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

/**
 * Sweep policy for the dedup path: collapse near-duplicates by *merging* the loser's evidence onto
 * its survivor rather than just flipping it STALE.
 *
 * A plain [StatusTransitionSweepPolicy] retires a duplicate with a pure status change, so the
 * loser's grounding and provenance vanish from retrieval (STALE is excluded). This policy instead
 * returns [SweepAction.MergeInto] pointing at the survivor named on the [MarkReason.Duplicate]
 * mark, and lets the runner fold the evidence across before retiring the loser.
 *
 * Decision order mirrors [StatusTransitionSweepPolicy]:
 * 1. Pinned propositions are always skipped.
 * 2. A proposition with no marks is skipped.
 * 3. A duplicate mark naming a usable survivor becomes [SweepAction.MergeInto].
 * 4. Anything else (marked, but no survivor to merge into) falls back to a plain transition to
 *    [targetStatus] — so a non-dedup mark still retires normally instead of being ignored.
 *
 * @property targetStatus The status a retired loser transitions to (defaults to
 *   [PropositionStatus.STALE]).
 */
class MergingSweepPolicy @JvmOverloads constructor(
    private val targetStatus: PropositionStatus = PropositionStatus.STALE,
) : SweepPolicy {

    private val logger = LoggerFactory.getLogger(MergingSweepPolicy::class.java)

    override fun decide(proposition: Proposition, marks: List<PropositionMark>): SweepAction {
        val action = when {
            proposition.pinned -> SweepAction.Skip
            marks.isEmpty() -> SweepAction.Skip
            else -> {
                // Take the survivor from the first duplicate mark that names a real, other
                // proposition. A blank id or one pointing back at this proposition is meaningless,
                // so ignore it and let the fallback retire the loser normally.
                val survivorId = marks.asSequence()
                    .map { it.reason }
                    .filterIsInstance<MarkReason.Duplicate>()
                    .map { it.survivorId }
                    .firstOrNull { it.isNotBlank() && it != proposition.id }
                if (survivorId != null) {
                    SweepAction.MergeInto(survivorId, targetStatus)
                } else {
                    logger.warn(
                        "No valid survivor found for {} (marks={}); falling back to status transition",
                        proposition.id.take(8), marks.size,
                    )
                    SweepAction.TransitionStatus(targetStatus)
                }
            }
        }
        logger.trace(
            "Merging sweep decision for {} (pinned={}, marks={}): {}",
            proposition.id.take(8), proposition.pinned, marks.size, action,
        )
        return action
    }
}
