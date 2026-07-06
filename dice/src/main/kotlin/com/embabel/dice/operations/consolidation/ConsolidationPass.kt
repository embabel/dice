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
import com.embabel.dice.proposition.Proposition

/**
 * One composable step in a consolidation cycle. A pass receives the current snapshot of
 * propositions for a context, decides what should change, and reports it back as a
 * [ConsolidationPassResult] — it never writes anything itself.
 *
 * ## Purity contract
 *
 * A pass works only with the snapshot it is handed and never touches a repository directly.
 * The orchestrator owns all writes: it fetches the snapshot once, runs each pass over it,
 * and applies the accumulated saves/deletes in a single write per cycle.
 *
 * Passes MUST be idempotent: running the same pass twice with no intervening write must produce
 * [ConsolidationPassResult.NoOp] on the second run. The orchestrator runs each pass once per cycle,
 * so successive scheduled cycles converge on a settled snapshot; a pass that keeps returning
 * [ConsolidationPassResult.Changed] for an already-consolidated snapshot would re-save the same
 * data every cycle instead of quieting down.
 *
 * Implement this interface to add domain-specific consolidation behavior without touching DICE core.
 */
interface ConsolidationPass {

    /** Short, stable identifier used in reports and logging (e.g. `"abstraction"`). */
    val name: String

    /**
     * Consolidate the snapshot [propositions] for [contextId], reporting the outcome.
     *
     * @param contextId The context the snapshot belongs to.
     * @param propositions The pre-fetched snapshot to consolidate; the pass must not assume any
     *   ordering and must not reach outside this list for state.
     * @return What changed (if anything), or why nothing did, as a [ConsolidationPassResult].
     */
    fun run(contextId: ContextId, propositions: List<Proposition>): ConsolidationPassResult
}

/**
 * What a [ConsolidationPass] decided to do (or not do). Sealed so the orchestrator can
 * exhaustively handle every case without a catch-all.
 *
 * Every case carries the [passName] that produced it, so reports can attribute outcomes back
 * to their source pass without threading the name through separately.
 *
 * @property passName The [ConsolidationPass.name] of the pass that produced this result.
 */
sealed class ConsolidationPassResult {

    abstract val passName: String

    /**
     * The pass found changes to apply.
     *
     * @property passName The name of the pass that produced this result.
     * @property propositionsToSave Propositions to upsert (new or updated copies).
     * @property propositionsToDelete IDs to hard-delete. Only acted on when the orchestrator runs
     *   with `allowHardDelete`; otherwise ignored in favor of the soft-retire default, so a pass
     *   can always express a delete intent without risking data loss.
     * @property skipped Count of snapshot propositions the pass deliberately left untouched.
     * @property externallyApplied Count of transitions the pass already wrote itself rather than
     *   handing back via [propositionsToSave]/[propositionsToDelete]. Used by report-only passes
     *   (e.g. the decay sweep, which writes through its own runner) so cycle totals still reflect
     *   the work; zero for passes that return everything to the orchestrator.
     * @property summary Short description of what changed, for reports and logging.
     */
    data class Changed @JvmOverloads constructor(
        override val passName: String,
        val propositionsToSave: List<Proposition> = emptyList(),
        val propositionsToDelete: List<String> = emptyList(),
        val skipped: Int = 0,
        val externallyApplied: Int = 0,
        val summary: String = "",
    ) : ConsolidationPassResult()

    /**
     * The pass had nothing to do — the snapshot is already consolidated as far as this pass is
     * concerned. Returning [NoOp] (not an empty [Changed]) is how a pass signals it has settled,
     * which lets the consolidation loop detect convergence.
     *
     * @property passName The name of the pass that produced this result.
     * @property reason Optional explanation of why nothing was done.
     */
    data class NoOp(
        override val passName: String,
        val reason: String = "",
    ) : ConsolidationPassResult()

    /**
     * The pass threw. The orchestrator records the failure and continues with the remaining
     * passes rather than aborting the whole cycle.
     *
     * @property passName The name of the pass that produced this result.
     * @property cause The exception that caused the failure.
     */
    data class Failed(
        override val passName: String,
        val cause: Throwable,
    ) : ConsolidationPassResult()
}
