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
 * retry writes the same node again rather than a second copy of one observation. That is only
 * race-free under a uniqueness constraint on the same four properties — see [MetamodelSchema], and
 * see [DriftReportRowMapper.GLOBAL_CONTEXT_KEY] for why the fourth is `contextKey` and not
 * `contextId`.
 *
 * Every statement is parameterized; nothing caller-derived is ever interpolated into Cypher.
 *
 * ## Scope goes into the query, never into a filter afterwards
 *
 * The contract's hardest rule, and the one its test pins: each of the three reads has its own
 * statement, with its scope in the `WHERE` clause and the `LIMIT` applied after it. The tempting
 * shortcut — read one limited page and filter it down in Kotlin — applies the limit *before* the
 * scope, so a schema whose recent history happens to be mostly context-scoped would report zero
 * global drift while plenty sat in the store. That is a wrong answer that looks exactly like a
 * right one, which is why there are three statements here rather than one and a `filter`.
 *
 * ## Newest first means the capture instant, broken by a counter
 *
 * The order is [DriftReport.capturedAt] descending — the instant the graph was *looked at*, which
 * is what the contract promises and what a `since` window bounds. Write order can't stand in for
 * it: a check of last week's snapshot saved today is still last week's observation.
 *
 * The instant alone is not a total order, though. Two reports of one schema — a global sweep and a
 * per-context one, say — can share a capture instant, and then a plain `ORDER BY` leaves their
 * relative order to the database. With a `LIMIT` on top that is not merely untidy: the page
 * boundary lands somewhere arbitrary, so the same read can return different rows each time and a
 * caller walking the history can miss one entirely. So each report also takes the next value off a
 * per-schema `(:MetamodelDriftReportCounter)` node when — and only when — its node is first
 * created, and that sequence breaks ties. Same mechanism as
 * [DrivineMetamodelVersionStore]'s, deliberately on its own counter node: stamps and reports have
 * very different volumes (a stamp per schema change, a report per scheduled check), a shared
 * counter would put every drift check in the same run into a write conflict with the version stamp
 * that precedes it, and the version store's own sequence would grow gaps that mean nothing.
 *
 * A re-save of a report that already exists neither bumps the counter nor reassigns the sequence,
 * so an idempotent write stays idempotent and a corrected observation keeps its original place.
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
         * row away, so the counter is never bumped and the existing sequence is never reassigned —
         * the drifted type sets are refreshed and the report keeps the position it has always had.
         *
         * `SET c.lockedBy = ...` writes a property nobody reads, to take the exclusive lock on the
         * counter before the increment below reads it; on its own the increment is a
         * read-modify-write and two concurrent savers could both read 5 and both write 6. It is
         * cheap insurance rather than a proven necessity — the same measurement in
         * [DrivineMetamodelVersionStore] could not tell the locked and unlocked versions apart.
         * What is load-bearing is the uniqueness constraint on `(schemaName, sequence)`: a lost
         * update becomes a loud, retryable constraint violation instead of a silently arbitrary
         * order.
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
         * `capturedAtEpochSecond IS NOT NULL` is not defensive noise. Neo4j sorts null as the
         * *largest* value, so a node missing the sort key would sort to the front of a DESC order,
         * consume a slot of the caller's `limit`, and then be dropped by the mapper — hiding a
         * perfectly good report behind a broken one. A node with no sort key never took a place in
         * the order at all, so it is excluded in the database instead.
         */
        private val MATCH_SCHEMA = """
            MATCH (n:MetamodelDriftReport {schemaName: ${'$'}schemaName})
            WHERE n.capturedAtEpochSecond IS NOT NULL
        """.trimIndent()

        /** Only unscoped, whole-graph checks: a global report has no `contextId` property at all. */
        private const val ONLY_GLOBAL = "AND n.contextId IS NULL"

        /** Only one context's checks. Global reports and other contexts' are both excluded. */
        private const val ONLY_CONTEXT = "AND n.contextId = \$contextId"

        /**
         * The `since` bound, inclusive, compared second-then-nanosecond so it is exact.
         *
         * Comparing a single truncated millisecond value would be simpler and subtly wrong: a bound
         * falling part-way through a millisecond would sweep in reports captured just before it.
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
     * The statement is built from the constants above and nothing else — `scope` is one of this
     * class's own literals, never anything a caller supplied — so this stays string *assembly*, not
     * string interpolation of user data. Every value still travels as a bound parameter.
     *
     * A corrupt row that survives the query is warned about and skipped rather than taking down the
     * whole governance read; see [DriftReportRowMapper], which throws instead of inventing defaults
     * precisely so this can happen. Note the honest consequence of a bounded read: a skipped row has
     * already spent one of the caller's `limit` slots, so a page can come back shorter than asked
     * for. Silently reading further to backfill would break the bound the contract exists to keep.
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
