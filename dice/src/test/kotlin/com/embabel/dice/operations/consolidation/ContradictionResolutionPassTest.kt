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
package com.embabel.dice.operations.consolidation

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.PropositionRoutedToReview
import com.embabel.dice.common.RecordingDiceEventListener
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.revision.ClassifiedProposition
import com.embabel.dice.proposition.revision.PropositionRelation
import com.embabel.dice.proposition.revision.PropositionReviser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ContradictionResolutionPassTest {

    private val contextId = ContextId("test-context")

    private fun proposition(
        id: String,
        entityId: String,
        confidence: Double,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        contentRevised: Instant = Instant.now(),
        pinned: Boolean = false,
    ): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = "prop-$id",
            mentions = listOf(EntityMention(span = "e", type = "Person", resolvedId = entityId)),
            confidence = confidence,
            decay = 0.1,
            status = status,
            contentRevised = contentRevised,
            pinned = pinned,
        )

    @Test
    fun `name is contradiction-resolution`() {
        assertEquals("contradiction-resolution", ContradictionResolutionPass(mockk(relaxed = true)).name)
    }

    @Test
    fun `classifies only against entity-overlapping candidates`() {
        val a = proposition("a", "bob", 0.9)
        val b = proposition("b", "bob", 0.4)
        val unrelated = proposition("c", "alice", 0.7)
        val reviser = mockk<PropositionReviser>()
        every { reviser.classify(any(), any()) } returns emptyList()

        ContradictionResolutionPass(reviser).run(contextId, listOf(a, b, unrelated))

        // For 'a', candidate set must be only entity-overlapping 'b' (not 'alice')
        verify { reviser.classify(a, listOf(b)) }
        verify { reviser.classify(unrelated, emptyList()) }
    }

    @Test
    fun `transitions the weaker of a contradictory pair to CONTRADICTED`() {
        val stronger = proposition("a", "bob", 0.9)
        val weaker = proposition("b", "bob", 0.3)
        val reviser = mockk<PropositionReviser>()
        // When classifying 'stronger', 'weaker' comes back as CONTRADICTORY
        every { reviser.classify(stronger, listOf(weaker)) } returns listOf(
            ClassifiedProposition(weaker, PropositionRelation.CONTRADICTORY, 0.5),
        )
        every { reviser.classify(weaker, listOf(stronger)) } returns listOf(
            ClassifiedProposition(stronger, PropositionRelation.CONTRADICTORY, 0.5),
        )

        val result = ContradictionResolutionPass(reviser).run(contextId, listOf(stronger, weaker))

        val changed = assertInstanceOf(ConsolidationPassResult.Changed::class.java, result)
        assertEquals(1, changed.propositionsToSave.size)
        val transitioned = changed.propositionsToSave.single()
        assertEquals("b", transitioned.id)
        assertEquals(PropositionStatus.CONTRADICTED, transitioned.status)
    }

    @Test
    fun `equal-confidence symmetric contradiction retires exactly one, never both`() {
        // Same effective confidence (same confidence, decay, and content anchor) and a symmetric
        // reviser that reports the conflict from both sides. The pair must resolve to exactly one
        // survivor — the bug was retiring both, leaving no survivor. A tie favors the proposition
        // being classified, so 'a' (the outer-loop subject seen first) survives and 'b' is retired.
        val anchor = Instant.now()
        val a = proposition("a", "bob", 0.5, contentRevised = anchor)
        val b = proposition("b", "bob", 0.5, contentRevised = anchor)
        val reviser = mockk<PropositionReviser>()
        every { reviser.classify(a, listOf(b)) } returns listOf(
            ClassifiedProposition(b, PropositionRelation.CONTRADICTORY, 0.5),
        )
        every { reviser.classify(b, listOf(a)) } returns listOf(
            ClassifiedProposition(a, PropositionRelation.CONTRADICTORY, 0.5),
        )

        val result = ContradictionResolutionPass(reviser).run(contextId, listOf(a, b))

        val changed = assertInstanceOf(ConsolidationPassResult.Changed::class.java, result)
        assertEquals(1, changed.propositionsToSave.size)
        val transitioned = changed.propositionsToSave.single()
        assertEquals("b", transitioned.id)
        assertEquals(PropositionStatus.CONTRADICTED, transitioned.status)
    }

    @Test
    fun `a pinned proposition is never retired by contradiction resolution`() {
        // The pinned one has the LOWER confidence, so it would normally lose — but pinned
        // propositions are conflict-protected and must survive.
        val pinned = proposition("a", "bob", 0.2, pinned = true)
        val strong = proposition("b", "bob", 0.9)
        val reviser = mockk<PropositionReviser>()
        every { reviser.classify(pinned, listOf(strong)) } returns listOf(
            ClassifiedProposition(strong, PropositionRelation.CONTRADICTORY, 0.5),
        )
        every { reviser.classify(strong, listOf(pinned)) } returns listOf(
            ClassifiedProposition(pinned, PropositionRelation.CONTRADICTORY, 0.5),
        )

        val result = ContradictionResolutionPass(reviser).run(contextId, listOf(pinned, strong))

        // Nothing retired: the only loser would have been the pinned proposition.
        assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
    }

    @Test
    fun `a contradicted pinned proposition is surfaced for review, not silently dropped`() {
        // Same setup as above (the pinned one would lose), but now assert the contradiction is not
        // swallowed: a review signal must be emitted so the contested pin can be resolved or unpinned.
        val pinned = proposition("a", "bob", 0.2, pinned = true)
        val strong = proposition("b", "bob", 0.9)
        val reviser = mockk<PropositionReviser>()
        every { reviser.classify(pinned, listOf(strong)) } returns listOf(
            ClassifiedProposition(strong, PropositionRelation.CONTRADICTORY, 0.5),
        )
        every { reviser.classify(strong, listOf(pinned)) } returns listOf(
            ClassifiedProposition(pinned, PropositionRelation.CONTRADICTORY, 0.5),
        )
        val listener = RecordingDiceEventListener()

        ContradictionResolutionPass(reviser, listener).run(contextId, listOf(pinned, strong))

        val routed = listener.eventsOfType<PropositionRoutedToReview>()
        assertEquals(1, routed.size)
        assertEquals("a", routed.single().proposition.id)
    }

    @Test
    fun `no contradictions yields NoOp`() {
        val a = proposition("a", "bob", 0.9)
        val b = proposition("b", "bob", 0.4)
        val reviser = mockk<PropositionReviser>()
        every { reviser.classify(any(), any()) } returns listOf(
            ClassifiedProposition(b, PropositionRelation.SIMILAR, 0.5),
        )

        val result = ContradictionResolutionPass(reviser).run(contextId, listOf(a, b))
        assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
    }

    @Test
    fun `already CONTRADICTED props are not compared so a resolved snapshot re-runs as NoOp`() {
        val active = proposition("a", "bob", 0.9)
        val resolved = proposition("b", "bob", 0.3, status = PropositionStatus.CONTRADICTED)
        val reviser = mockk<PropositionReviser>()
        // active has no ACTIVE entity-overlapping peer left, so classify gets empty candidates
        every { reviser.classify(active, emptyList()) } returns emptyList()

        val result = ContradictionResolutionPass(reviser).run(contextId, listOf(active, resolved))

        assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
        // the already-resolved proposition is never classified
        verify(exactly = 0) { reviser.classify(resolved, any()) }
    }
}
