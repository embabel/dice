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

import org.jetbrains.annotations.ApiStatus

import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DriftCheckResult
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.SweptBaselineStore
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus

/**
 * The store-side operations a host needs to act on a drift check: find the propositions a schema
 * change could have stranded, quarantine them, and let them back out again.
 *
 * ## Why this is a separate interface
 *
 * A `PropositionStore` is a general persistence port, and most of what it offers — read everything,
 * read one context, save — is enough to *look* like a sweep while being wrong at any real size.
 * Sweeping by reading every proposition materialises every tenant's data in one JVM heap and filters
 * mention types afterwards, which works on a laptop and falls over in production. So the sweep asks
 * for something narrower and states it as a requirement: a query bounded by a page size, confined to
 * one context, and filtered on mention type by the backend.
 *
 * A store implements this when its backend can honour that, exactly the way DICE's other opt-in
 * store capabilities work. A store that can't keeps the plain persistence contract and its host
 * sweeps through [PropositionStoreDriftSweep], the reference
 * implementation, which is honest about doing the filtering in the JVM.
 *
 * ## Nothing calls this on its own
 *
 * DICE runs drift *checks*. It never sweeps. [DriftCheckRunner] reports what it found and moves
 * nothing, and every method here runs because a host called it, at a moment a host chose. There is
 * no timer, no scheduler and no autoconfiguration that reaches these methods.
 *
 * The usual shape is: run a check, read its [DriftCheckResult.quarantineDiff], decide, then call
 * [sweep] once per context you meant to reconcile, then — and only after every context is done —
 * call [SweptBaselineStore.markSwept] so the next check compares against what you actually swept.
 *
 * ```kotlin
 * val result = runner.run()
 * if (result.hasAnyChange) {
 *     val swept = sweepStore.sweep(result.quarantineDiff, policy, contextId)
 *     log.info("quarantined {} proposition(s)", swept.quarantined.size)
 * }
 * ```
 *
 * ## Releasing is a real operation
 *
 * Quarantine is reversible, and [releaseFromQuarantine] is the only thing that reverses it.
 * [PropositionStatus.QUARANTINED] is a status of its own that every lifecycle policy in DICE leaves
 * alone, so a quarantined proposition stays put until a host says otherwise: no decay sweep revives
 * it, no consolidation pass moves it, and editing its metadata by hand changes nothing. Release
 * restores the status the proposition carried before quarantine and clears both quarantine keys in
 * one write.
 */
@ApiStatus.Experimental
interface DriftSweepCapable {

    /**
     * The propositions in [contextId] that could be affected by a schema change, restricted to those
     * mentioning at least one type in [mentionTypes].
     *
     * Three requirements, all of them load-bearing:
     *
     * - **Bounded.** Return at most [limit] propositions. A backend must push the bound into its
     *   query, so a sweep of a large context costs one page at a time.
     * - **Scoped.** Return only propositions whose `contextId` is [contextId]. A schema mis-declared
     *   in one context must never be able to reach another context's data, so this parameter is
     *   required and there is no whole-graph form.
     * - **Mention-type aware.** Return only propositions carrying at least one entity mention whose
     *   type is in [mentionTypes]. A backend must push this filter down too. Reading a context whole
     *   and filtering afterwards gives the same answer at a cost that grows with the size of the
     *   context, when what it should grow with is the size of the change.
     *
     * Order by [Proposition.id] ascending, and start after [afterId] when it is given. A stable
     * total order is what makes [afterId] a usable cursor: a sweep pages by passing back the last id
     * it saw, and quarantining a proposition changes its status without moving it in this order, so
     * no page is skipped and none repeats.
     *
     * An empty [mentionTypes] returns nothing. A change that could affect no mention type has no
     * candidates, and reading the context to discover that would be pure waste.
     *
     * Already-quarantined propositions are included when they match. Skipping them here would hide
     * them from [DriftQuarantinePolicy.evaluate], which reports them in their own bucket so a
     * sweep's conforming count stays honest.
     *
     * @param contextId The context to sweep. Required.
     * @param mentionTypes Entity type names a candidate must mention at least one of. Ordinarily
     *   [DriftQuarantinePolicy.candidateMentionTypes] of the diff being swept.
     * @param limit The most propositions to return. Must be positive.
     * @param afterId Return only propositions whose id sorts after this one. `null` starts at the
     *   beginning.
     * @return At most [limit] candidates, by ascending id. Empty when the page is past the end.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun quarantineCandidates(
        contextId: ContextId,
        mentionTypes: Set<String>,
        limit: Int,
        afterId: String?,
    ): List<Proposition>

    /**
     * The same read from the beginning.
     *
     * A real overload with a body, because Java can't see a Kotlin default argument.
     *
     * @param contextId The context to sweep.
     * @param mentionTypes Entity type names a candidate must mention at least one of.
     * @param limit The most propositions to return. Must be positive.
     * @return At most [limit] candidates, by ascending id.
     */
    fun quarantineCandidates(
        contextId: ContextId,
        mentionTypes: Set<String>,
        limit: Int,
    ): List<Proposition> = quarantineCandidates(contextId, mentionTypes, limit, null)

    /**
     * Persist one quarantine decision.
     *
     * [QuarantineDecision.Quarantined.proposition] is already the `QUARANTINED` copy carrying its
     * reason and the status it came from; a policy built it and wrote nothing. This is the write.
     *
     * An implementation announces the transition to whatever listener it was given as a
     * [com.embabel.dice.common.PropositionStatusChanged], so a consumer watching the proposition
     * lifecycle hears about a quarantine the way it hears about any other status move.
     *
     * @param decision What the policy decided.
     * @return The saved proposition.
     */
    fun applyQuarantine(decision: QuarantineDecision.Quarantined): Proposition

    /**
     * Let a quarantined proposition back out: restore the status it carried before quarantine and
     * clear its quarantine metadata, in one write.
     *
     * This is the whole reversibility story, and it is the only way out. A host that clears
     * [com.embabel.dice.common.DiceMetadataKeys.QUARANTINE_REASON] by hand leaves the proposition
     * `QUARANTINED` with nothing on it saying why, still held and now unexplained.
     *
     * The prior status comes from [DriftQuarantineKeys.PREVIOUS_STATUS], which the policy wrote at
     * quarantine time. A proposition carrying no readable value there is restored to
     * [PropositionStatus.ACTIVE], which is the only sensible reading of "let it back into use" when
     * the record of where it came from is gone. So is one whose recorded value reads `QUARANTINED`,
     * since restoring that would leave the release doing nothing.
     *
     * The status move is announced as a [com.embabel.dice.common.PropositionStatusChanged], the same
     * way [applyQuarantine] announces the move in.
     *
     * Which propositions are held is decided by status alone, so releasing twice is safe: the second
     * call finds a proposition that isn't quarantined and answers `null`.
     *
     * @param propositionId The proposition to release.
     * @return The released proposition, or `null` when no proposition has that id, or when the one
     *   that does is not quarantined.
     */
    fun releaseFromQuarantine(propositionId: String): Proposition?

    /**
     * Sweep one context against [diff]: page through the candidates, evaluate them, and persist
     * every quarantine [policy] decides on.
     *
     * The default body is the whole sweep, written once against the three operations above so every
     * implementation gets the same bounded, scoped behaviour. It reads a page of at most [batchSize]
     * candidates, hands the page to [policy], applies what came back, then asks for the next page
     * starting after the last id it saw, until a page comes back short.
     *
     * Only mention types [policy] says could matter are ever read
     * ([DriftQuarantinePolicy.candidateMentionTypes]), so a diff that touches one type reads one
     * type's propositions however large the context is. When the diff could affect nothing, no page
     * is read at all.
     *
     * Nothing here advances the swept baseline. A host sweeps every context it means to reconcile
     * and then calls [SweptBaselineStore.markSwept] itself, because only the host knows when it is
     * finished. See that method for what goes wrong if the baseline moves early.
     *
     * @param diff The comparison to evaluate against, ordinarily [DriftCheckResult.quarantineDiff].
     * @param policy Decides which propositions the change stranded.
     * @param contextId The context to sweep.
     * @param batchSize How many candidates to read per page. Must be positive.
     * @return Every decision the sweep made, gathered across pages in the order they were read.
     * @throws IllegalArgumentException if [batchSize] is not positive.
     */
    fun sweep(
        diff: MetamodelDiff,
        policy: DriftQuarantinePolicy,
        contextId: ContextId,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): QuarantineResult {
        require(batchSize > 0) { "batchSize must be positive, but was $batchSize" }
        val mentionTypes = policy.candidateMentionTypes(diff)

        val conforming = mutableListOf<QuarantineDecision.Conforming>()
        val quarantined = mutableListOf<QuarantineDecision.Quarantined>()
        val alreadyQuarantined = mutableListOf<QuarantineDecision.AlreadyQuarantined>()
        val protected = mutableListOf<QuarantineDecision.Protected>()

        var afterId: String? = null
        while (mentionTypes.isNotEmpty()) {
            val page = quarantineCandidates(contextId, mentionTypes, batchSize, afterId)
            if (page.isEmpty()) break

            val decided = policy.evaluate(diff, page)
            decided.quarantined.forEach { applyQuarantine(it) }

            conforming += decided.conforming
            quarantined += decided.quarantined
            alreadyQuarantined += decided.alreadyQuarantined
            protected += decided.protected

            if (page.size < batchSize) break
            afterId = page.last().id
        }

        return QuarantineResult(
            conforming = conforming,
            quarantined = quarantined,
            alreadyQuarantined = alreadyQuarantined,
            protected = protected,
        )
    }

    companion object {

        /**
         * Candidates read per page when a caller doesn't say. Big enough that an ordinary sweep is a
         * handful of round trips, small enough that one page fits comfortably in memory.
         */
        const val DEFAULT_BATCH_SIZE: Int = 500
    }
}
