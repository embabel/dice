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
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Checks that Kotlin callers can still stub and call the dream loop and maintenance
 * interfaces with untyped matchers such as `any()`. A String overload next to the
 * ContextId one would make those calls ambiguous, so this file fails to compile if one comes back.
 *
 * Contract:
 * E1: consolidate(ContextId) returns the stubbed report, or null when the stub says so.
 * E2: consolidateNow(ContextId) returns the stubbed report.
 * E3: maintain(ContextId) and maintain(ContextId, propositions) return the stubbed result.
 */
class DreamLoopCallShapesTest {

    private val contextId = ContextId("acme")

    private val report = DreamLoopReport(
        contextId = contextId,
        cycleStarted = Instant.EPOCH,
        passResults = emptyList(),
        totalExamined = 3,
        totalTransitioned = 1,
        totalNewPropositions = 0,
        triggered = true,
    )

    private val maintenanceResult = MaintenanceResult(
        consolidation = null,
        abstractions = emptyList(),
        superseded = emptyList(),
        retired = emptyList(),
    )

    // E1: the untyped matcher stub on consolidate() is used by a typed call.
    @Test
    fun `consolidate with any matcher returns stubbed report`() {
        val loop = mockk<DreamLoopOrchestrator>()
        every { loop.consolidate(any()) } returns report

        val result = loop.consolidate(contextId)

        assertSame(report, result)
        verify(exactly = 1) { loop.consolidate(contextId) }
    }

    // E1: a stub that returns null is passed through as null.
    @Test
    fun `consolidate with any matcher can return null`() {
        val loop = mockk<DreamLoopOrchestrator>()
        every { loop.consolidate(any()) } returns null

        assertNull(loop.consolidate(ContextId("other")))
    }

    // E2: the untyped matcher stub on consolidateNow() is used by a typed call.
    @Test
    fun `consolidateNow with any matcher returns stubbed report`() {
        val loop = mockk<DreamLoopOrchestrator>()
        every { loop.consolidateNow(any()) } returns report

        val result = loop.consolidateNow(contextId)

        assertSame(report, result)
        verify(exactly = 1) { loop.consolidateNow(contextId) }
    }

    // E3: the single-argument maintain() stub is used by a typed call.
    @Test
    fun `maintain with any matcher returns stubbed result`() {
        val maintenance = mockk<MemoryMaintenanceOrchestrator>()
        every { maintenance.maintain(any()) } returns maintenanceResult

        val result = maintenance.maintain(contextId)

        assertSame(maintenanceResult, result)
        verify(exactly = 1) { maintenance.maintain(contextId) }
    }

    // E3: the two-argument maintain() stub is used by a typed call, and the propositions reach it.
    @Test
    fun `maintain with two any matchers returns stubbed result`() {
        val maintenance = mockk<MemoryMaintenanceOrchestrator>()
        val sessionPropositions = emptyList<Proposition>()
        every { maintenance.maintain(any(), any()) } returns maintenanceResult

        val result = maintenance.maintain(contextId, sessionPropositions)

        assertEquals(maintenanceResult, result)
        verify(exactly = 1) { maintenance.maintain(contextId, sessionPropositions) }
    }
}
