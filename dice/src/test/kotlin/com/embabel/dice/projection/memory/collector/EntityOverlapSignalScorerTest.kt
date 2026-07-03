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
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.spi.CandidatePair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EntityOverlapSignalScorerTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String, mentions: List<EntityMention> = emptyList()): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = 0.8,
        )

    private fun mention(resolvedId: String, span: String = resolvedId, type: String = "Person"): EntityMention =
        EntityMention(span = span, type = type, resolvedId = resolvedId, role = MentionRole.SUBJECT)

    @Test
    fun `scores full overlap as 1 with neutral weight when both sides share the same mention`() {
        val pair = CandidatePair(
            proposition("a", mentions = listOf(mention("person:jim"))),
            proposition("b", mentions = listOf(mention("person:jim"))),
        )

        val score = EntityOverlapSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals("entity-overlap", score.signal)
        assertEquals(1.0, score.score, 1e-9)
        assertEquals(1.0, score.weight, 1e-9)
        assertFalse(score.veto)
    }

    @Test
    fun `scores partial overlap as jaccard of the mention id sets`() {
        val pair = CandidatePair(
            proposition("a", mentions = listOf(mention("person:jim"), mention("org:acme"))),
            proposition("b", mentions = listOf(mention("person:jim"))),
        )

        val score = EntityOverlapSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals(0.5, score.score, 1e-9)
    }

    @Test
    fun `abstains when the anchor has no mentions`() {
        val pair = CandidatePair(
            proposition("a", mentions = emptyList()),
            proposition("b", mentions = listOf(mention("person:jim"))),
        )

        assertNull(EntityOverlapSignalScorer().score(pair, contextId))
    }

    @Test
    fun `abstains when the member has no mentions`() {
        val pair = CandidatePair(
            proposition("a", mentions = listOf(mention("person:jim"))),
            proposition("b", mentions = emptyList()),
        )

        assertNull(EntityOverlapSignalScorer().score(pair, contextId))
    }

    @Test
    fun `uses default weight of 1 0 when constructed without args`() {
        val pair = CandidatePair(
            proposition("a", mentions = listOf(mention("person:jim"))),
            proposition("b", mentions = listOf(mention("person:jim"))),
        )

        val score = EntityOverlapSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals(1.0, score.weight, 1e-9)
    }

    @Test
    fun `uses custom weight when provided in constructor`() {
        val pair = CandidatePair(
            proposition("a", mentions = listOf(mention("person:jim"))),
            proposition("b", mentions = listOf(mention("person:jim"))),
        )

        val score = EntityOverlapSignalScorer(weight = 0.3).score(pair, contextId)

        requireNotNull(score)
        assertEquals(0.3, score.weight, 1e-9)
    }
}
