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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.dice.spi.DecayStatusPolicy
import com.embabel.dice.spi.StatusTransitionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Behavioral surface tests for the [DecaySweeper] extension point: the config
 * defaults that downstream forgetting/consolidation work depends on, and the
 * sealed result type that callers must be able to exhaustively branch over.
 */
class DecaySweeperTypesTest {

    private val testContextId = ContextId("test-context")

    private fun prop(text: String = "fact") = Proposition(
        contextId = testContextId,
        text = text,
        mentions = emptyList(),
        confidence = 0.5,
    )

    @Nested
    inner class ConfigDefaults {

        @Test
        fun `default config uses the DecayStatusPolicy default`() {
            val config = DecaySweepConfig()
            assertTrue(config.policy is DecayStatusPolicy)
        }

        @Test
        fun `default target statuses scope to ACTIVE only and exclude PROMOTED`() {
            val config = DecaySweepConfig()
            assertEquals(setOf(PropositionStatus.ACTIVE), config.targetStatuses)
            assertFalse(config.targetStatuses.contains(PropositionStatus.PROMOTED))
        }

        @Test
        fun `default config is non-destructive (pruneStale is false)`() {
            assertFalse(DecaySweepConfig().pruneStale)
        }

        @Test
        fun `config preserves a custom policy and widened target statuses`() {
            val custom: StatusTransitionPolicy = StatusTransitionPolicy { null }
            val config = DecaySweepConfig(
                policy = custom,
                targetStatuses = setOf(PropositionStatus.ACTIVE, PropositionStatus.PROMOTED),
                pruneStale = true,
            )
            assertSame(custom, config.policy)
            assertEquals(setOf(PropositionStatus.ACTIVE, PropositionStatus.PROMOTED), config.targetStatuses)
            assertTrue(config.pruneStale)
        }
    }

    @Nested
    inner class SealedResult {

        @Test
        fun `Swept carries transitioned revived pruned and skipped counts`() {
            val transitioned = listOf(prop("stale-now"))
            val revived = listOf(prop("active-again"))
            val result: DecaySweepResult = DecaySweepResult.Swept(
                transitioned = transitioned,
                revived = revived,
                pruned = emptyList(),
                skipped = 3,
            )

            val summary = when (result) {
                is DecaySweepResult.Swept ->
                    "${result.transitioned.size}/${result.revived.size}/${result.pruned.size}/${result.skipped}"
                is DecaySweepResult.NoOp -> "noop"
                is DecaySweepResult.Failed -> "failed"
            }
            assertEquals("1/1/0/3", summary)
        }

        @Test
        fun `NoOp carries a reason and is distinct from Swept`() {
            val result: DecaySweepResult = DecaySweepResult.NoOp("nothing to sweep")
            assertTrue(result is DecaySweepResult.NoOp)
            assertEquals("nothing to sweep", (result as DecaySweepResult.NoOp).reason)
        }

        @Test
        fun `Failed carries the cause`() {
            val boom = IllegalStateException("repository unavailable")
            val result: DecaySweepResult = DecaySweepResult.Failed(boom)
            assertSame(boom, (result as DecaySweepResult.Failed).cause)
        }
    }
}
