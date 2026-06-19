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

import com.embabel.dice.projection.lineage.CollectorRecord
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.CollectorRun
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.springframework.transaction.annotation.Transactional

/**
 * Drivine / Neo4j [CollectorRecordStore]: persists the collector audit trail as
 * `(:CollectorRecord)` and `(:CollectorRun)` nodes so collection history survives a restart. The
 * graph counterpart of the in-memory store, shipping here alongside [DrivinePropositionRepository].
 *
 * The query methods default to filtering [all] / [runs] in memory, so only the writers ([record],
 * [recordRun]) and readers ([all], [runs]) are supplied here. Writes MERGE on the natural key so a
 * retried record updates in place rather than duplicating. Every statement is parameterized; user-
 * derived values are never interpolated into Cypher.
 */
open class DrivineCollectorRecordStore(
    private val persistenceManager: PersistenceManager,
) : CollectorRecordStore {

    @Transactional
    override fun record(record: CollectorRecord) {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MERGE (n:CollectorRecord {propositionId: ${'$'}propositionId, runId: ${'$'}runId})
                SET n.reason         = ${'$'}reason,
                    n.survivorId     = ${'$'}survivorId,
                    n.outcome        = ${'$'}outcome,
                    n.strategyName   = ${'$'}strategyName,
                    n.at             = ${'$'}at,
                    n.previousStatus = ${'$'}previousStatus,
                    n.newStatus      = ${'$'}newStatus
                """.trimIndent(),
            ).bind(CollectorRecordRowMapper.bindMap(record)),
        )
    }

    @Transactional
    override fun recordRun(run: CollectorRun) {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MERGE (n:CollectorRun {runId: ${'$'}runId})
                SET n.startedAt  = ${'$'}startedAt,
                    n.finishedAt = ${'$'}finishedAt,
                    n.dryRun     = ${'$'}dryRun
                """.trimIndent(),
            ).bind(CollectorRunRowMapper.bindMap(run)),
        )
    }

    @Transactional(readOnly = true)
    override fun all(): List<CollectorRecord> {
        @Suppress("UNCHECKED_CAST")
        val rows = persistenceManager.query(
            QuerySpecification.withStatement("MATCH (n:CollectorRecord) RETURN n") as QuerySpecification<Any>,
        )
        return rows.filterIsInstance<Map<*, *>>().map(CollectorRecordRowMapper::fromRow)
    }

    @Transactional(readOnly = true)
    override fun runs(): List<CollectorRun> {
        @Suppress("UNCHECKED_CAST")
        val rows = persistenceManager.query(
            QuerySpecification.withStatement("MATCH (n:CollectorRun) RETURN n") as QuerySpecification<Any>,
        )
        return rows.filterIsInstance<Map<*, *>>().map(CollectorRunRowMapper::fromRow)
    }
}
