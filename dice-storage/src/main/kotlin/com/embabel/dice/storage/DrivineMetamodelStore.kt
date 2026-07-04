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
 * rather than duplicating. All statements are parameterized; user-derived values are never
 * interpolated into Cypher.
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
        logger.debug("Saving drift report schemaName={} versionHash={}", report.schemaName, report.versionHash.take(8))
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MERGE (n:MetamodelDriftReport {schemaName: ${'$'}schemaName, versionHash: ${'$'}versionHash, capturedAt: ${'$'}capturedAt})
                SET n.driftingEntityTypes       = ${'$'}driftingEntityTypes,
                    n.driftingRelationshipTypes = ${'$'}driftingRelationshipTypes
                """.trimIndent(),
            ).bind(DriftReportRowMapper.bindMap(report)),
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
}
