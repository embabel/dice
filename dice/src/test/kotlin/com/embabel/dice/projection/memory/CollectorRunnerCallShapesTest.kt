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
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pins the Kotlin call shapes of [CollectorRunner] that existing code relies on, so that adding
 * new overloads never changes which call resolves.
 *
 * Contract:
 * E1: run(contextId) is a real run, not a dry run.
 * E2: run(contextId, true) and run(contextId, dryRun = true) are both dry runs.
 * E3: collect(contextId) returns a result with a blank run id.
 * E4: a mocked run(any(), any()) and collect(any()) still resolve and return the stubbed result.
 */
class CollectorRunnerCallShapesTest {

    private val contextId = ContextId("acme")

    private val runner: CollectorRunner = CollectorRunner.withRepository(InMemoryPropositionRepository())
        .withStrategy(DecayCollectorStrategy(retireBelow = 0.3))
        .build()

    // E1: the one-argument run is a real run.
    @Test
    fun `run with only a context id is not a dry run`() {
        val result = runner.run(contextId)

        assertFalse(result.dryRun)
    }

    // E2: the positional dryRun flag is honoured.
    @Test
    fun `run with a positional dryRun true is a dry run`() {
        val result = runner.run(contextId, true)

        assertTrue(result.dryRun)
    }

    // E2: the named dryRun flag is honoured.
    @Test
    fun `run with a named dryRun true is a dry run`() {
        val result = runner.run(contextId, dryRun = true)

        assertTrue(result.dryRun)
    }

    // E3: collect gives a blank run id because nothing is persisted.
    @Test
    fun `collect returns a blank run id`() {
        val result = runner.collect(contextId)

        assertTrue(result.runId.isBlank())
    }

    // E4: a mocked two-argument run still resolves for a two-argument call.
    @Test
    fun `mocked run with any arguments returns the stubbed result`() {
        val stub = CollectorRunResult(
            runId = "run-1",
            dryRun = true,
            marks = emptyList(),
            applied = emptyList(),
            skipped = emptyList(),
            hardDeleted = emptyList(),
            startedAt = Instant.now(),
        )
        val mocked = mockk<CollectorRunner>()
        every { mocked.run(any(), any()) } returns stub

        val result = mocked.run(contextId, true)

        assertEquals("run-1", result.runId)
        assertTrue(result.dryRun)
    }

    // E4: a mocked collect still resolves for a one-argument call.
    @Test
    fun `mocked collect with any argument returns the stubbed result`() {
        val stub = CollectorRunResult(
            runId = "",
            dryRun = true,
            marks = emptyList(),
            applied = emptyList(),
            skipped = emptyList(),
            hardDeleted = emptyList(),
            startedAt = Instant.now(),
        )
        val mocked = mockk<CollectorRunner>()
        every { mocked.collect(any()) } returns stub

        val result = mocked.collect(contextId)

        assertEquals(stub, result)
    }
}
