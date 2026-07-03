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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolarityVetoSignalScorerTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String, mentions: List<EntityMention> = emptyList()): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = 0.8,
        )

    private fun mention(resolvedId: String): EntityMention =
        EntityMention(span = resolvedId, type = "Organization", resolvedId = resolvedId, role = MentionRole.OBJECT)

    @Test
    fun `fires when a shared entity is asserted by one side and negated by the other`() {
        val pair = CandidatePair(
            proposition("Jim works at Acme", mentions = listOf(mention("org:acme"))),
            proposition("Jim no longer works at Acme", mentions = listOf(mention("org:acme"))),
        )

        val score = PolarityVetoSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals("polarity-veto", score.signal)
        assertEquals(0.0, score.score, 1e-9)
        assertEquals(0.0, score.weight, 1e-9)
        assertTrue(score.veto)
        assertEquals("opposite polarity about shared entities", score.explanation)
    }

    @Test
    fun `does not fire when there is no shared entity`() {
        val pair = CandidatePair(
            proposition("Jim works at Acme", mentions = listOf(mention("org:acme"))),
            proposition("Jim no longer works at Globex", mentions = listOf(mention("org:globex"))),
        )

        assertNull(PolarityVetoSignalScorer().score(pair, contextId))
    }

    @Test
    fun `does not fire when both sides share the same polarity`() {
        val pair = CandidatePair(
            proposition("Jim works at Acme", mentions = listOf(mention("org:acme"))),
            proposition("Jim is employed at Acme", mentions = listOf(mention("org:acme"))),
        )

        assertNull(PolarityVetoSignalScorer().score(pair, contextId))
    }

    @Test
    fun `never fires when neither side has any mentions, even with opposite polarity text`() {
        val pair = CandidatePair(
            proposition("Jim works at Acme"),
            proposition("Jim no longer works at Acme"),
        )

        assertNull(PolarityVetoSignalScorer().score(pair, contextId))
    }
}
