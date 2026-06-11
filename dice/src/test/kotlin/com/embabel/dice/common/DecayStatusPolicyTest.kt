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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class DecayStatusPolicyTest {

    private val testContextId = ContextId("test-context")
    private val policy = DecayStatusPolicy()

    /**
     * Build a proposition with controlled signals so that
     * `effectiveConfidence(2.0) = confidence * exp(-decay * 2 * ageDays)`
     * lands deterministically relative to the 0.1 / 0.2 thresholds. With the default
     * weights of 0.0, utility reduces to effective confidence.
     */
    private fun proposition(
        confidence: Double,
        decay: Double = 0.0,
        ageDays: Long = 0,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        pinned: Boolean = false,
    ): Proposition = Proposition(
        contextId = testContextId,
        text = "test proposition",
        mentions = emptyList(),
        confidence = confidence,
        decay = decay,
        status = status,
        pinned = pinned,
        contentRevised = Instant.now().minus(Duration.ofDays(ageDays)),
    )

    @Nested
    inner class PinnedShortCircuit {

        @Test
        fun `pinned active proposition with low utility returns null`() {
            // utility would be effectively zero, but pinned is sweep-exempt
            val prop = proposition(confidence = 0.5, decay = 1.0, ageDays = 10, pinned = true)
            assertNull(policy.evaluate(prop))
        }

        @Test
        fun `pinned stale proposition with high utility returns null`() {
            val prop = proposition(
                confidence = 0.9,
                status = PropositionStatus.STALE,
                pinned = true,
            )
            assertNull(policy.evaluate(prop))
        }
    }

    @Nested
    inner class ActiveTransitions {

        @Test
        fun `active proposition below staleness threshold becomes stale`() {
            // 0.5 * exp(-1.0 * 2 * 10) is ~1e-9, far below 0.1
            val prop = proposition(confidence = 0.5, decay = 1.0, ageDays = 10)
            assertEquals(PropositionStatus.STALE, policy.evaluate(prop))
        }

        @Test
        fun `active proposition with high utility does not transition`() {
            // 0.9, no decay -> utility 0.9, above staleness threshold
            val prop = proposition(confidence = 0.9)
            assertNull(policy.evaluate(prop))
        }
    }

    @Nested
    inner class StaleTransitions {

        @Test
        fun `stale proposition above recovery threshold becomes active`() {
            // 0.9 utility > 0.2 recovery threshold
            val prop = proposition(confidence = 0.9, status = PropositionStatus.STALE)
            assertEquals(PropositionStatus.ACTIVE, policy.evaluate(prop))
        }

        @Test
        fun `stale proposition inside hysteresis band does not transition`() {
            // 0.15 sits between staleness (0.1) and recovery (0.2) -> no transition
            val prop = proposition(confidence = 0.15, status = PropositionStatus.STALE)
            assertNull(policy.evaluate(prop))
        }
    }
}
