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
import com.embabel.dice.operations.PropositionGroup
import com.embabel.dice.operations.abstraction.PropositionAbstractor
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AbstractionPassTest {

    private val contextId = ContextId("test-context")

    private fun proposition(
        id: String,
        entityId: String,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        level: Int = 0,
        sourceIds: List<String> = emptyList(),
        pinned: Boolean = false,
    ): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = "prop-$id",
            mentions = listOf(EntityMention(span = "e", type = "Person", resolvedId = entityId)),
            confidence = 0.8,
            decay = 0.1,
            status = status,
            level = level,
            sourceIds = sourceIds,
            pinned = pinned,
        )

    private fun group(entityId: String, count: Int): List<Proposition> =
        (1..count).map { proposition("$entityId-$it", entityId) }

    @Test
    fun `name is abstraction`() {
        assertEquals("abstraction", AbstractionPass(mockk(relaxed = true)).name)
    }

    @Test
    fun `below threshold yields NoOp and never calls the abstractor`() {
        val abstractor = mockk<PropositionAbstractor>()
        val result = AbstractionPass(abstractor, abstractionThreshold = 5)
            .run(contextId, group("bob", 4))

        assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
        verify(exactly = 0) { abstractor.abstract(any<PropositionGroup>(), any()) }
    }

    @Test
    fun `qualifying group is abstracted and sources are SUPERSEDED via withStatus`() {
        val members = group("bob", 5)
        val abstraction = proposition("abs-1", "bob", level = 1, sourceIds = members.map { it.id })
        val abstractor = mockk<PropositionAbstractor>()
        val groupSlot = slot<PropositionGroup>()
        val targetSlot = slot<Int>()
        every {
            abstractor.abstract(capture(groupSlot), capture(targetSlot))
        } returns listOf(abstraction)

        val before = Instant.now()
        val result = AbstractionPass(abstractor, abstractionThreshold = 5, abstractionTargetCount = 3)
            .run(contextId, members)

        val changed = assertInstanceOf(ConsolidationPassResult.Changed::class.java, result)
        assertEquals(3, targetSlot.captured)
        assertEquals(5, groupSlot.captured.size)
        // abstraction + 5 superseded sources
        assertEquals(6, changed.propositionsToSave.size)
        assertTrue(changed.propositionsToSave.contains(abstraction))
        val supersededSources = changed.propositionsToSave.filter { it.status == PropositionStatus.SUPERSEDED }
        assertEquals(5, supersededSources.size)
        // withStatus preserves the contentRevised decay anchor while bumping metadataRevised
        supersededSources.forEach { sup ->
            val original = members.first { it.id == sup.id }
            assertEquals(original.contentRevised, sup.contentRevised)
            assertTrue(!sup.metadataRevised.isBefore(before))
        }
    }

    @Test
    fun `a pinned source is kept ACTIVE, not superseded, when its group is abstracted`() {
        val members = group("bob", 4) + proposition("pinned-1", "bob", pinned = true)
        val abstraction = proposition("abs-1", "bob", level = 1, sourceIds = members.map { it.id })
        val abstractor = mockk<PropositionAbstractor>()
        every { abstractor.abstract(any<PropositionGroup>(), any()) } returns listOf(abstraction)

        val changed = assertInstanceOf(
            ConsolidationPassResult.Changed::class.java,
            AbstractionPass(abstractor, abstractionThreshold = 5).run(contextId, members),
        )

        // The abstraction plus the 4 unpinned sources superseded; the pinned source is eviction-
        // immune, so it is neither superseded nor otherwise touched (stays ACTIVE, absent from saves).
        assertTrue(changed.propositionsToSave.contains(abstraction))
        assertEquals(4, changed.propositionsToSave.count { it.status == PropositionStatus.SUPERSEDED })
        assertTrue(changed.propositionsToSave.none { it.id == "pinned-1" })
    }

    @Test
    fun `level-inflation guard skips a group already covered by an existing higher-level proposition`() {
        val members = group("bob", 5)
        val existingAbstraction = proposition(
            "abs-1", "bob", level = 1, sourceIds = members.map { it.id },
        )
        val abstractor = mockk<PropositionAbstractor>()

        val result = AbstractionPass(abstractor, abstractionThreshold = 5)
            .run(contextId, members + existingAbstraction)

        // fully covered -> NoOp, abstractor never invoked
        assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
        verify(exactly = 0) { abstractor.abstract(any<PropositionGroup>(), any()) }
    }

    @Test
    fun `re-running over the pass's own previous output yields NoOp (round-trip idempotency)`() {
        // The SPI mandates: running the same pass twice with no intervening write must yield NoOp on
        // the second run. The existing guard test hand-crafts a covering abstraction; this proves the
        // ACTUAL abstractor output trips the guard, so a real cycle converges instead of inflating.
        val members = group("bob", 5)
        val produced = proposition("abs-1", "bob", level = 1, sourceIds = members.map { it.id })
        val abstractor = mockk<PropositionAbstractor>()
        every { abstractor.abstract(any<PropositionGroup>(), any()) } returns listOf(produced)

        val pass = AbstractionPass(abstractor, abstractionThreshold = 5)

        // First run produces an abstraction and supersedes the 5 sources.
        val first = assertInstanceOf(ConsolidationPassResult.Changed::class.java, pass.run(contextId, members))
        val abstraction = first.propositionsToSave.single { it.level > 0 }
        val supersededSources = first.propositionsToSave.filter { it.status == PropositionStatus.SUPERSEDED }

        // Feed the pass's OWN output back in (sources are now SUPERSEDED, plus the new abstraction).
        // A converged snapshot must re-run as NoOp and must NOT call the abstractor a second time.
        val secondInput = supersededSources + abstraction
        val second = pass.run(contextId, secondInput)

        assertInstanceOf(ConsolidationPassResult.NoOp::class.java, second)
        verify(exactly = 1) { abstractor.abstract(any<PropositionGroup>(), any()) }
    }

    @Test
    fun `when every abstraction exceeds maxLevel the sources are kept, not superseded`() {
        val members = group("bob", 5)
        val tooHigh = proposition("abs-high", "bob", level = 4, sourceIds = members.map { it.id })
        val abstractor = mockk<PropositionAbstractor>()
        every { abstractor.abstract(any<PropositionGroup>(), any()) } returns listOf(tooHigh)

        val result = AbstractionPass(abstractor, abstractionThreshold = 5, maxLevel = 3)
            .run(contextId, members)

        // The over-cap abstraction is dropped, and crucially the sources are NOT retired to
        // SUPERSEDED — there would be no surviving abstraction to replace them, so retiring them
        // would silently lose the facts. The group is skipped and the pass is a NoOp.
        assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
    }

    @Test
    fun `a surviving abstraction still supersedes the sources even when an over-cap sibling is dropped`() {
        val members = group("bob", 5)
        val ok = proposition("abs-ok", "bob", level = 2, sourceIds = members.map { it.id })
        val tooHigh = proposition("abs-high", "bob", level = 4, sourceIds = members.map { it.id })
        val abstractor = mockk<PropositionAbstractor>()
        every { abstractor.abstract(any<PropositionGroup>(), any()) } returns listOf(ok, tooHigh)

        val result = AbstractionPass(abstractor, abstractionThreshold = 5, maxLevel = 3)
            .run(contextId, members)

        val changed = assertInstanceOf(ConsolidationPassResult.Changed::class.java, result)
        // Over-cap sibling dropped, the within-cap abstraction kept, and the sources superseded
        // because there IS now a replacement.
        assertTrue(changed.propositionsToSave.none { it.level > 3 })
        assertTrue(changed.propositionsToSave.contains(ok))
        assertEquals(5, changed.propositionsToSave.count { it.status == PropositionStatus.SUPERSEDED })
    }
}
