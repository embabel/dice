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

    private data class Msg(val id: String, val minute: Long)

    private fun at(m: Msg): Instant = base.plusSeconds(m.minute * 60)

    private fun segmenter(min: Int = 2) = ConversationSegmenter(gap = Duration.ofMinutes(45), minItems = min)

    private fun ids(seg: List<Msg>) = seg.map { it.id }

    @Test
    fun `gap-splits into runs, both returned when both have gone quiet`() {
        val msgs = listOf(Msg("m0", 0), Msg("m1", 1), Msg("m2", 2), Msg("m3", 62), Msg("m4", 63), Msg("m5", 64))
        val segs = segmenter().closedSegments(msgs, base.plusSeconds(200 * 60), ::at)
        assertEquals(2, segs.size)
        assertEquals(listOf("m0", "m1", "m2"), ids(segs[0]))
        assertEquals(listOf("m3", "m4", "m5"), ids(segs[1]))
    }

    @Test
    fun `withholds the still-open trailing run`() {
        val msgs = listOf(Msg("m0", 0), Msg("m1", 1), Msg("m2", 2), Msg("m3", 62), Msg("m4", 63), Msg("m5", 64))
        // now only 1 min after the last message → the second run is still open.
        val segs = segmenter().closedSegments(msgs, base.plusSeconds(65 * 60), ::at)
        assertEquals(1, segs.size)
        assertEquals(listOf("m0", "m1", "m2"), ids(segs[0]))
    }

    @Test
    fun `does not split a long continuous run (windowing is the analyzer's job)`() {
        val msgs = (0..99).map { Msg("m$it", it.toLong()) } // 100 consecutive minutes → one run
        val segs = segmenter().closedSegments(msgs, base.plusSeconds(500 * 60), ::at)
        assertEquals(1, segs.size)
        assertEquals(100, segs[0].size)
    }

    @Test
    fun `drops runs below the min-item floor`() {
        val msgs = listOf(Msg("m0", 0), Msg("m1", 100)) // two gap-isolated singletons
        assertTrue(segmenter(min = 2).closedSegments(msgs, base.plusSeconds(500 * 60), ::at).isEmpty())
    }

    @Test
    fun `empty input yields nothing`() {
        assertEquals(emptyList<List<Msg>>(), segmenter().closedSegments(emptyList<Msg>(), base, ::at))
    }
}
