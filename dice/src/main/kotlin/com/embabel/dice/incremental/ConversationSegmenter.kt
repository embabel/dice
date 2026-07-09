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
 * Splits a timestamped item stream (e.g. a chat channel's messages) into coherent
 * conversation segments by time-silence, so each segment can be handed to
 * [IncrementalSource]-based extraction as one conversation.
 *
 * This decides conversation *boundaries*; it deliberately does NOT window by size
 * or overlap — that is the incremental analyzer's job (see `WindowConfig`), which
 * chunks *within* a conversation. Feeding a firehose straight to the analyzer
 * would window by index and cut mid-conversation; segmenting first keeps each
 * extraction unit coherent.
 *
 * A segment is **closed** — safe to extract — when a gap longer than [gap] follows
 * it, or (the final run) its last item is older than [gap] before `now`. The still-
 * open trailing run is withheld until it closes, so an in-progress conversation
 * isn't extracted then re-extracted when the rest arrives. Segments with fewer than
 * [minItems] items are dropped.
 *
 * Pure and deterministic — no LLM, no I/O. Generic over the item type: the caller
 * supplies a timestamp accessor.
 */
class ConversationSegmenter(
    private val gap: Duration,
    private val minItems: Int = 1,
) {

    /**
     * The closed conversation segments in [items] (a single stream, e.g. one
     * channel), each a time-ordered sub-list. [now] is the reference "current
     * time" used to decide whether the final run has gone quiet.
     */
    fun <T> closedSegments(items: List<T>, now: Instant, at: (T) -> Instant): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedWith(compareBy(at))

        // Split into runs wherever the inter-item gap exceeds `gap`.
        val runs = mutableListOf<MutableList<T>>()
        for (item in sorted) {
            val last = runs.lastOrNull()?.last()
            if (last == null || Duration.between(at(last), at(item)) > gap) {
                runs.add(mutableListOf(item))
            } else {
                runs.last().add(item)
            }
        }

        return runs.filterIndexed { idx, run ->
            // The last run is closed only once it's gone quiet (older than `gap`).
            val closed = idx != runs.lastIndex || Duration.between(at(run.last()), now) > gap
            closed && run.size >= minItems
        }
    }
}
