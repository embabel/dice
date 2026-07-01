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

/**
 * Pure decision oracles for [MergingSweepPolicy].
 *
 * The policy translates a duplicate mark into a [SweepAction.MergeInto] aimed at the survivor,
 * while preserving the same pinned/no-marks guards as [StatusTransitionSweepPolicy] and falling
 * back to a plain STALE transition when there is no survivor to merge into.
 */
class MergingSweepPolicyTest {

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

    private fun duplicateMarks(p: Proposition, survivorId: String) =
        listOf(PropositionMark(p.id, MarkReason.Duplicate(survivorId = survivorId), "duplicate"))

    @Test
    fun `a duplicate mark becomes MergeInto aimed at the survivor`() {
        val p = proposition()
        val action = MergingSweepPolicy().decide(p, duplicateMarks(p, survivorId = "survivor-1"))
        assertEquals(SweepAction.MergeInto("survivor-1", PropositionStatus.STALE), action)
    }

    @Test
    fun `pinned proposition is skipped even when marked as a duplicate`() {
        val p = proposition(pinned = true)
        val action = MergingSweepPolicy().decide(p, duplicateMarks(p, survivorId = "survivor-1"))
        assertEquals(SweepAction.Skip, action)
    }

    @Test
    fun `empty marks is skipped`() {
        val p = proposition()
        val action = MergingSweepPolicy().decide(p, emptyList())
        assertEquals(SweepAction.Skip, action)
    }

    @Test
    fun `a non-duplicate mark falls back to a plain STALE transition`() {
        val p = proposition()
        val marks = listOf(PropositionMark(p.id, MarkReason.Stale, "decay"))
        val action = MergingSweepPolicy().decide(p, marks)
        assertEquals(SweepAction.TransitionStatus(PropositionStatus.STALE), action)
    }

    @Test
    fun `a duplicate mark with a blank survivor id falls back to a plain transition`() {
        val p = proposition()
        val action = MergingSweepPolicy().decide(p, duplicateMarks(p, survivorId = ""))
        assertEquals(SweepAction.TransitionStatus(PropositionStatus.STALE), action)
    }

    @Test
    fun `a duplicate mark pointing back at this proposition falls back to a plain transition`() {
        val p = proposition()
        val action = MergingSweepPolicy().decide(p, duplicateMarks(p, survivorId = p.id))
        assertEquals(SweepAction.TransitionStatus(PropositionStatus.STALE), action)
    }

    @Test
    fun `custom target status is honored on both the merge and the fallback`() {
        val p = proposition()
        val merge = MergingSweepPolicy(targetStatus = PropositionStatus.SUPERSEDED)
            .decide(p, duplicateMarks(p, survivorId = "survivor-1"))
        assertEquals(SweepAction.MergeInto("survivor-1", PropositionStatus.SUPERSEDED), merge)

        val fallback = MergingSweepPolicy(targetStatus = PropositionStatus.SUPERSEDED)
            .decide(p, listOf(PropositionMark(p.id, MarkReason.Stale, "decay")))
        assertEquals(SweepAction.TransitionStatus(PropositionStatus.SUPERSEDED), fallback)
    }
}
