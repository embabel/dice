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
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.DriftReportStore
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Drivine / Neo4j implementation of [DriftReportStore]: keeps every drift check as a
 * `(:MetamodelDriftReport)` node.
 *
 * The write MERGEs on the natural key `(schemaName, versionHash, capturedAt, contextKey)`, so a
 * retry updates the same node in place. That is race-free only under a uniqueness constraint on the
 * same four properties; see [MetamodelSchema], and see [DriftReportRowMapper.GLOBAL_CONTEXT_KEY] for
 * why the fourth property is `contextKey`.
 *
 * Every statement is parameterized; nothing caller-derived is interpolated into Cypher.
 *
 * ## Scope is applied in the query, ahead of the limit
 *
 * Each of the three reads has its own statement, with its scope in the `WHERE` clause and the
 * `LIMIT` applied after it. Reading one limited page and filtering it in Kotlin would apply the
 * limit before the scope, so a schema whose recent history is mostly context-scoped would report
 * zero global drift while the store held plenty. Hence three statements, and no `filter`. The
 * contract's test pins this.
 *
 * ## Ordering: capture instant, with a per-schema counter breaking ties
 *
 * The order is [DriftReport.capturedAt] descending, the instant the graph was looked at, which is
 * what the contract promises and what a `since` window bounds. Write order can't stand in for it: a
 * check of last week's snapshot saved today is still last week's observation.
 *
 * The instant alone is not a total order. Two reports of one schema, say a global sweep and a
 * per-context one, can share a capture instant, and a plain `ORDER BY` then leaves their relative
 * order to the database. Under a `LIMIT` the page boundary lands arbitrarily, so the same read can
 * return different rows each time and a caller walking the history can miss one. So each report also
 * takes the next value off a per-schema `(:MetamodelDriftReportCounter)` node, only when its node is
 * first created, and that sequence breaks ties. [DrivineMetamodelVersionStore] uses the same
 * mechanism on its own counter node. Stamps and reports have very different volumes — a stamp per
 * schema change, a report per scheduled check — so a shared counter would put every drift check in a
 * run into a write conflict with the version stamp preceding it, and would leave gaps in the version
 * store's sequence.
 *
 * A re-save of an existing report leaves the counter and the sequence alone, so the write stays
 * idempotent and a corrected observation keeps its original place.
 *
 * @param persistenceManager Drivine's handle on the `neo` datasource.
 */
open class DrivineDriftReportStore(
    private val persistenceManager: PersistenceManager,
) : DriftReportStore {

    private val logger = LoggerFactory.getLogger(DrivineDriftReportStore::class.java)

    private companion object {

        /**
         * Upsert the report node and, if this is the first time we've seen it, give it the next
         * number off its schema's counter. One statement, so one transaction: a report node never
         * exists without its place in the tie-break order.
         *
         * `WITH n WHERE n.sequence IS NULL` separates the two halves. On a re-save that filters the
         * row away, leaving the counter and the existing sequence untouched; the drifted type sets
         * are refreshed and the report keeps its original position.
         *
         * `SET c.lockedBy = ...` writes a property nobody reads, to take the exclusive lock on the
         * counter before the increment below reads it. On its own the increment is a
         * read-modify-write, and two concurrent savers could both read 5 and both write 6. The
         * benefit is unproven: the same measurement in [DrivineMetamodelVersionStore] could not tell
         * the locked and unlocked versions apart. The guarantee comes from the uniqueness constraint
         * on `(schemaName, sequence)`, under which a lost update fails with a constraint violation
         * the caller can retry.
         */
        private val SAVE_REPORT = """
            MERGE (n:MetamodelDriftReport {
                schemaName:  ${'$'}schemaName,
                versionHash: ${'$'}versionHash,
                capturedAt:  ${'$'}capturedAt,
                contextKey:  ${'$'}contextKey
            })
            SET n.driftedEntityTypes       = ${'$'}driftedEntityTypes,
                n.driftedRelationshipTypes = ${'$'}driftedRelationshipTypes,
                n.capturedAtEpochSecond    = ${'$'}capturedAtEpochSecond,
                n.capturedAtNano           = ${'$'}capturedAtNano,
                n.contextId                = ${'$'}contextId
            WITH n
            WHERE n.sequence IS NULL
            MERGE (c:MetamodelDriftReportCounter {schemaName: ${'$'}schemaName})
            SET c.lockedBy = ${'$'}capturedAt
            WITH n, c
            SET c.sequence = coalesce(c.sequence, 0) + 1
            WITH n, c
            SET n.sequence = c.sequence
        """.trimIndent()

        /**
         * Every read starts here.
         *
         * `capturedAtEpochSecond IS NOT NULL` is load-bearing. Neo4j sorts null as the largest
         * value, so a node missing the sort key would sort to the front of a DESC order, consume a
         * slot of the caller's `limit`, and then be dropped by the mapper, hiding a good report
         * behind a broken one. Excluding it in the database keeps it out of the order entirely.
         */
        private val MATCH_SCHEMA = """
            MATCH (n:MetamodelDriftReport {schemaName: ${'$'}schemaName})
            WHERE n.capturedAtEpochSecond IS NOT NULL
        """.trimIndent()

        /** Only unscoped, whole-graph checks: a global report has no `contextId` property at all. */
        private const val ONLY_GLOBAL = "AND n.contextId IS NULL"

        /** Only one context's checks. Global reports and other contexts' are excluded. */
        private const val ONLY_CONTEXT = "AND n.contextId = \$contextId"

        /**
         * The `since` bound, inclusive, compared second-then-nanosecond so it is exact.
         *
         * Comparing a single truncated millisecond value would sweep in reports captured just before
         * a bound that falls part-way through a millisecond.
         */
        private val SINCE_BOUND = """
            AND (n.capturedAtEpochSecond > ${'$'}sinceEpochSecond
                 OR (n.capturedAtEpochSecond = ${'$'}sinceEpochSecond AND n.capturedAtNano >= ${'$'}sinceNano))
        """.trimIndent()

        /** Newest first by capture instant, with the write sequence breaking exact ties. */
        private val NEWEST_FIRST_PAGE = """
            RETURN n
            ORDER BY n.capturedAtEpochSecond DESC, n.capturedAtNano DESC, coalesce(n.sequence, -1) DESC
            LIMIT ${'$'}limit
        """.trimIndent()
    }

    @Transactional
    override fun saveDriftReport(report: DriftReport) {
        logger.debug(
            "Saving drift report schemaName={} versionHash={} contextId={} capturedAt={}",
            report.schemaName,
            report.versionHash.take(8),
            report.contextId?.value,
            report.capturedAt,
        )
        persistenceManager.execute(
            QuerySpecification.withStatement(SAVE_REPORT).bind(DriftReportRowMapper.bindMap(report)),
        )
    }

    @Transactional(readOnly = true)
    override fun driftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
        readPage(scope = null, schemaName = schemaName, limit = limit, since = since)

    @Transactional(readOnly = true)
    override fun globalDriftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
        readPage(scope = ONLY_GLOBAL, schemaName = schemaName, limit = limit, since = since)

    @Transactional(readOnly = true)
    override fun driftReportsInContext(
        schemaName: String,
        contextId: ContextId,
        limit: Int,
        since: Instant?,
    ): List<DriftReport> = readPage(
        scope = ONLY_CONTEXT,
        schemaName = schemaName,
        limit = limit,
        since = since,
        extraBindings = mapOf("contextId" to contextId.value),
    )

    /**
     * Assemble and run one of the three scoped reads.
     *
     * The statement is assembled from the constants above and nothing else. `scope` is one of this
     * class's own literals, so no caller-supplied text reaches the statement, and every value
     * travels as a bound parameter.
     *
     * A corrupt row that survives the query is logged and skipped, which keeps one bad node from
     * failing the whole governance read; [DriftReportRowMapper] throws rather than inventing
     * defaults so that this can happen. A skipped row has already spent one of the caller's `limit`
     * slots, so a page can come back shorter than asked for. Reading further to backfill would break
     * the bound the contract keeps.
     */
    private fun readPage(
        scope: String?,
        schemaName: String,
        limit: Int,
        since: Instant?,
        extraBindings: Map<String, Any?> = emptyMap(),
    ): List<DriftReport> {
        require(limit > 0) { "limit must be positive, but was $limit" }

        val statement = buildString {
            append(MATCH_SCHEMA)
            scope?.let { append("\n").append(it) }
            if (since != null) append("\n").append(SINCE_BOUND)
            append("\n").append(NEWEST_FIRST_PAGE)
        }
        val bindings = buildMap {
            put("schemaName", schemaName)
            put("limit", limit)
            putAll(extraBindings)
            if (since != null) {
                put("sinceEpochSecond", since.epochSecond)
                put("sinceNano", since.nano)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val spec = QuerySpecification.withStatement(statement).bind(bindings) as QuerySpecification<Any>
        return persistenceManager.query(spec).filterIsInstance<Map<*, *>>().mapNotNull { row ->
            runCatching { DriftReportRowMapper.fromRow(row) }
                .onFailure { logger.warn("Skipping unreadable MetamodelDriftReport row: {}", it.message) }
                .getOrNull()
        }
    }
}
