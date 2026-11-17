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
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the Kotlin call shapes of [ConsolidationPass] that existing code relies on, so that
 * adding a String form beside one never makes a call ambiguous.
 *
 * Contract:
 * E1: a mocked run(any(), any()) resolves and returns the stubbed result.
 * E2: a direct run(ContextId, list) call returns the stubbed result from that mock.
 * E3: runValue(String, list) hands the string to the pass as a ContextId with the same value.
 */
class ConsolidationPassCallShapesTest {

    private val stub = ConsolidationPassResult.NoOp(passName = "tidy", reason = "stubbed")

    // E1: the untyped any() matchers resolve because there is only one run overload with two arguments.
    @Test
    fun `mocked run with any arguments resolves`() {
        val pass = mockk<ConsolidationPass>()
        every { pass.run(any(), any()) } returns stub

        val result = pass.run(ContextId("acme"), emptyList())

        assertEquals(stub, result)
    }

    // E2: a direct typed call on the mock still returns what the stub was given.
    @Test
    fun `direct run with a context id returns the stubbed result`() {
        val pass = mockk<ConsolidationPass>()
        every { pass.run(any(), any()) } returns stub

        val result = pass.run(ContextId("acme"), emptyList())

        assertEquals("tidy", result.passName)
    }

    // E3: the string form passes the id through as a ContextId with the same value.
    @Test
    fun `runValue hands the string id to run as a context id`() {
        var seen: String? = null
        val pass = ConsolidationPass.of("tidy") { ctx, _ ->
            seen = ctx
            stub
        }

        pass.runValue("acme", emptyList())

        assertEquals("acme", seen)
    }
}
