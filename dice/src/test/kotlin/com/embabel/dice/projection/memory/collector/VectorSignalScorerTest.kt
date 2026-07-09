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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VectorSignalScorerTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList<EntityMention>(),
            confidence = 0.8,
        )

    @Test
    fun `abstains when the pair carries no proposal score`() {
        val pair = CandidatePair(proposition("a"), proposition("b"), proposalScore = null)

        val score = VectorSignalScorer().score(pair, contextId)

        assertNull(score)
    }

    @Test
    fun `scores the pair using its proposal score, clamped to 0 to 1, with neutral weight`() {
        val pair = CandidatePair(proposition("a"), proposition("b"), proposalScore = 0.83)

        val score = VectorSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals("vector", score.signal)
        assertEquals(0.83, score.score, 1e-9)
        assertEquals(1.0, score.weight, 1e-9)
        assertEquals(false, score.veto)
    }

    @Test
    fun `clamps an out-of-range proposal score into 0 to 1`() {
        val pair = CandidatePair(proposition("a"), proposition("b"), proposalScore = 1.4)

        val score = VectorSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals(1.0, score.score, 1e-9)
    }

    @Test
    fun `uses default weight of 1 0 when constructed without args`() {
        val pair = CandidatePair(proposition("a"), proposition("b"), proposalScore = 0.83)

        val score = VectorSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals(1.0, score.weight, 1e-9)
    }

    @Test
    fun `uses custom weight when provided in constructor`() {
        val pair = CandidatePair(proposition("a"), proposition("b"), proposalScore = 0.83)

        val score = VectorSignalScorer(weight = 0.3).score(pair, contextId)

        requireNotNull(score)
        assertEquals(0.3, score.weight, 1e-9)
    }
}
