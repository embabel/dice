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

import com.embabel.agent.core.ContextId
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorComponent
import com.embabel.dice.spi.CollectorDecision
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.spi.CollectorTraceStore
import com.embabel.dice.spi.RetiredProposition
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Drivine / Neo4j [CollectorTraceStore]: persists the multi-signal collector's inspectable
 * decision trace as plain property-bag nodes, so a graph deployment keeps it durable and
 * queryable instead of losing it to a process restart (the in-memory store's tradeoff).
 *
 * Graph model — every trace node carries both `runId` and `contextId` so
 * [deleteTracesForContext] can erase a context's rows in one pass per label, and every write
 * MERGEs on a natural key so a replayed run updates in place instead of duplicating:
 * - `(:CollectorTraceRun {runId, contextId, createdAt})` — written by [recordRunContext]; every
 *   other write MATCHes this node to copy its `contextId`, so `contextId` is single-sourced here
 *   rather than threaded through every record call.
 * - `(:CollectorCandidateEdge {id, runId, contextId, anchorId, memberId, aggregateScore, vetoed})`
 *   with `id = "runId|anchorId|memberId"`, plus one child
 *   `(:CollectorSignalScore {id, runId, contextId, signal, score, weight, veto, explanation,
 *   evidenceRef})-[:SCORED]->(:CollectorCandidateEdge)` per signal so per-signal detail stays
 *   queryable.
 * - `(:CollectorComponent {id, runId, contextId, componentId, memberIds})` with
 *   `id = "runId|componentId"`.
 * - `(:CollectorDecision {id, runId, contextId, componentId, survivorId, action, createdAt})`
 *   with `id = "runId|componentId"`, plus one child
 *   `(:CollectorRetired {id, runId, contextId, propositionId, priorStatus, foldedGrounding,
 *   foldedProvenanceRefs, foldedSourceIds})-[:RETIRED_IN]->(:CollectorDecision)` per retired
 *   proposition, so a reversal has everything a merging sweep folded onto the survivor.
 *
 * Every write is a single `UNWIND $rows AS r ...` round trip (see [CollectorTraceRowMappers] for
 * the flattening); no APOC, no GDS. Query methods are corrupt-row-tolerant: a row that fails to
 * map is logged and skipped rather than failing the whole read.
 */
open class DrivineCollectorTraceStore(
    private val persistenceManager: PersistenceManager,
) : CollectorTraceStore {

    private val logger = LoggerFactory.getLogger(DrivineCollectorTraceStore::class.java)

    @Transactional
    override fun recordRunContext(runId: String, contextId: ContextId) {
        logger.debug("Recording collector trace run {} for context {}", runId.take(8), contextId.value)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MERGE (r:CollectorTraceRun {runId: ${'$'}runId})
                SET r.contextId = ${'$'}contextId,
                    r.createdAt = ${'$'}now
                """.trimIndent(),
            ).bind(mapOf("runId" to runId, "contextId" to contextId.value, "now" to Instant.now().toString())),
        )
    }

    @Transactional
    override fun recordCandidateEdges(runId: String, edges: List<CollectorCandidateEdge>) {
        if (edges.isEmpty()) return
        logger.debug("Recording {} collector candidate edge(s) for run {}", edges.size, runId.take(8))

        val edgeRows = edges.map { CollectorCandidateEdgeRowMapper.edgeBindMap(runId, it) }
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (run:CollectorTraceRun {runId: ${'$'}runId})
                UNWIND ${'$'}rows AS r
                MERGE (e:CollectorCandidateEdge {id: r.id})
                SET e += r, e.contextId = run.contextId
                """.trimIndent(),
            ).bind(mapOf("runId" to runId, "rows" to edgeRows)),
        )

        val signalRows = edges.flatMap { edge -> edge.signals.map { CollectorCandidateEdgeRowMapper.signalBindMap(runId, edge, it) } }
        if (signalRows.isEmpty()) return
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (run:CollectorTraceRun {runId: ${'$'}runId})
                UNWIND ${'$'}rows AS r
                MATCH (e:CollectorCandidateEdge {id: r.edgeId})
                MERGE (s:CollectorSignalScore {id: r.id})
                SET s += r, s.contextId = run.contextId
                MERGE (s)-[:SCORED]->(e)
                """.trimIndent(),
            ).bind(mapOf("runId" to runId, "rows" to signalRows)),
        )
    }

    @Transactional
    override fun recordComponents(runId: String, components: List<CollectorComponent>) {
        if (components.isEmpty()) return
        logger.debug("Recording {} collector component(s) for run {}", components.size, runId.take(8))

        val rows = components.map { CollectorComponentRowMapper.bindMap(runId, it) }
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (run:CollectorTraceRun {runId: ${'$'}runId})
                UNWIND ${'$'}rows AS r
                MERGE (c:CollectorComponent {id: r.id})
                SET c += r, c.contextId = run.contextId
                """.trimIndent(),
            ).bind(mapOf("runId" to runId, "rows" to rows)),
        )
    }

    @Transactional
    override fun recordDecision(runId: String, decision: CollectorDecision) {
        logger.debug("Recording collector decision for component {} in run {}", decision.componentId, runId.take(8))

        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (run:CollectorTraceRun {runId: ${'$'}runId})
                MERGE (d:CollectorDecision {id: ${'$'}row.id})
                SET d += ${'$'}row, d.contextId = run.contextId, d.createdAt = ${'$'}now
                """.trimIndent(),
            ).bind(
                mapOf(
                    "runId" to runId,
                    "row" to CollectorDecisionRowMapper.bindMap(runId, decision),
                    "now" to Instant.now().toString(),
                ),
            ),
        )

        if (decision.retired.isEmpty()) return
        val retiredRows = decision.retired.map { CollectorDecisionRowMapper.retiredBindMap(runId, decision, it) }
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (run:CollectorTraceRun {runId: ${'$'}runId})
                UNWIND ${'$'}rows AS r
                MATCH (d:CollectorDecision {id: r.decisionId})
                MERGE (ret:CollectorRetired {id: r.id})
                SET ret += r, ret.contextId = run.contextId
                MERGE (ret)-[:RETIRED_IN]->(d)
                """.trimIndent(),
            ).bind(mapOf("runId" to runId, "rows" to retiredRows)),
        )
    }

    @Transactional
    override fun deleteTracesForContext(contextId: ContextId) {
        val params = mapOf("contextId" to contextId.value)
        var totalDeleted = 0
        // One statement per label rather than a single multi-label match: keeps each delete a plain
        // parameterized MATCH + DETACH DELETE, no APOC/GDS needed to loop over labels in Cypher itself.
        for (label in CollectorTraceSchema.LABELS) {
            val deleted = persistenceManager.maybeGetOne(
                QuerySpecification
                    .withStatement(
                        """
                        MATCH (n:$label {contextId: ${'$'}contextId})
                        WITH collect(n) AS nodes, count(n) AS deleted
                        FOREACH (x IN nodes | DETACH DELETE x)
                        RETURN deleted
                        """.trimIndent(),
                    )
                    .bind(params)
                    .transform(Long::class.java),
            )
            totalDeleted += deleted?.toInt() ?: 0
        }
        logger.info("Deleted {} collector trace node(s) for context {}", totalDeleted, contextId.value)
    }

    // ---- Query helpers (plan Phase 3 requires these; not on the CollectorTraceStore interface) ----

    /** Rehydrates every edge recorded under [runId], each with its signals nested. */
    @Transactional(readOnly = true)
    fun findEdgesByRun(runId: String): List<CollectorCandidateEdge> {
        val edgeRows = queryRows("MATCH (e:CollectorCandidateEdge {runId: \$runId}) RETURN e", mapOf("runId" to runId))
        val signalRows = queryRows(
            """
            MATCH (s:CollectorSignalScore)-[:SCORED]->(e:CollectorCandidateEdge {runId: ${'$'}runId})
            RETURN {
                edgeId: e.id, signal: s.signal, score: s.score, weight: s.weight,
                veto: s.veto, explanation: s.explanation, evidenceRef: s.evidenceRef
            } AS row
            """.trimIndent(),
            mapOf("runId" to runId),
        )
        val signalsByEdgeId = signalRows.mapNotNull { row ->
            runCatching {
                val edgeId = row["edgeId"]?.toString().orEmpty()
                edgeId to CollectorCandidateEdgeRowMapper.signalFromRow(row)
            }.onFailure { logger.warn("Skipping unreadable CollectorSignalScore row: {}", it.message) }.getOrNull()
        }.groupBy({ it.first }, { it.second })

        return edgeRows.mapNotNull { node ->
            runCatching {
                val edgeId = node["id"]?.toString().orEmpty()
                CollectorCandidateEdgeRowMapper.fromRow(node, signalsByEdgeId[edgeId].orEmpty())
            }.onFailure { logger.warn("Skipping unreadable CollectorCandidateEdge row: {}", it.message) }.getOrNull()
        }
    }

    /** Rehydrates every decision recorded under [runId], each with its retired members nested. */
    @Transactional(readOnly = true)
    fun findDecisionsByRun(runId: String): List<CollectorDecision> {
        val decisionRows = queryRows("MATCH (d:CollectorDecision {runId: \$runId}) RETURN d", mapOf("runId" to runId))
        return decisionRows.mapNotNull { node ->
            runCatching {
                val decisionId = node["id"]?.toString().orEmpty()
                CollectorDecisionRowMapper.fromRow(node, retiredFor(decisionId))
            }.onFailure { logger.warn("Skipping unreadable CollectorDecision row: {}", it.message) }.getOrNull()
        }
    }

    /** Finds the decision that retired or preserved [propositionId], whichever side of the merge it was on. */
    @Transactional(readOnly = true)
    fun findDecisionForProposition(propositionId: String): CollectorDecision? {
        val bySurvivor = queryRows(
            "MATCH (d:CollectorDecision {survivorId: \$propositionId}) RETURN d",
            mapOf("propositionId" to propositionId),
        )
        val node = bySurvivor.firstOrNull() ?: queryRows(
            "MATCH (:CollectorRetired {propositionId: \$propositionId})-[:RETIRED_IN]->(d:CollectorDecision) RETURN d",
            mapOf("propositionId" to propositionId),
        ).firstOrNull() ?: return null
        return runCatching {
            val decisionId = node["id"]?.toString().orEmpty()
            CollectorDecisionRowMapper.fromRow(node, retiredFor(decisionId))
        }.onFailure { logger.warn("Skipping unreadable CollectorDecision row: {}", it.message) }.getOrNull()
    }

    private fun retiredFor(decisionId: String): List<RetiredProposition> =
        queryRows(
            "MATCH (ret:CollectorRetired {decisionId: \$decisionId}) RETURN ret",
            mapOf("decisionId" to decisionId),
        ).mapNotNull { node ->
            runCatching {
                CollectorDecisionRowMapper.retiredFromRow(node)
            }.onFailure { logger.warn("Skipping unreadable CollectorRetired row: {}", it.message) }.getOrNull()
        }

    /** Runs a parameterized read and returns each result row as a `Map<*, *>`. */
    private fun queryRows(statement: String, params: Map<String, Any?>): List<Map<*, *>> {
        @Suppress("UNCHECKED_CAST")
        val spec = QuerySpecification.withStatement(statement).let {
            if (params.isEmpty()) it else it.bind(params)
        } as QuerySpecification<Any>
        return persistenceManager.query(spec).filterIsInstance<Map<*, *>>()
    }
}
