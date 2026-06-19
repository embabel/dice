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

import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.springframework.transaction.annotation.Transactional

/**
 * Drivine / Neo4j [ProjectionRecordStore]: persists projection lineage as `(:ProjectionRecord)`
 * nodes so it survives a restart and stays queryable by proposition or lifecycle. The graph
 * counterpart of the in-memory store, shipping here alongside [DrivinePropositionRepository].
 *
 * The query methods on the SPI default to filtering [all] in memory, so this only supplies the
 * writer ([record]), the reader ([all]), and a real [markStaleByProposition] — the SPI default for
 * that one is a no-op, which against a durable store would silently leave the lifecycle cascade
 * dead. Every statement is parameterized; user-derived values are never interpolated into Cypher.
 */
open class DrivineProjectionRecordStore(
    private val persistenceManager: PersistenceManager,
) : ProjectionRecordStore {

    /**
     * Upsert the record on its natural key (proposition + run + target) so a replayed projection
     * outcome updates in place rather than piling up duplicate nodes.
     */
    @Transactional
    override fun record(record: ProjectionRecord) {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MERGE (n:ProjectionRecord {propositionId: ${'$'}propositionId, runId: ${'$'}runId, target: ${'$'}target})
                SET n.targetRef = ${'$'}targetRef,
                    n.lifecycle = ${'$'}lifecycle,
                    n.at        = ${'$'}at,
                    n.reason    = ${'$'}reason
                """.trimIndent(),
            ).bind(ProjectionRecordRowMapper.bindMap(record)),
        )
    }

    @Transactional(readOnly = true)
    override fun all(): List<ProjectionRecord> {
        @Suppress("UNCHECKED_CAST")
        val rows = persistenceManager.query(
            QuerySpecification.withStatement("MATCH (n:ProjectionRecord) RETURN n") as QuerySpecification<Any>,
        )
        return rows.filterIsInstance<Map<*, *>>().map(ProjectionRecordRowMapper::fromRow)
    }

    /**
     * Flip every non-stale record for the proposition to [ProjectionLifecycle.STALE] in one
     * statement and return how many were actually transitioned. Scoping to records that are not
     * already stale keeps the operation idempotent: a replayed status-change event for an
     * already-stale proposition transitions nothing and returns 0.
     */
    @Transactional
    override fun markStaleByProposition(propositionId: String): Int {
        val stale = ProjectionLifecycle.STALE.name
        val updated = persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement(
                    """
                    MATCH (n:ProjectionRecord {propositionId: ${'$'}propositionId})
                    WHERE n.lifecycle <> ${'$'}stale
                    SET n.lifecycle = ${'$'}stale
                    RETURN count(n) AS updated
                    """.trimIndent(),
                )
                .bind(mapOf("propositionId" to propositionId, "stale" to stale))
                .transform(Long::class.java),
        )
        return updated?.toInt() ?: 0
    }
}
