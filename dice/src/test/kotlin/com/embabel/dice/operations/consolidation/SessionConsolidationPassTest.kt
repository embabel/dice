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
import com.embabel.dice.projection.memory.ConsolidationResult
import com.embabel.dice.projection.memory.MemoryConsolidator
import com.embabel.dice.projection.memory.PropositionMerge
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionConsolidationPassTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = 0.8,
            decay = 0.1,
        )

    @Test
    fun `name is session-consolidation`() {
        val pass = SessionConsolidationPass(mockk(relaxed = true), emptyList())
        assertEquals("session-consolidation", pass.name)
    }

    @Test
    fun `delegates session and snapshot to the consolidator`() {
        val session = listOf(proposition("session-1"))
        val snapshot = listOf(proposition("existing-1"))
        val consolidator = mockk<MemoryConsolidator>()
        val sessionSlot = slot<List<Proposition>>()
        val existingSlot = slot<List<Proposition>>()
        every {
            consolidator.consolidate(capture(sessionSlot), capture(existingSlot))
        } returns ConsolidationResult(emptyList(), emptyList(), emptyList(), emptyList())

        SessionConsolidationPass(consolidator, session).run(contextId, snapshot)

        verify(exactly = 1) { consolidator.consolidate(any(), any()) }
        assertSame(session, sessionSlot.captured)
        assertSame(snapshot, existingSlot.captured)
    }

    @Test
    fun `aggregates promoted reinforced and merged into propositionsToSave`() {
        val promoted = proposition("promoted")
        val reinforced = proposition("reinforced")
        val mergedResult = proposition("merged")
        val consolidator = mockk<MemoryConsolidator>()
        every { consolidator.consolidate(any(), any()) } returns ConsolidationResult(
            promoted = listOf(promoted),
            reinforced = listOf(reinforced),
            discarded = listOf(proposition("discarded")),
            merged = listOf(PropositionMerge(listOf(proposition("a"), proposition("b")), mergedResult)),
        )

        val result = SessionConsolidationPass(consolidator, listOf(proposition("s")))
            .run(contextId, emptyList())

        val changed = assertInstanceOf(ConsolidationPassResult.Changed::class.java, result)
        assertEquals("session-consolidation", changed.passName)
        assertEquals(listOf(promoted, reinforced, mergedResult), changed.propositionsToSave)
        assertTrue(changed.summary.isNotBlank())
    }

    @Test
    fun `returns NoOp when consolidation yields nothing to store`() {
        val consolidator = mockk<MemoryConsolidator>()
        every { consolidator.consolidate(any(), any()) } returns ConsolidationResult(
            promoted = emptyList(),
            reinforced = emptyList(),
            discarded = listOf(proposition("discarded")),
            merged = emptyList(),
        )

        val result = SessionConsolidationPass(consolidator, listOf(proposition("s")))
            .run(contextId, emptyList())

        val noOp = assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
        assertEquals("session-consolidation", noOp.passName)
    }

    @Test
    fun `wraps consolidator failure in a Failed result`() {
        val boom = RuntimeException("boom")
        val consolidator = mockk<MemoryConsolidator>()
        every { consolidator.consolidate(any(), any()) } throws boom

        val result = SessionConsolidationPass(consolidator, listOf(proposition("s")))
            .run(contextId, emptyList())

        val failed = assertInstanceOf(ConsolidationPassResult.Failed::class.java, result)
        assertSame(boom, failed.cause)
        assertEquals("session-consolidation", failed.passName)
    }
}
