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
package com.embabel.dice.common

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.pipeline.PropositionExtractionStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract for the [DiceEvent] subtypes.
 *
 * Constructor shapes are 1:1 with `RevisionResult` plus `PropositionPersisted` and the two
 * batch aggregates.
 */
class DiceEventTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String = "Jim is an expert in GOAP"): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = listOf(
                EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT),
            ),
            confidence = 0.9,
        )

    @Test
    fun `PropositionDiscovered is a DiceEvent with a timestamp and carries the proposition`() {
        val p = proposition()
        val event = PropositionDiscovered(p)

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertSame(p, event.proposition)
    }

    @Test
    fun `PropositionMerged is a DiceEvent carrying original and revised`() {
        val original = proposition("original")
        val revised = proposition("revised")
        val event = PropositionMerged(original, revised)

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertSame(original, event.original)
        assertSame(revised, event.revised)
    }

    @Test
    fun `PropositionReinforced is a DiceEvent carrying original and revised`() {
        val original = proposition("original")
        val revised = proposition("revised")
        val event = PropositionReinforced(original, revised)

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertSame(original, event.original)
        assertSame(revised, event.revised)
    }

    @Test
    fun `PropositionContradicted is a DiceEvent carrying contextId and both propositions`() {
        val original = proposition("original")
        val new = proposition("new")
        val event = PropositionContradicted(contextId, original, new)

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertEquals(contextId, event.contextId)
        assertSame(original, event.original)
        assertSame(new, event.new)
    }

    @Test
    fun `PropositionGeneralized is a DiceEvent carrying the proposition and its sources`() {
        val p = proposition("generalization")
        val sources = listOf(proposition("source-a"), proposition("source-b"))
        val event = PropositionGeneralized(p, sources)

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertSame(p, event.proposition)
        assertTrue(event.generalizes.size == 2)
    }

    @Test
    fun `PropositionPersisted is a DiceEvent carrying the persisted proposition`() {
        val p = proposition()
        val event = PropositionPersisted(p)

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertSame(p, event.proposition)
    }

    @Test
    fun `ProjectionBatchCompleted is a DiceEvent carrying the four counts`() {
        val event = ProjectionBatchCompleted(
            successCount = 3,
            skipCount = 1,
            failureCount = 2,
            totalCount = 6,
        )

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertTrue(event.successCount == 3)
        assertTrue(event.skipCount == 1)
        assertTrue(event.failureCount == 2)
        assertTrue(event.totalCount == 6)
    }

    @Test
    fun `ExtractionBatchCompleted is a DiceEvent carrying extraction stats`() {
        val stats = PropositionExtractionStats(
            newCount = 2,
            mergedCount = 1,
            reinforcedCount = 0,
            contradictedCount = 0,
            generalizedCount = 0,
        )
        val event = ExtractionBatchCompleted(stats)

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertSame(stats, event.stats)
    }

    @Test
    fun `ContradictionEvent is deprecated`() {
        // Reflection: the legacy empty event must be deprecated this release (deprecate-only, not removed).
        val deprecated = ContradictionEvent::class.java.getAnnotation(Deprecated::class.java)
        assertNotNull(deprecated, "ContradictionEvent must be @Deprecated in favour of PropositionContradicted")
    }

    @Test
    fun `ContradictionEvent deprecation names PropositionContradicted as the replacement`() {
        // The replaceWith expression must point IDEs/migrations at the richer event,
        // not merely flag the type as deprecated.
        val replaceWith = ContradictionEvent::class.annotations
            .filterIsInstance<Deprecated>()
            .single()
            .replaceWith
        assertTrue(
            replaceWith.expression.contains("PropositionContradicted"),
            "ReplaceWith expression must name PropositionContradicted, was '${replaceWith.expression}'",
        )
    }
}
