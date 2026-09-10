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
package com.embabel.dice.proposition.extraction

import com.embabel.agent.core.ContextId
import org.jetbrains.annotations.ApiStatus

/**
 * Which extraction runs produced which claims.
 *
 * This is the relation that closes DICE #67's gap: a stored claim could not be traced to the run
 * that produced it. `ProvenanceEntry` says which source a claim was read from and
 * `CollectorTraceStore` says why a collapse happened; neither carries a run.
 *
 * ## Many-to-many, and why it has to be
 *
 * One run produces many propositions. One proposition is produced by many runs — that is not an
 * edge case, it is the normal outcome of re-extraction. Run a second extraction over the same
 * material and the deduplicating store answers with the proposition that already exists, so the
 * second run produced nothing new and still produced that claim. Both runs are true answers to
 * "what produced this?", and a relation that could hold only one would have to pick, silently.
 * That is why this is its own relation rather than a field on the proposition.
 *
 * ## Only canonical ids go in
 *
 * A link is written against the id the store holds, never the id extraction minted. Those differ
 * whenever a backend deduplicates, and a link against the minted id would point at nothing.
 * [com.embabel.dice.proposition.PropositionPersistenceResult] is what carries the canonical ids
 * back from a save, and it is what a caller links from.
 *
 * ## Run identity stays out of source provenance
 *
 * Nothing here touches `ProvenanceEntry` or `SourceLocator`. Two claims read from the same source
 * under two different runs have equal provenance and always will: source identity answers "where
 * did this come from", run identity answers "which execution wrote it down", and folding one into
 * the other would make evidence from two runs over one document look like evidence from two
 * documents. The lineage lives in this relation instead.
 *
 * ## Tenant-guarded, on both ends
 *
 * A link is between a proposition and a run *in one tenant*. [link] resolves both ends inside
 * `key.contextId` and rejects the write outright if either does not resolve there — a proposition
 * that lives in another tenant is not "missing", it is out of scope, and joining it to this
 * tenant's run would put a neighbour's claim into this tenant's audit. Nothing partial is written:
 * one out-of-scope id rejects the whole batch.
 *
 * Both reads fail closed the same way. A read in the wrong tenant returns nothing rather than
 * crossing.
 *
 * ## Bounded, and ordered by id
 *
 * Every read takes a positive limit. Both are ordered by id ascending, which makes a page
 * repeatable without joining anything: ordering runs newest-first would mean reading each run's
 * header for its start time, and a caller who wants that already has [ExtractionRunStore] and the
 * refs this returns.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
interface PropositionRunLinkStore {

    // ---- writes ----

    /**
     * Records that [key]'s run produced each of [propositionIds], in that run's tenant.
     *
     * Idempotent: the link is merged on the pair, so re-running an extraction that produced the
     * same claims writes nothing new and returns the same number. That is what makes the relation
     * safe to write from a path that can be retried.
     *
     * An empty batch is a no-op and touches nothing.
     *
     * @param key The run, tenant-qualified.
     * @param propositionIds Canonical ids of the propositions the run produced. Duplicates in the
     *   collection are one link.
     * @return How many links now join this run to these propositions. Equal on a replay.
     * @throws ExtractionRunNotFoundException if this tenant has no run under that id. A claim
     *   attributed to a run nobody recorded is a dangling audit row.
     * @throws PropositionRunLinkScopeException if any id names a proposition this tenant does not
     *   hold, whether it does not exist at all or belongs to a neighbour. Nothing is written.
     *
     * **The failures listed above must not damage the caller.** Lineage is written best-effort by a
     * caller that catches and carries on, so raising one of them has to leave everything else
     * exactly as it was — including a transaction the caller is running in. They are all raised
     * after the implementation's own reads and writes have succeeded, which is what makes that
     * possible; an implementation that joins a caller's transaction must not condemn it on the way
     * out. `DrivinePropositionRunLinkStore`'s integration tests hold the one backend that has a
     * transaction to condemn to this. The in-memory reference has none, so the shared contract suite
     * has nothing to assert here and does not pretend to.
     *
     * A failure *below* an implementation — a database that terminates the transaction itself — is
     * outside this and outside any catch. See `DrivinePropositionRunLinkStore.link`.
     */
    fun link(key: ExtractionRunKey, propositionIds: Collection<String>): Int

    /** [link] for a single proposition. */
    fun link(key: ExtractionRunKey, propositionId: String): Int =
        link(key, listOf(propositionId))

    // ---- reads ----

    /**
     * The runs that produced the proposition, in one tenant, by run id ascending.
     *
     * @param contextIdValue The tenant.
     * @param propositionId The canonical proposition id.
     * @param limit The most runs to return. Must be positive.
     * @return At most [limit] run references. Empty if the proposition is unknown to this tenant.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun runsOf(contextIdValue: String, propositionId: String, limit: Int): List<ExtractionRunRef>

    /**
     * [runsOf] for Kotlin callers holding a typed tenant.
     *
     * @param contextId The tenant.
     * @param propositionId The canonical proposition id.
     * @param limit The most runs to return. Must be positive.
     * @return At most [limit] run references.
     */
    fun runsOf(contextId: ContextId, propositionId: String, limit: Int): List<ExtractionRunRef> =
        runsOf(contextId.value, propositionId, limit)

    /**
     * The propositions a run produced — the inverse read — by proposition id ascending.
     *
     * Ids rather than propositions, so this store does not have to read the proposition store to
     * answer. The audit projection joins by canonical id anyway.
     *
     * @param key The run, tenant-qualified.
     * @param limit The most ids to return. Must be positive.
     * @return At most [limit] canonical proposition ids. Empty if this tenant has no such run.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun propositionsOf(key: ExtractionRunKey, limit: Int): List<String>
}

/** How many ids a scope rejection names before it stops counting them out. */
private const val MESSAGE_ID_LIMIT: Int = 5

/**
 * A link named a proposition the run's tenant does not hold.
 *
 * Either the proposition does not exist, or it exists in another tenant. Those are the same answer
 * from inside a tenant, and they mean the same thing: this run cannot claim to have produced it.
 *
 * The message names the run and up to five of the rejected ids. Proposition ids are DICE-minted
 * identifiers and carry no content, so naming them is what an operator needs; the count covers the
 * rest, so a large batch cannot produce a log line of unbounded length.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property key The run the link was against
 * @property propositionIds Every id that did not resolve in the run's tenant
 */
@ApiStatus.Experimental
class PropositionRunLinkScopeException(
    val key: ExtractionRunKey,
    val propositionIds: List<String>,
) : RuntimeException(
    "run ${key.runRef.runId} in context ${key.contextId.value}: " +
        "${propositionIds.size} proposition(s) are not in this context and cannot be linked — " +
        propositionIds.take(MESSAGE_ID_LIMIT).joinToString(separator = ", ") +
        if (propositionIds.size > MESSAGE_ID_LIMIT) ", …" else "",
)
