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
 * Brief silence is deliberately **not** a conversation boundary. In async chat a thread
 * routinely goes quiet for eight or twenty-four hours while contributors sleep across
 * timezones, and cutting on that silence would shred exactly the long-running
 * conversations most worth extracting. Within a conversation, segments are bounded by
 * *size*: a run is cut only once it exceeds [maxSize], and the cut lands on the largest
 * interior gap, so the seam falls in a lull rather than mid-exchange.
 *
 * A silence longer than [settle] *is* a boundary, because [settle] is already the point
 * at which a trailing conversation is declared finished and handed to extraction. Once a
 * conversation has been extracted, a later reply must begin a new segment rather than
 * re-open the extracted one — so the same duration necessarily governs both.
 *
 * This decides conversation *boundaries*; it deliberately does NOT window by size or
 * overlap — that is the incremental analyzer's job (see [WindowConfig]), which chunks
 * *within* a segment. Those windows, not whole segments, are what an LLM sees.
 *
 * A segment is **closed** — safe to extract — once further content follows it, or, for
 * the trailing segment, once it has been quiet for longer than [settle]. The still-open
 * trailing segment is withheld so an in-progress conversation isn't extracted and then
 * re-extracted when the rest arrives.
 *
 * Pure and deterministic — no LLM, no I/O. Generic over the item type: the caller
 * supplies a timestamp accessor and, optionally, a size accessor. Segmentation of a given
 * prefix is stable as later items arrive, so repeated calls over a growing stream emit
 * each closed segment exactly once — provided items are ingested in timestamp order. An
 * item back-dated to within [settle] of an already-extracted segment will re-open it.
 *
 * @param maxSize ceiling on a single segment, expressed in whatever units the `size`
 *   accessor passed to [closedSegments] returns. Under the default accessor (one unit per
 *   item) it is a message count; a caller supplying a token estimator must scale this
 *   accordingly. A segment absorbing an undersized tail (see [minItems]) may exceed it by
 *   up to `minItems - 1` items: [maxSize] is a coherence cap, not a hard limit.
 * @param settle how long a conversation must be quiet before it is taken to be finished:
 *   the trailing segment becomes extractable, and any later item starts a new segment.
 * @param minItems segments holding fewer items than this are dropped. Cuts are placed so
 *   that no segment falls below the floor, and an undersized tail is folded back into the
 *   segment before it, so only a whole conversation shorter than [minItems] is dropped.
 *   Setting [minItems] above the number of items [maxSize] admits leaves every segment
 *   below the floor, discarding the stream entirely.
 */
class ConversationSegmenter(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val settle: Duration = DEFAULT_SETTLE,
    private val minItems: Int = 1,
) {

    init {
        require(maxSize > 0) { "maxSize must be positive" }
        require(minItems > 0) { "minItems must be positive" }
        require(!settle.isNegative) { "settle must not be negative" }
    }

    /**
     * The closed conversation segments in [items] (a single stream, e.g. one channel), each
     * a time-ordered sub-list. [now] is the reference "current time" used to decide whether
     * the trailing segment has settled; a [now] preceding the last item leaves that segment
     * open. [size] weights each item against [maxSize]; the default counts items. Both
     * accessors are invoked exactly once per item.
     */
    fun <T> closedSegments(
        items: List<T>,
        now: Instant,
        timestamp: (T) -> Instant,
        size: (T) -> Int = { 1 },
    ): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        // Sort indices against cached instants: a comparator over `timestamp` would re-invoke
        // it on every comparison, and the accessor may be costly (a token estimator, say).
        val unsorted = Array(items.size) { timestamp(items[it]) }
        val order = items.indices.sortedBy { unsorted[it] }
        val sorted = order.map { items[it] }
        val instants = Array(order.size) { unsorted[order[it]] }
        val weights = IntArray(sorted.size) { size(sorted[it]) }

        val conversations = conversations(instants)
        val segments = mutableListOf<Span>()
        for ((index, conversation) in conversations.withIndex()) {
            val carved = capBySize(conversation, instants, weights)
            // Only the final conversation can still be growing; everything before it is
            // closed by the content that follows.
            val open = index == conversations.lastIndex &&
                Duration.between(instants[conversation.endExclusive - 1], now) <= settle
            if (open) carved.removeAt(carved.lastIndex) else mergeUndersizedTail(carved)
            segments += carved
        }
        return segments
            .filter { it.size >= minItems }
            .map { sorted.slice(it.start until it.endExclusive) }
    }

    /** A half-open index range into the sorted item list. */
    private data class Span(val start: Int, val endExclusive: Int) {
        val size: Int get() = endExclusive - start
    }

    /** Split wherever a silence exceeds [settle]: each resulting run is one conversation. */
    private fun conversations(instants: Array<Instant>): List<Span> {
        val spans = mutableListOf<Span>()
        var start = 0
        for (index in 1 until instants.size) {
            if (Duration.between(instants[index - 1], instants[index]) > settle) {
                spans += Span(start, index)
                start = index
            }
        }
        return spans + Span(start, instants.size)
    }

    /** Repeatedly carve [maxSize]-bounded segments off the front of [conversation]. */
    private fun capBySize(conversation: Span, instants: Array<Instant>, weights: IntArray): MutableList<Span> {
        val segments = mutableListOf<Span>()
        var start = conversation.start
        while (start < conversation.endExclusive) {
            val limit = prefixLimit(start, conversation.endExclusive, weights)
            if (limit == conversation.endExclusive) {
                segments += Span(start, limit)
                break
            }
            val cut = cutIndex(start, limit, instants)
            segments += Span(start, cut)
            start = cut
        }
        return segments
    }

    /**
     * One past the last item of the longest prefix from [start] that fits within [maxSize].
     * Always takes at least one item, so an item heavier than [maxSize] on its own still
     * makes progress.
     */
    private fun prefixLimit(start: Int, endExclusive: Int, weights: IntArray): Int {
        var accumulated = 0L
        var index = start
        while (index < endExclusive) {
            val weight = weights[index]
            if (index > start && accumulated + weight > maxSize) break
            accumulated += weight
            index++
        }
        return index
    }

    /**
     * Where to cut an over-sized conversation: the largest gap within the prefix that fits,
     * constrained to leave at least [minItems] behind so a big lull near the head can't
     * strand items in a sub-[minItems] fragment. Ties favour the later index, keeping
     * segments full. Falls back to [limit] when the floor admits no gap — either a lone
     * over-sized item fills the prefix, or [minItems] exceeds what [maxSize] holds.
     */
    private fun cutIndex(start: Int, limit: Int, instants: Array<Instant>): Int {
        var cut = limit
        var widest = Duration.ZERO
        for (index in start + minItems..limit) {
            val gap = Duration.between(instants[index - 1], instants[index])
            if (gap >= widest) {
                widest = gap
                cut = index
            }
        }
        return cut
    }

    /**
     * Fold a trailing segment that falls below [minItems] into the one before it, rather than
     * let the item-count filter discard those items. A conversation carved into a single
     * undersized segment has nothing to fold into, and is dropped.
     */
    private fun mergeUndersizedTail(segments: MutableList<Span>) {
        if (segments.size < 2 || segments.last().size >= minItems) return
        val tail = segments.removeAt(segments.lastIndex)
        segments[segments.lastIndex] = segments.last().copy(endExclusive = tail.endExclusive)
    }

    companion object {
        /**
         * Segment ceiling under the default one-unit-per-item size accessor, i.e. messages.
         * Comfortably many analyzer windows ([WindowConfig.windowSize]) wide, while small
         * enough that a segment remains plausibly a single conversation.
         */
        const val DEFAULT_MAX_SIZE: Int = 500

        /** Quiet period after which a conversation is taken to be finished. */
        @JvmField
        val DEFAULT_SETTLE: Duration = Duration.ofHours(24)
    }
}
