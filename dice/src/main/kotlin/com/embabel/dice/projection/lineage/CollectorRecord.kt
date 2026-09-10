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
package com.embabel.dice.projection.lineage

import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.MarkReason
import java.time.Instant

/**
 * A record of one proposition being acted upon by a collector during a run.
 *
 * Collectively these records form an audit trail — "which propositions were marked
 * or swept, why, and by which strategy" — so a reviewer can trace any retired or
 * removed proposition back to the run and reason that produced the outcome.
 *
 * The reason is a typed [MarkReason] rather than a free-form string, and the
 * action is a typed [CollectorOutcome] rather than a lifecycle/magic value, so the
 * collector audit trail stays semantically distinct from projection lineage.
 *
 * @property propositionId ID of the proposition that was acted upon
 * @property reason Typed explanation of why the proposition was marked
 * @property outcome What the collector did to the proposition
 * @property strategyName Name of the collector strategy that produced this record
 * @property runId ID of the collection run that produced this record
 * @property at When this record was created
 * @property previousStatus The proposition's status before the outcome, or null if not applicable
 * @property newStatus The proposition's status after the outcome, or null if not applicable
 * @property mergedIntoId The survivor this proposition's evidence was folded into, when the sweep
 *   actually performed a merge. Null everywhere else — a plain status transition, a skip, a hard
 *   delete, and the fallback retirement a runner does when a merge target has vanished or is no
 *   longer active all leave it null, which is what separates them from a merge that ran. On a
 *   preview record from a dry run it names the target the run would have merged into; the run
 *   header's `dryRun` flag is what says nothing happened.
 *
 *   This is the *applied* target, not the one a mark proposed. A proposition can be marked as a
 *   duplicate of several different survivors in one run while the sweep merges into exactly one of
 *   them, so a reader that needs to know what really happened has to read this rather than the
 *   reason. It is recorded identically on every record the run writes for this proposition, so a
 *   store that keeps one row per (proposition, run) reports the same answer as one that keeps them
 *   all.
 * @property undoneAt When this run's merge was reversed, or null while it still stands. Written by
 *   collapse undo once it has finished, so a record cannot authorize the same undo twice. Without
 *   it the audit trail is immortal and says "applied" forever, and a member re-retired later by
 *   anything at all — a decay sweep, a second collector run — would re-arm the original undo and
 *   let it subtract that run's evidence a second time. A store that keeps one row per (proposition,
 *   run) updates that row in place; one that appends leaves both, so a reader should treat *any*
 *   record for the pair carrying this as the whole collapse being undone.
 */
data class CollectorRecord @JvmOverloads constructor(
    val propositionId: String,
    val reason: MarkReason,
    val outcome: CollectorOutcome,
    val strategyName: String,
    val runId: String,
    val at: Instant = Instant.now(),
    val previousStatus: PropositionStatus? = null,
    val newStatus: PropositionStatus? = null,
    val mergedIntoId: String? = null,
    val undoneAt: Instant? = null,
) {

    init {
        require(propositionId.isNotBlank()) { "propositionId must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
    }

    companion object {

        /**
         * Java-friendly factory method to create a [CollectorRecord].
         *
         * @param propositionId ID of the proposition that was acted upon
         * @param reason Typed explanation of why the proposition was marked
         * @param outcome What the collector did to the proposition
         * @param strategyName Name of the collector strategy that produced this record
         * @param runId ID of the collection run
         * @param at When this record was created
         * @param previousStatus The proposition's status before the outcome
         * @param newStatus The proposition's status after the outcome
         * @param mergedIntoId The survivor the sweep merged this proposition into, if it did
         * @param undoneAt When that merge was reversed, or null while it stands
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            propositionId: String,
            reason: MarkReason,
            outcome: CollectorOutcome,
            strategyName: String,
            runId: String,
            at: Instant = Instant.now(),
            previousStatus: PropositionStatus? = null,
            newStatus: PropositionStatus? = null,
            mergedIntoId: String? = null,
            undoneAt: Instant? = null,
        ): CollectorRecord = CollectorRecord(
            propositionId = propositionId,
            reason = reason,
            outcome = outcome,
            strategyName = strategyName,
            runId = runId,
            at = at,
            previousStatus = previousStatus,
            newStatus = newStatus,
            mergedIntoId = mergedIntoId,
            undoneAt = undoneAt,
        )
    }
}
