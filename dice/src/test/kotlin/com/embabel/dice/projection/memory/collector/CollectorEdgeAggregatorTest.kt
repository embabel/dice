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
package com.embabel.dice.projection.memory.collector

import com.embabel.dice.spi.CandidatePair
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.agent.core.ContextId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollectorEdgeAggregatorTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList<EntityMention>(),
            confidence = 0.8,
        )

    @Test
    fun `weighted mean over two non-abstaining signals`() {
        val anchor = proposition("anchor")
        val member = proposition("member")
        val pair = CandidatePair(anchor, member)
        val signals = listOf(
            CollectorSignalScore(signal = "a", score = 1.0, weight = 1.0),
            CollectorSignalScore(signal = "b", score = 0.0, weight = 3.0),
        )

        val edge = CollectorEdgeAggregator.aggregate(pair, signals)

        // (1*1.0 + 3*0.0) / (1+3) = 0.25
        assertEquals(0.25, edge.aggregateScore, 1e-9)
        assertFalse(edge.vetoed)
        assertEquals(anchor.id, edge.anchorId)
        assertEquals(member.id, edge.memberId)
        assertEquals(signals, edge.signals)
    }

    @Test
    fun `a vetoed signal forces vetoed true regardless of score`() {
        val anchor = proposition("anchor")
        val member = proposition("member")
        val pair = CandidatePair(anchor, member)
        val signals = listOf(
            CollectorSignalScore(signal = "a", score = 0.95, weight = 1.0),
            CollectorSignalScore(signal = "veto-signal", score = 0.0, weight = 1.0, veto = true),
        )

        val edge = CollectorEdgeAggregator.aggregate(pair, signals)

        assertTrue(edge.vetoed)
    }

    @Test
    fun `an all-abstain pair yields aggregate score 0`() {
        val anchor = proposition("anchor")
        val member = proposition("member")
        val pair = CandidatePair(anchor, member)

        val edge = CollectorEdgeAggregator.aggregate(pair, emptyList())

        assertEquals(0.0, edge.aggregateScore, 1e-9)
        assertFalse(edge.vetoed)
    }
}
