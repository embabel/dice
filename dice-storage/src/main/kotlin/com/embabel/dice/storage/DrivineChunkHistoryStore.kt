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

import com.embabel.dice.incremental.AnalysisBookmark
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.storage.model.ProcessedChunkNode
import org.drivine.manager.*
import org.drivine.query.QuerySpecification
import org.springframework.transaction.annotation.Transactional

/**
 * Drivine / Neo4j [ChunkHistoryStore]: tracks processed source chunks (`:ProcessedChunk`) for
 * incremental analysis — dedup by content hash and per-source resume bookmarks. The graph
 * implementation of the dice-core SPI; ships here alongside [DrivinePropositionRepository] so a
 * consumer needs no hand-rolled store.
 */
open class DrivineChunkHistoryStore(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
) : ChunkHistoryStore {

    /** The node id is the content hash, so existence is a direct load-by-id. */
    @Transactional(readOnly = true)
    override fun isProcessed(contentHash: String): Boolean =
        graphObjectManager.load<ProcessedChunkNode>(contentHash) != null

    @Transactional
    override fun recordProcessed(record: ProcessedChunkRecord) {
        graphObjectManager.save(
            ProcessedChunkNode(
                id = record.contentHash,
                contentHash = record.contentHash,
                sourceId = record.sourceId,
                startIndex = record.startIndex,
                endIndex = record.endIndex,
                processedAt = record.processedAt,
            ),
            CascadeType.NONE,
        )
    }

    /**
     * Most-recently-processed chunk for the source. A bare `@NodeFragment` has no generated query
     * DSL, so the "latest by processedAt" pick is a small parameterized Cypher returning the id;
     * the node itself is then loaded via the high-level API (correct temporal deserialization).
     */
    @Transactional(readOnly = true)
    override fun getLastBookmark(sourceId: String): AnalysisBookmark? {
        val latestId = persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement(
                    "MATCH (c:ProcessedChunk {sourceId: \$sourceId}) " +
                        "RETURN c.id AS id ORDER BY c.processedAt DESC LIMIT 1"
                )
                .bind(mapOf("sourceId" to sourceId))
                .transform(String::class.java)
        ) ?: return null
        val node = graphObjectManager.load<ProcessedChunkNode>(latestId) ?: return null
        return AnalysisBookmark(sourceId = node.sourceId, endIndex = node.endIndex, processedAt = node.processedAt)
    }
}
