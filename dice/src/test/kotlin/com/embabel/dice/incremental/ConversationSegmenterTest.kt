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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ConversationSegmenterTest {

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private data class Msg(val id: String, val minute: Long, val weight: Int = 1)

    private fun at(m: Msg): Instant = base.plusSeconds(m.minute * 60)

    private fun ids(segment: List<Msg>) = segment.map { it.id }

    private fun minutesFromBase(minutes: Long): Instant = base.plusSeconds(minutes * 60)

    /** Far enough past the last message that every segment has settled. */
    private val longAfter: Instant = base.plusSeconds(Duration.ofDays(30).seconds)

    @Test
    fun `an overnight lull does not split a conversation`() {
        // Someone replies 24h later, across timezones: still one conversation.
        val msgs = listOf(Msg("m0", 0), Msg("m1", 1), Msg("m2", 24 * 60), Msg("m3", 24 * 60 + 1))
        val segments = ConversationSegmenter().closedSegments(msgs, longAfter, ::at)
        assertEquals(1, segments.size)
        assertEquals(listOf("m0", "m1", "m2", "m3"), ids(segments[0]))
    }

    @Test
    fun `splits only once over maxSize, cutting at the largest interior gap`() {
        // A 98-minute lull between m2 and m3 is the natural seam.
        val msgs = listOf(
            Msg("m0", 0), Msg("m1", 1), Msg("m2", 2),
            Msg("m3", 100), Msg("m4", 101), Msg("m5", 102),
            Msg("m6", 103), Msg("m7", 104), Msg("m8", 105), Msg("m9", 106),
        )
        val segments = ConversationSegmenter(maxSize = 5).closedSegments(msgs, longAfter, ::at)
        assertEquals(3, segments.size)
        assertEquals(listOf("m0", "m1", "m2"), ids(segments[0]))
        // No further lull, so the next cut simply fills to maxSize.
        assertEquals(listOf("m3", "m4", "m5", "m6", "m7"), ids(segments[1]))
        assertEquals(listOf("m8", "m9"), ids(segments[2]))
    }

    @Test
    fun `a run at or under maxSize is never split, however lulled`() {
        val msgs = (0..9).map { Msg("m$it", it * 500L) } // ~8h between each
        val segments = ConversationSegmenter(maxSize = 10).closedSegments(msgs, longAfter, ::at)
        assertEquals(1, segments.size)
        assertEquals(10, segments[0].size)
    }

    @Test
    fun `withholds the still-open trailing segment until it settles`() {
        val msgs = listOf(Msg("m0", 0), Msg("m1", 1))
        val segmenter = ConversationSegmenter(settle = Duration.ofHours(24))

        // Two hours after the last message: still in progress, so nothing is emitted.
        assertTrue(segmenter.closedSegments(msgs, minutesFromBase(121), ::at).isEmpty())

        // Two days later it has gone quiet.
        val settled = segmenter.closedSegments(msgs, minutesFromBase(2 * 24 * 60), ::at)
        assertEquals(listOf("m0", "m1"), ids(settled.single()))
    }

    @Test
    fun `earlier segments are closed even while the trailing one is open`() {
        val msgs = (0..6).map { Msg("m$it", it.toLong()) }
        val segments = ConversationSegmenter(maxSize = 5, settle = Duration.ofHours(24))
            .closedSegments(msgs, minutesFromBase(7), ::at)
        // m5, m6 are still open; the capped prefix is safe to extract.
        assertEquals(1, segments.size)
        assertEquals(listOf("m0", "m1", "m2", "m3", "m4"), ids(segments[0]))
    }

    @Test
    fun `maxSilence, when set, does split a dormant channel`() {
        val msgs = listOf(Msg("m0", 0), Msg("m1", 1), Msg("m2", 30 * 24 * 60), Msg("m3", 30 * 24 * 60 + 1))
        val segments = ConversationSegmenter(maxSilence = Duration.ofDays(14))
            .closedSegments(msgs, longAfter.plusSeconds(Duration.ofDays(60).seconds), ::at)
        assertEquals(2, segments.size)
        assertEquals(listOf("m0", "m1"), ids(segments[0]))
        assertEquals(listOf("m2", "m3"), ids(segments[1]))
    }

    @Test
    fun `the cut leaves at least minItems behind`() {
        // The widest gap sits after m0, but minItems=3 forbids cutting there.
        val msgs = listOf(
            Msg("m0", 0),
            Msg("m1", 100), Msg("m2", 101), Msg("m3", 102), Msg("m4", 103), Msg("m5", 104), Msg("m6", 105),
        )
        val segments = ConversationSegmenter(maxSize = 5, minItems = 3).closedSegments(msgs, longAfter, ::at)
        assertEquals(listOf("m0", "m1", "m2", "m3", "m4"), ids(segments[0]))
    }

    @Test
    fun `size accessor weights items against maxSize`() {
        val msgs = listOf(Msg("m0", 0, weight = 4), Msg("m1", 1, weight = 4), Msg("m2", 2, weight = 4))
        val segments = ConversationSegmenter(maxSize = 10)
            .closedSegments(msgs, longAfter, ::at) { it.weight }
        assertEquals(2, segments.size)
        assertEquals(listOf("m0", "m1"), ids(segments[0]))
        assertEquals(listOf("m2"), ids(segments[1]))
    }

    @Test
    fun `a lone item heavier than maxSize becomes its own segment`() {
        val msgs = listOf(Msg("m0", 0, weight = 99), Msg("m1", 1, weight = 1))
        val segments = ConversationSegmenter(maxSize = 10)
            .closedSegments(msgs, longAfter, ::at) { it.weight }
        assertEquals(listOf("m0"), ids(segments[0]))
        assertEquals(listOf("m1"), ids(segments[1]))
    }

    @Test
    fun `drops segments below the min-item floor`() {
        val msgs = listOf(Msg("m0", 0))
        assertTrue(ConversationSegmenter(minItems = 2).closedSegments(msgs, longAfter, ::at).isEmpty())
    }

    @Test
    fun `empty input yields nothing`() {
        assertEquals(emptyList<List<Msg>>(), ConversationSegmenter().closedSegments(emptyList<Msg>(), base, ::at))
    }

    @Test
    fun `items are segmented in time order regardless of input order`() {
        val msgs = listOf(Msg("m2", 2), Msg("m0", 0), Msg("m1", 1))
        val segments = ConversationSegmenter().closedSegments(msgs, longAfter, ::at)
        assertEquals(listOf("m0", "m1", "m2"), ids(segments.single()))
    }
}
