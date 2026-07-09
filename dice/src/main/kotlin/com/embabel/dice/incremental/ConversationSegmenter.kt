/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice.incremental

import java.time.Duration
import java.time.Instant

/**
 * Splits a timestamped item stream (e.g. a chat channel's messages) into conversation
 * segments, so each segment can be handed to [IncrementalSource]-based extraction as
 * one conversation.
 *
 * Silence is deliberately **not** a conversation boundary. In async chat a thread
 * routinely goes quiet for eight or twenty-four hours while contributors sleep across
 * timezones, and cutting on that silence would shred exactly the long-running
 * conversations most worth extracting. Segments are instead bounded by *size*: a run
 * is cut only once it exceeds [maxSize], and the cut lands on the largest interior
 * gap, so the seam falls in a lull rather than mid-exchange.
 *
 * This decides conversation *boundaries*; it deliberately does NOT window by size or
 * overlap — that is the incremental analyzer's job (see [WindowConfig]), which chunks
 * *within* a segment. Those windows, not whole segments, are what an LLM sees.
 *
 * A segment is **closed** — safe to extract — once further content follows it, or, for
 * the trailing segment, once it has been quiet for longer than [settle]. The still-open
 * trailing segment is withheld so an in-progress conversation isn't extracted and then
 * re-extracted when the rest arrives. Segments with fewer than [minItems] items are
 * dropped.
 *
 * Pure and deterministic — no LLM, no I/O. Generic over the item type: the caller
 * supplies a timestamp accessor and, optionally, a size accessor.
 *
 * @param maxSize ceiling on a single segment, expressed in whatever units the `size`
 *   accessor passed to [closedSegments] returns. Under the default accessor (one unit
 *   per item) it is a message count; a caller supplying a token estimator must scale
 *   this accordingly.
 * @param settle how long the trailing segment must be quiet before it is extractable.
 * @param minItems segments holding fewer items than this are dropped.
 * @param maxSilence optional ceiling beyond which a silence *is* taken as a boundary,
 *   for channels that go dormant and are later revived. Null — the default — means
 *   segmentation is driven purely by size.
 */
class ConversationSegmenter(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val settle: Duration = DEFAULT_SETTLE,
    private val minItems: Int = 1,
    private val maxSilence: Duration? = null,
) {

    init {
        require(maxSize > 0) { "maxSize must be positive" }
        require(minItems > 0) { "minItems must be positive" }
        require(!settle.isNegative) { "settle must not be negative" }
        require(maxSilence == null || !maxSilence.isNegative) { "maxSilence must not be negative" }
    }

    /**
     * The closed conversation segments in [items] (a single stream, e.g. one channel),
     * each a time-ordered sub-list. [now] is the reference "current time" used to decide
     * whether the trailing segment has settled. [size] weights each item against
     * [maxSize]; the default counts items.
     */
    fun <T> closedSegments(
        items: List<T>,
        now: Instant,
        at: (T) -> Instant,
        size: (T) -> Int = { 1 },
    ): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedWith(compareBy(at))
        val segments = dormancyRuns(sorted, at).flatMap { capBySize(it, at, size) }

        return segments.filterIndexed { idx, segment ->
            // Only the final segment can still be growing; everything before it is closed
            // by the content that follows.
            val settled = idx != segments.lastIndex ||
                Duration.between(at(segment.last()), now) > settle
            settled && segment.size >= minItems
        }
    }

    /** Split only where a silence exceeds [maxSilence]; one run throughout when it is null. */
    private fun <T> dormancyRuns(sorted: List<T>, at: (T) -> Instant): List<List<T>> {
        val ceiling = maxSilence ?: return listOf(sorted)
        val runs = mutableListOf<MutableList<T>>()
        for (item in sorted) {
            val previous = runs.lastOrNull()?.last()
            if (previous == null || Duration.between(at(previous), at(item)) > ceiling) {
                runs.add(mutableListOf(item))
            } else {
                runs.last().add(item)
            }
        }
        return runs
    }

    /** Repeatedly carve [maxSize]-bounded segments off the front of [run]. */
    private fun <T> capBySize(run: List<T>, at: (T) -> Instant, size: (T) -> Int): List<List<T>> {
        val segments = mutableListOf<List<T>>()
        var rest = run
        while (rest.isNotEmpty()) {
            if (rest.sumOf { size(it) } <= maxSize) {
                segments.add(rest)
                break
            }
            val cut = cutIndex(rest, at, size)
            segments.add(rest.subList(0, cut))
            rest = rest.subList(cut, rest.size)
        }
        return segments
    }

    /**
     * Where to cut an over-sized run: the largest gap within the longest prefix that
     * still fits in [maxSize], constrained to leave at least [minItems] behind so a big
     * lull near the head can't strand items in a sub-[minItems] fragment that gets
     * dropped. Ties favour the later index, keeping segments full.
     */
    private fun <T> cutIndex(rest: List<T>, at: (T) -> Instant, size: (T) -> Int): Int {
        var accumulated = 0
        var limit = 0
        for ((index, item) in rest.withIndex()) {
            val weight = size(item)
            // Always take at least one item, even one heavier than maxSize on its own.
            if (index > 0 && accumulated + weight > maxSize) break
            accumulated += weight
            limit = index + 1
        }

        var cut = limit
        var widest = Duration.ofSeconds(-1)
        // `limit` reaches rest.size only when a lone over-sized item fills the prefix,
        // in which case there is no interior gap to consider.
        for (index in minItems..minOf(limit, rest.size - 1)) {
            val gap = Duration.between(at(rest[index - 1]), at(rest[index]))
            if (gap >= widest) {
                widest = gap
                cut = index
            }
        }
        return cut
    }

    companion object {
        /**
         * Segment ceiling under the default one-unit-per-item size accessor, i.e. messages.
         * Comfortably many analyzer windows ([WindowConfig.windowSize]) wide, while small
         * enough that a segment remains plausibly a single conversation.
         */
        const val DEFAULT_MAX_SIZE: Int = 500

        /** Quiet period after which a trailing conversation is taken to be finished. */
        @JvmField
        val DEFAULT_SETTLE: Duration = Duration.ofHours(24)
    }
}
