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

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory

/**
 * The "sweep" half of the mark-and-sweep collector: given a proposition and its marks, decides
 * what should actually happen to it as a [SweepAction].
 *
 * Implementations decide per-proposition and should be stateless. Applying the decision
 * (transitioning status, deleting, or skipping) is the runner's job, not the policy's.
 *
 * Implement this to encode domain-specific sweep behavior without touching DICE core.
 */
fun interface SweepPolicy {

    /**
     * Decide what to do with [proposition] given its [marks].
     *
     * @param proposition The marked proposition under consideration.
     * @param marks The marks produced for this proposition; may be empty.
     * @return The action the sweep should take.
     */
    fun decide(proposition: Proposition, marks: List<PropositionMark>): SweepAction
}

/**
 * Default [SweepPolicy]: non-destructive and safe for pinned propositions.
 *
 * Decision order:
 * 1. Pinned propositions are always skipped, no matter what marks they carry.
 * 2. A proposition with no marks is skipped.
 * 3. Everything else transitions to [targetStatus].
 *
 * Never returns [SweepAction.HardDelete] — the default outcome is STALE, which is reversible.
 *
 * @property targetStatus The status a marked, unpinned proposition transitions to.
 *   Defaults to [PropositionStatus.STALE].
 */
class StatusTransitionSweepPolicy @JvmOverloads constructor(
    private val targetStatus: PropositionStatus = PropositionStatus.STALE,
) : SweepPolicy {

    private val logger = LoggerFactory.getLogger(StatusTransitionSweepPolicy::class.java)

    override fun decide(proposition: Proposition, marks: List<PropositionMark>): SweepAction {
        val action = when {
            proposition.pinned -> SweepAction.Skip
            marks.isEmpty() -> SweepAction.Skip
            else -> SweepAction.TransitionStatus(targetStatus)
        }
        logger.trace(
            "Sweep decision for {} (pinned={}, marks={}): {}",
            proposition.id.take(8), proposition.pinned, marks.size, action,
        )
        return action
    }
}
