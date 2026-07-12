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

import com.embabel.dice.bundle.EmbeddingPort
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional

/**
 * [EmbeddingPort] backed directly by the Proposition node's own embedding property in Neo4j — the
 * same property [DrivinePropositionRepository] already writes (from [com.embabel.dice.proposition.Proposition.embeddableValue])
 * and reads for vector search. Wiring this into bundle export/import means a re-import can restore
 * the vector straight from the bundle instead of re-embedding at LLM cost.
 *
 * Reads and writes go through hand-written, parameterized Cypher against
 * [DrivinePropositionRepository.VECTOR_INDEX_LABEL] / [DrivinePropositionRepository.VECTOR_INDEX_PROPERTY] —
 * the same two constants the repository and its vector index are built on — so this stays wired to
 * whatever node label and property name the repository actually uses, rather than duplicating them.
 */
@Transactional
open class DrivineEmbeddingAccess(
    private val persistenceManager: PersistenceManager,
) : EmbeddingPort {

    private val logger = LoggerFactory.getLogger(DrivineEmbeddingAccess::class.java)

    /**
     * The proposition's stored embedding as a plain float list, or null if the node doesn't exist,
     * has no embedding, or the stored value can't be read back as a numeric vector (corrupt data
     * is treated the same as "missing" rather than thrown).
     */
    override fun embeddingFor(propositionId: String): List<Float>? {
        val raw = try {
            persistenceManager.maybeGetOne(
                QuerySpecification
                    .withStatement(
                        "MATCH (p:${DrivinePropositionRepository.VECTOR_INDEX_LABEL} {id: \$id}) " +
                            "RETURN p.${DrivinePropositionRepository.VECTOR_INDEX_PROPERTY} AS embedding"
                    )
                    .bind(mapOf("id" to propositionId))
            )
        } catch (ex: Exception) {
            logger.warn("Failed to read embedding for proposition {}: {}", propositionId, ex.message)
            return null
        }

        val values = raw as? List<*> ?: return null
        return try {
            values.map { (it as Number).toFloat() }
        } catch (ex: Exception) {
            logger.warn(
                "Stored embedding for proposition {} is not a numeric vector; treating as absent: {}",
                propositionId,
                ex.message,
            )
            null
        }
    }

    /**
     * Write [vector] onto the proposition node's embedding property, replacing whatever was there.
     * A no-op (matches zero rows) if no proposition with [propositionId] exists — bundle import
     * calls this after the proposition loop has run, so an absent node here is a real ordering bug
     * upstream, not something this method should paper over by creating a node itself.
     */
    override fun importEmbedding(propositionId: String, vector: List<Float>) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement(
                    "MATCH (p:${DrivinePropositionRepository.VECTOR_INDEX_LABEL} {id: \$id}) " +
                        "SET p.${DrivinePropositionRepository.VECTOR_INDEX_PROPERTY} = \$embedding"
                )
                .bind(mapOf("id" to propositionId, "embedding" to vector))
        )
    }
}
