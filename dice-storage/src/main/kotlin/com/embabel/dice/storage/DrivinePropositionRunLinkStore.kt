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
package com.embabel.dice.storage

import com.embabel.dice.proposition.extraction.ExtractionRunKey
import com.embabel.dice.proposition.extraction.ExtractionRunNotFoundException
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.PropositionRunLinkScopeException
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import org.drivine.manager.PersistenceManager
import org.jetbrains.annotations.ApiStatus
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional

/**
 * Drivine / Neo4j implementation of [PropositionRunLinkStore].
 *
 * ## The graph
 *
 * `(:Proposition {id, contextId})-[:PRODUCED_BY_RUN]->(:ExtractionRun {contextId, runId})`
 *
 * One edge type, no properties on it. A bare edge says one thing — this run produced this claim —
 * and there is nothing on it for a replay to disagree about, which is what lets the write be a plain
 * `MERGE`. A timestamp would have to be `ON CREATE SET` to stay idempotent, and it would duplicate
 * what the run header's `startedAt` already records.
 *
 * The relation is many-to-many in both directions and the graph is the right shape for it. One run
 * points at every claim it produced; one claim points at every run that produced it, which is the
 * normal outcome of re-extraction rather than an edge case.
 *
 * ## The tenant guard is a MATCH, and then it is a check
 *
 * Every statement here names `contextId` on both endpoints, so an edge between two tenants cannot
 * be matched or created by anything in this class. That makes the reads fail closed for free: a
 * neighbour's run is not in this tenant's pattern.
 *
 * A write needs more than "matched nothing", because "nothing matched" and "you asked to link a
 * neighbour's proposition" are the same silence. So [link] resolves the run and then the
 * propositions before it writes, and names what did not resolve. Both checks and the write run in
 * one transaction, and the exception rolls it back, so a batch with one out-of-scope id leaves the
 * graph exactly as it found it.
 *
 * ## No new constraint, and why that is not an oversight
 *
 * Neither endpoint label is new. `Proposition(id)` and `ExtractionRun(contextId, runId)` already
 * carry uniqueness constraints, and both are what these statements seek on: the write anchors on
 * the run, the proposition lookup anchors on the id, and the inverse read anchors on the run and
 * expands backwards. A relationship has no key of its own to constrain — `MERGE` on a pattern
 * between two matched nodes creates at most one edge — so there is nothing here for a constraint to
 * make race-free that the endpoint constraints do not already.
 *
 * See [ExtractionRunSchema] for the relationship type name and the constraints the run end depends
 * on.
 *
 * Every statement is parameterized; nothing caller-derived is interpolated into Cypher.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land. The marker is on
 * this class rather than inherited from [PropositionRunLinkStore]: annotations on an interface do
 * not carry to its implementations, so a host looking at the concrete bean it declares would see
 * nothing.
 *
 * @param persistenceManager Drivine's handle on the `neo` datasource.
 */
@ApiStatus.Experimental
@Transactional
open class DrivinePropositionRunLinkStore(
    private val persistenceManager: PersistenceManager,
) : PropositionRunLinkStore {

    private val logger = LoggerFactory.getLogger(DrivinePropositionRunLinkStore::class.java)

    /**
     * Records the links, joining a caller's transaction when there is one.
     *
     * **Propagation is `REQUIRED`, and that is a decision rather than a default.** The alternative,
     * `REQUIRES_NEW`, would guarantee that a failure here can never affect a caller's transaction —
     * but it would also suspend that transaction, and a new one cannot see rows the suspended one
     * has not committed. A host that wraps extraction in `@Transactional` saves its propositions in
     * that transaction, so under `REQUIRES_NEW` every one of them would be invisible to the scope
     * check and lineage would fail closed on every such extraction. Joining the caller means
     * lineage resolves the claims it is about, and the two commit or roll back together.
     *
     * **What joining costs, stated exactly.** Spring marks a participating transaction rollback-only
     * when an inner method throws, and it does so here: Drivine's manager overrides
     * `doSetRollbackOnly` and the shared transaction object's `rollbackOnly` flag is set. The
     * caller's commit survives anyway because that flag is write-only — `DrivineTransactionObject`
     * does not implement `SmartTransactionObject`, so Spring's `isGlobalRollbackOnly` cannot see it,
     * and Drivine never reads it in `doCommit`. The marking is propagated and then dropped. That is
     * behaviour, not contract, so it is pinned rather than assumed: `lineage inside a caller's
     * transaction sees its writes and cannot condemn it` goes red if Drivine implements
     * `SmartTransactionObject` or starts reading the flag.
     *
     * **The guarantee covers application-level failures only.** Everything this method raises by
     * itself — tenant guard, run-not-found, scope rejection, batch-changed — is thrown from Kotlin
     * after its statements succeeded, so the Bolt transaction is healthy and a caller that catches
     * really can carry on. A statement that fails *at the server* is not covered: it terminates the
     * transaction beneath Spring, and no catch can undo that. A deadlock between two runs linking
     * overlapping propositions is the realistic version: nothing orders the node locks two
     * concurrent `MERGE` batches take over the same propositions. `a server-side failure
     * inside a caller's transaction is the window best-effort does not cover` demonstrates it and
     * measures the cost: the caller's later writes are lost with it.
     *
     * That window closes when the run coordinator commits claims before recording lineage (DICE #67
     * slice 10), which is what makes lineage a genuinely separate write rather than one that shares
     * a caller's fate. Until then it is a real limitation of running extraction inside an ambient
     * transaction, and hosts that do not wrap extraction are unaffected.
     */
    @Transactional
    override fun link(key: ExtractionRunKey, propositionIds: Collection<String>): Int {
        val ids = propositionIds.distinct()
        if (ids.isEmpty()) return 0
        ExtractionRunSchema.requireStorableTenant(key.contextId)

        if (!runExists(key)) throw ExtractionRunNotFoundException(key)

        val inScope = propositionsInContext(key.contextId.value, ids)
        val outOfScope = ids.filterNot { it in inScope }
        if (outOfScope.isNotEmpty()) {
            throw PropositionRunLinkScopeException(key, outOfScope)
        }

        val row = singleRow(
            MERGE_LINKS,
            mapOf(
                "contextId" to key.contextId.value,
                "runId" to key.runRef.runId,
                "propositionIds" to ids,
                "expected" to ids.size,
            ),
        )
        val linked = (row?.get("linked") as? Number)?.toInt()
        if (linked != ids.size) {
            // The batch changed between the preflight above and this write. Read-committed means
            // the two statements see two snapshots, so a proposition deleted or re-tenanted in
            // between passes the check and is gone by the time the MERGE runs. The statement itself
            // refuses to write a partial batch, so nothing has been written here — but this method
            // has already promised all-or-nothing, so it has to say so rather than return a count
            // that would read as success. The throw also rolls the transaction back, which covers
            // the case where the statement's own guard is not the thing that stopped it.
            throw batchChangedUnderUs(key, ids)
        }
        logger.debug(
            "Linked {} propositions to run {} in context {}",
            linked, key.runRef.runId, key.contextId.value,
        )
        return linked
    }

    /**
     * Names what went missing during the write, by re-reading in the transaction that is about to
     * roll back.
     *
     * The second read sees the state the write saw, so it can say which ids no longer resolve —
     * which is what an operator needs and what the preflight's message would have said if the batch
     * had been broken when it ran.
     */
    private fun batchChangedUnderUs(
        key: ExtractionRunKey,
        ids: List<String>,
    ): RuntimeException {
        if (!runExists(key)) return ExtractionRunNotFoundException(key)
        val stillInScope = propositionsInContext(key.contextId.value, ids)
        val vanished = ids.filterNot { it in stillInScope }
        logger.warn(
            "Linking {} propositions to run {} in context {} wrote nothing: {} still resolve",
            ids.size, key.runRef.runId, key.contextId.value, stillInScope.size,
        )
        return PropositionRunLinkScopeException(key, vanished.ifEmpty { ids })
    }

    @Transactional(readOnly = true)
    override fun runsOf(
        contextIdValue: String,
        propositionId: String,
        limit: Int,
    ): List<ExtractionRunRef> {
        requirePositiveLimit(limit)
        return queryRows(
            RUNS_OF_PROPOSITION,
            mapOf(
                "contextId" to contextIdValue,
                "propositionId" to propositionId,
                "limit" to limit,
            ),
        ).mapNotNull { row -> row["runId"]?.toString()?.let(::ExtractionRunRef) }
    }

    @Transactional(readOnly = true)
    override fun propositionsOf(key: ExtractionRunKey, limit: Int): List<String> {
        requirePositiveLimit(limit)
        return queryRows(
            PROPOSITIONS_OF_RUN,
            mapOf(
                "contextId" to key.contextId.value,
                "runId" to key.runRef.runId,
                "limit" to limit,
            ),
        ).mapNotNull { row -> row["propositionId"]?.toString() }
    }

    // ---- plumbing ----

    private fun runExists(key: ExtractionRunKey): Boolean =
        singleRow(
            RUN_EXISTS,
            mapOf("contextId" to key.contextId.value, "runId" to key.runRef.runId),
        ) != null

    /**
     * Which of [ids] this tenant actually holds. One round trip, whatever the batch size.
     *
     * This is the preflight, and its job is to *name* what is out of scope, not to decide whether
     * the write may proceed — [MERGE_LINKS] decides that, in the same snapshot it writes in.
     *
     * `protected open` so a test can stand in the window between this check and the write, which is
     * the only way to exercise the guard that closes it. Overriding it in production would weaken
     * the error messages and nothing else.
     */
    protected open fun propositionsInContext(contextIdValue: String, ids: List<String>): Set<String> =
        queryRows(
            PROPOSITIONS_IN_CONTEXT,
            mapOf("contextId" to contextIdValue, "propositionIds" to ids),
        ).mapNotNull { row -> row["propositionId"]?.toString() }.toSet()

    private fun queryRows(statement: String, bindings: Map<String, Any?>): List<Map<*, *>> {
        @Suppress("UNCHECKED_CAST")
        val spec = QuerySpecification.withStatement(statement).bind(bindings) as QuerySpecification<Any>
        return persistenceManager.query(spec).filterIsInstance<Map<*, *>>()
    }

    private fun singleRow(statement: String, bindings: Map<String, Any?>): Map<*, *>? =
        queryRows(statement, bindings).firstOrNull()

    private fun requirePositiveLimit(limit: Int) {
        require(limit > 0) { "limit must be positive, was $limit" }
    }

    private companion object {

        /** Does this tenant hold this run? A seek on the run's uniqueness constraint. */
        private val RUN_EXISTS = """
            MATCH (n:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
            RETURN {runId: n.runId} AS row
        """.trimIndent()

        /**
         * Which of the given proposition ids this tenant holds.
         *
         * The id seek uses the `Proposition(id)` uniqueness constraint and the tenant is checked on
         * what comes back, rather than being part of the seek. Proposition ids are minted globally
         * unique, so an id that resolves to another tenant's proposition resolves to exactly one
         * node, and it is that node's tenant that decides.
         */
        private val PROPOSITIONS_IN_CONTEXT = """
            MATCH (p:Proposition)
            WHERE p.id IN ${'$'}propositionIds AND p.contextId = ${'$'}contextId
            RETURN {propositionId: p.id} AS row
        """.trimIndent()

        /**
         * The write, and the authority on whether the batch is still writable.
         *
         * Both endpoints carry the tenant, so a cross-tenant edge is not expressible here — the
         * caller has already rejected one by name, and this is the second answer to the same
         * question. `MERGE` on the pattern between two already-matched nodes creates at most one
         * edge, so a replay writes nothing new and counts the same.
         *
         * **`WHERE size(ps) = $expected` is what makes the batch atomic**, and it is not the same
         * check as the caller's preflight even though it reads the same predicate. The preflight
         * runs in its own statement, so under read-committed it sees an earlier snapshot: a
         * proposition deleted or re-tenanted between the two passes the preflight and is gone by
         * now. Counting the matches *inside the statement that writes them* closes that window —
         * the count and the `MERGE` see one snapshot, so either every proposition is here and all
         * the edges are written, or the row is filtered out and none of them are. The preflight
         * stays, because it is what can name the ids; this is what decides.
         */
        private val MERGE_LINKS = """
            MATCH (n:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
            WITH n
            MATCH (p:Proposition)
            WHERE p.id IN ${'$'}propositionIds AND p.contextId = ${'$'}contextId
            WITH n, collect(p) AS ps
            WHERE size(ps) = ${'$'}expected
            UNWIND ps AS p
            MERGE (p)-[r:PRODUCED_BY_RUN]->(n)
            WITH count(r) AS linked
            RETURN {linked: linked} AS row
        """.trimIndent()

        /**
         * Which runs produced this claim, in one tenant.
         *
         * Anchored on the proposition's id constraint, with the tenant asserted on both ends so a
         * read can never cross. `DISTINCT` before the limit, because a page that spent a slot on a
         * repeated run id would come back short.
         */
        private val RUNS_OF_PROPOSITION = """
            MATCH (p:Proposition {id: ${'$'}propositionId})-[:PRODUCED_BY_RUN]->(n:ExtractionRun)
            WHERE p.contextId = ${'$'}contextId AND n.contextId = ${'$'}contextId
            WITH DISTINCT n.runId AS runId ORDER BY runId ASC
            LIMIT ${'$'}limit
            RETURN {runId: runId} AS row
        """.trimIndent()

        /**
         * The inverse: which claims this run produced.
         *
         * Anchored on the run so the expansion starts from one node rather than from a tenant's
         * worth of propositions, and scoped before the limit — the tenant is in the pattern, not a
         * filter applied to a page that was already cut.
         */
        private val PROPOSITIONS_OF_RUN = """
            MATCH (n:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})<-[:PRODUCED_BY_RUN]-(p:Proposition)
            WHERE p.contextId = ${'$'}contextId
            WITH DISTINCT p.id AS propositionId ORDER BY propositionId ASC
            LIMIT ${'$'}limit
            RETURN {propositionId: propositionId} AS row
        """.trimIndent()
    }
}
