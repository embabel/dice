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
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LexicalSignalScorerTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList<EntityMention>(),
            confidence = 0.8,
        )

    @Test
    fun `scores identical text as a perfect match with neutral weight`() {
        val pair = CandidatePair(proposition("Jim is an expert in GOAP"), proposition("Jim is an expert in GOAP"))

        val score = LexicalSignalScorer().score(pair, contextId)

        assertEquals("lexical", score.signal)
        assertEquals(1.0, score.score, 1e-9)
        assertEquals(0.5, score.weight, 1e-9)
        assertFalse(score.veto)
    }

    @Test
    fun `scores near-miss text using Jaro-Winkler similarity, case and padding insensitive`() {
        val pair = CandidatePair(proposition(" Jim is an expert in GOAP "), proposition("jim is an expert in goap"))

        val score = LexicalSignalScorer().score(pair, contextId)

        val expected = JaroWinklerSimilarity().apply("jim is an expert in goap", "jim is an expert in goap")
        assertEquals(expected, score.score, 1e-9)
    }

    @Test
    fun `never abstains, even for wildly different text`() {
        val pair = CandidatePair(proposition("Jim works at Acme"), proposition("The weather is nice today"))

        val score = LexicalSignalScorer().score(pair, contextId)

        assertEquals("lexical", score.signal)
        assertEquals(true, score.score in 0.0..1.0)
    }

    @Test
    fun `uses default weight of 0 5 when constructed without args`() {
        val pair = CandidatePair(proposition("Jim is an expert in GOAP"), proposition("Jim is an expert in GOAP"))

        val score = LexicalSignalScorer().score(pair, contextId)

        assertEquals(0.5, score.weight, 1e-9)
    }

    @Test
    fun `uses custom weight when provided in constructor`() {
        val pair = CandidatePair(proposition("Jim is an expert in GOAP"), proposition("Jim is an expert in GOAP"))

        val score = LexicalSignalScorer(weight = 0.2).score(pair, contextId)

        assertEquals(0.2, score.weight, 1e-9)
    }
}
