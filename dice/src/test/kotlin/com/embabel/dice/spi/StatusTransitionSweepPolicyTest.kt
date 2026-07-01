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
package com.embabel.dice.spi

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusTransitionSweepPolicyTest {

    private val contextId = ContextId("test-context")

    private fun proposition(pinned: Boolean = false): Proposition =
        Proposition(
            contextId = contextId,
            text = "p",
            mentions = emptyList(),
            confidence = 0.8,
            decay = 0.1,
            pinned = pinned,
        )

    private fun marks(p: Proposition) = listOf(PropositionMark(p.id, MarkReason.Stale, "decay"))

    @Test
    fun `SweepAction is a sealed family`() {
        val actions: List<SweepAction> = listOf(
            SweepAction.TransitionStatus(PropositionStatus.STALE),
            SweepAction.MergeInto("survivor-1"),
            SweepAction.HardDelete,
            SweepAction.Skip,
        )
        actions.forEach { action ->
            val described: String = when (action) {
                is SweepAction.TransitionStatus -> action.newStatus.name
                is SweepAction.MergeInto -> action.survivorId
                is SweepAction.HardDelete -> "delete"
                is SweepAction.Skip -> "skip"
            }
            assertEquals(true, described.isNotBlank())
        }
    }

    @Test
    fun `pinned proposition is skipped even when marked`() {
        val p = proposition(pinned = true)
        val action = StatusTransitionSweepPolicy().decide(p, marks(p))
        assertEquals(SweepAction.Skip, action)
    }

    @Test
    fun `empty marks is skipped`() {
        val p = proposition()
        val action = StatusTransitionSweepPolicy().decide(p, emptyList())
        assertEquals(SweepAction.Skip, action)
    }

    @Test
    fun `marked unpinned proposition transitions to STALE by default`() {
        val p = proposition()
        val action = StatusTransitionSweepPolicy().decide(p, marks(p))
        assertEquals(SweepAction.TransitionStatus(PropositionStatus.STALE), action)
    }

    @Test
    fun `custom target status is honored`() {
        val p = proposition()
        val action = StatusTransitionSweepPolicy(targetStatus = PropositionStatus.SUPERSEDED)
            .decide(p, marks(p))
        assertEquals(SweepAction.TransitionStatus(PropositionStatus.SUPERSEDED), action)
    }
}
