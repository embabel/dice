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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.spi.CandidatePair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GroundingOverlapSignalScorerTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String, grounding: List<String> = emptyList()): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList<EntityMention>(),
            confidence = 0.8,
            grounding = grounding,
        )

    @Test
    fun `scores overlap as jaccard of grounding chunk ids with conservative weight`() {
        val pair = CandidatePair(
            proposition("a", grounding = listOf("chunk-1", "chunk-2")),
            proposition("b", grounding = listOf("chunk-1")),
        )

        val score = GroundingOverlapSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals("grounding-overlap", score.signal)
        assertEquals(0.5, score.score, 1e-9)
        assertEquals(0.5, score.weight, 1e-9)
        assertFalse(score.veto)
    }

    @Test
    fun `abstains when the anchor has no grounding`() {
        val pair = CandidatePair(
            proposition("a", grounding = emptyList()),
            proposition("b", grounding = listOf("chunk-1")),
        )

        assertNull(GroundingOverlapSignalScorer().score(pair, contextId))
    }

    @Test
    fun `abstains when the member has no grounding`() {
        val pair = CandidatePair(
            proposition("a", grounding = listOf("chunk-1")),
            proposition("b", grounding = emptyList()),
        )

        assertNull(GroundingOverlapSignalScorer().score(pair, contextId))
    }

    @Test
    fun `uses default weight of 0 5 when constructed without args`() {
        val pair = CandidatePair(
            proposition("a", grounding = listOf("chunk-1", "chunk-2")),
            proposition("b", grounding = listOf("chunk-1")),
        )

        val score = GroundingOverlapSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals(0.5, score.weight, 1e-9)
    }

    @Test
    fun `uses custom weight when provided in constructor`() {
        val pair = CandidatePair(
            proposition("a", grounding = listOf("chunk-1", "chunk-2")),
            proposition("b", grounding = listOf("chunk-1")),
        )

        val score = GroundingOverlapSignalScorer(weight = 0.8).score(pair, contextId)

        requireNotNull(score)
        assertEquals(0.8, score.weight, 1e-9)
    }
}
