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

import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.MetamodelStore
import com.embabel.dice.metamodel.MetamodelVersion
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional

/**
 * Drivine / Neo4j implementation of [MetamodelStore]: persists metamodel versions as
 * `(:MetamodelVersion)` nodes and drift observations as `(:MetamodelDriftReport)` nodes.
 *
 * Both node types MERGE on their natural keys so retries and idempotent writes update in place
 * rather than duplicating. `MetamodelDriftReport`'s natural key includes `contextKey`
 * ([GLOBAL_DRIFT_REPORT_CONTEXT_KEY] when the report is unscoped) alongside `contextId` itself,
 * so a scoped and a global (or other-context) report for the same schema/version/instant are
 * always two distinct nodes, never a silent overwrite. All statements are parameterized;
 * user-derived values are never interpolated into Cypher.
 *
 * Type sets (entity types, relationship types, labels, properties) are serialized to JSON
 * strings for escape-safe encoding that handles any characters in names. The query methods
 * sort by storage timestamps in Cypher rather than in memory, so ordering is deterministic
 * across restarts. savedAt is set only on CREATE, so idempotent re-saves preserve the original
 * creation timestamp and history order.
 */
open class DrivineMetamodelStore(
    private val persistenceManager: PersistenceManager,
) : MetamodelStore {

    private val logger = LoggerFactory.getLogger(DrivineMetamodelStore::class.java)

    companion object {
        /**
         * A real `contextId` is never merged into `MetamodelDriftReport`'s natural key directly:
         * Cypher property-map equality on a literal `null` never matches (not even against a node
         * with no such property at all), so a `null`-context report would always take MERGE's
         * CREATE branch and duplicate on every retry instead of updating in place. `contextKey`
         * is a separate property that's never itself null — it's the real context id when there
         * is one, or this sentinel when there isn't — so MERGE can key on it directly and a
         * global report is exactly as idempotent as a scoped one. It lives in its own property
         * namespace (`contextKey`, not `contextId`), so a tenant whose context id happened to be
         * this exact sentinel string still couldn't collide with the global bucket.
         */
        internal const val GLOBAL_DRIFT_REPORT_CONTEXT_KEY = "\u0000__global-context__\u0000"
    }

    private fun contextKeyFor(contextId: String?): String = contextId ?: GLOBAL_DRIFT_REPORT_CONTEXT_KEY

    @Transactional
    override fun saveVersion(version: MetamodelVersion) {
        logger.debug("Saving metamodel version schemaName={} contentHash={}", version.schemaName, version.contentHash.take(8))
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MERGE (n:MetamodelVersion {schemaName: ${'$'}schemaName, contentHash: ${'$'}contentHash})
                ON CREATE SET n.savedAt = ${'$'}savedAt
                SET n.entityTypeNames       = ${'$'}entityTypeNames,
                    n.entityTypeLabels      = ${'$'}entityTypeLabels,
                    n.entityTypeProperties  = ${'$'}entityTypeProperties,
                    n.relationshipNames     = ${'$'}relationshipNames
                """.trimIndent(),
            ).bind(MetamodelVersionRowMapper.bindMap(version)),
        )
    }

    @Transactional(readOnly = true)
    override fun latestVersion(schemaName: String): MetamodelVersion? {
        @Suppress("UNCHECKED_CAST")
        val rows = persistenceManager.query(
            QuerySpecification.withStatement(
                """
                MATCH (n:MetamodelVersion {schemaName: ${'$'}schemaName})
                RETURN n
                ORDER BY n.savedAt DESC
                LIMIT 1
                """.trimIndent(),
            ).bind(mapOf("schemaName" to schemaName)) as QuerySpecification<Any>,
        )
        return rows.filterIsInstance<Map<*, *>>().mapNotNull { row ->
            runCatching { MetamodelVersionRowMapper.fromRow(row) }
                .onFailure { logger.warn("Skipping unreadable MetamodelVersion row: {}", it.message) }
                .getOrNull()
        }.firstOrNull()
    }

    @Transactional(readOnly = true)
    override fun versionHistory(schemaName: String): List<MetamodelVersion> {
        @Suppress("UNCHECKED_CAST")
        val spec = QuerySpecification.withStatement(
            """
            MATCH (n:MetamodelVersion {schemaName: ${'$'}schemaName})
            RETURN n
            ORDER BY n.savedAt DESC
            """.trimIndent(),
        ).bind(mapOf("schemaName" to schemaName)) as QuerySpecification<Any>
        return persistenceManager.query(spec).filterIsInstance<Map<*, *>>().mapNotNull { row ->
            runCatching { MetamodelVersionRowMapper.fromRow(row) }
                .onFailure { logger.warn("Skipping unreadable MetamodelVersion row: {}", it.message) }
                .getOrNull()
        }
    }

    @Transactional
    override fun saveDriftReport(report: DriftReport) {
        logger.debug(
            "Saving drift report schemaName={} versionHash={} contextId={}",
            report.schemaName, report.versionHash.take(8), report.contextId,
        )
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MERGE (n:MetamodelDriftReport {
                    schemaName: ${'$'}schemaName,
                    versionHash: ${'$'}versionHash,
                    capturedAt: ${'$'}capturedAt,
                    contextKey: ${'$'}contextKey
                })
                SET n.driftingEntityTypes       = ${'$'}driftingEntityTypes,
                    n.driftingRelationshipTypes = ${'$'}driftingRelationshipTypes,
                    n.contextId                 = ${'$'}contextId
                """.trimIndent(),
            ).bind(DriftReportRowMapper.bindMap(report) + ("contextKey" to contextKeyFor(report.contextId))),
        )
    }

    @Transactional(readOnly = true)
    override fun driftReports(schemaName: String): List<DriftReport> {
        @Suppress("UNCHECKED_CAST")
        val spec = QuerySpecification.withStatement(
            """
            MATCH (n:MetamodelDriftReport {schemaName: ${'$'}schemaName})
            RETURN n
            ORDER BY n.capturedAt DESC
            """.trimIndent(),
        ).bind(mapOf("schemaName" to schemaName)) as QuerySpecification<Any>
        return persistenceManager.query(spec).filterIsInstance<Map<*, *>>().mapNotNull { row ->
            runCatching { DriftReportRowMapper.fromRow(row) }
                .onFailure { logger.warn("Skipping unreadable MetamodelDriftReport row: {}", it.message) }
                .getOrNull()
        }
    }

    /**
     * [contextId] filters in Cypher, not in memory: a `null` context matches reports with no
     * `contextId` property at all (a Neo4j node never stores an explicitly-null property, so
     * "global" reports simply don't have the property), and a non-null context matches an exact
     * equality on it.
     */
    @Transactional(readOnly = true)
    override fun driftReports(schemaName: String, contextId: String?): List<DriftReport> {
        @Suppress("UNCHECKED_CAST")
        val spec = if (contextId != null) {
            QuerySpecification.withStatement(
                """
                MATCH (n:MetamodelDriftReport {schemaName: ${'$'}schemaName, contextId: ${'$'}contextId})
                RETURN n
                ORDER BY n.capturedAt DESC
                """.trimIndent(),
            ).bind(mapOf("schemaName" to schemaName, "contextId" to contextId)) as QuerySpecification<Any>
        } else {
            QuerySpecification.withStatement(
                """
                MATCH (n:MetamodelDriftReport {schemaName: ${'$'}schemaName})
                WHERE n.contextId IS NULL
                RETURN n
                ORDER BY n.capturedAt DESC
                """.trimIndent(),
            ).bind(mapOf("schemaName" to schemaName)) as QuerySpecification<Any>
        }
        return persistenceManager.query(spec).filterIsInstance<Map<*, *>>().mapNotNull { row ->
            runCatching { DriftReportRowMapper.fromRow(row) }
                .onFailure { logger.warn("Skipping unreadable MetamodelDriftReport row: {}", it.message) }
                .getOrNull()
        }
    }
}
