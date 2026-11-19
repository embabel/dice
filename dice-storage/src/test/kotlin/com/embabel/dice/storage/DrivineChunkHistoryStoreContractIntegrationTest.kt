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
import com.embabel.dice.conformance.AbstractChunkHistoryStoreContractTest
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

/**
 * Runs the [AbstractChunkHistoryStoreContractTest] suite against [DrivineChunkHistoryStore]. The subclasses
 * pick the store: Neo4j here, SQLite in the `sqlite` package. Each case starts and ends with an empty set of processed chunks.
 */
@SpringBootTest(classes = [TestApplication::class])
abstract class DrivineChunkHistoryStoreContractCases : AbstractChunkHistoryStoreContractTest() {

    @Autowired
    private lateinit var chunkHistoryStore: DrivineChunkHistoryStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    override fun store(): ChunkHistoryStore = chunkHistoryStore

    @BeforeEach
    @AfterEach
    fun clearProcessedChunks() {
        persistenceManager.execute(cypherQuery("MATCH (c:ProcessedChunk) DETACH DELETE c"))
    }

    @Test
    fun `clearAll removes every processed chunk node and nothing else`() {
        val ctx = ContextId("chunk-clear-ctx")
        chunkHistoryStore.recordProcessed(
            ProcessedChunkRecord(
                bookmarkKey = BookmarkKey(ctx, "source-1"),
                hashKey = HashKey(ctx, "hash-1"),
                startIndex = 0,
                endIndex = 5,
                processedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        persistenceManager.execute(
            cypherQuery(
                "CREATE (:ProcessedChunk {id: 'raw-chunk', contextId: 'raw-ctx'}), (:ChunkClearBystander {id: 'keep'})",
            ),
        )
        try {
            chunkHistoryStore.clearAll()

            assertEquals(0L, countNodes("ProcessedChunk"))
            assertEquals(1L, countNodes("ChunkClearBystander"))
        } finally {
            persistenceManager.execute(
                cypherQuery("MATCH (n:ChunkClearBystander) DETACH DELETE n"),
            )
        }
    }

    @Test
    fun `the bookmark is the later record when a whole second precedes a fraction of it`() {
        val ctx = ContextId("chunk-order-ctx")
        val bookmarkKey = BookmarkKey(ctx, "source-1")
        chunkHistoryStore.recordProcessed(
            ProcessedChunkRecord(
                bookmarkKey = bookmarkKey,
                hashKey = HashKey(ctx, "hash-whole-second"),
                startIndex = 0,
                endIndex = 5,
                processedAt = Instant.parse("2026-01-01T12:00:05Z"),
            ),
        )
        chunkHistoryStore.recordProcessed(
            ProcessedChunkRecord(
                bookmarkKey = bookmarkKey,
                hashKey = HashKey(ctx, "hash-half-second"),
                startIndex = 5,
                endIndex = 9,
                processedAt = Instant.parse("2026-01-01T12:00:05.500Z"),
            ),
        )

        assertEquals(9, chunkHistoryStore.getLastBookmark(bookmarkKey)?.endIndex)
    }

    /**
     * The two records share a time, and the earlier window's content hash sorts after the later
     * window's, so an order that falls back to the record id would pick the earlier window.
     */
    @Test
    fun `the bookmark is the further window when two records share a time`() {
        val ctx = ContextId("chunk-tie-ctx")
        val bookmarkKey = BookmarkKey(ctx, "source-1")
        val processedAt = Instant.parse("2026-01-01T12:00:05Z")
        chunkHistoryStore.recordProcessed(
            ProcessedChunkRecord(
                bookmarkKey = bookmarkKey,
                hashKey = HashKey(ctx, "hash-z"),
                startIndex = 0,
                endIndex = 20,
                processedAt = processedAt,
            ),
        )
        chunkHistoryStore.recordProcessed(
            ProcessedChunkRecord(
                bookmarkKey = bookmarkKey,
                hashKey = HashKey(ctx, "hash-a"),
                startIndex = 20,
                endIndex = 40,
                processedAt = processedAt,
            ),
        )

        assertEquals(40, chunkHistoryStore.getLastBookmark(bookmarkKey)?.endIndex)
    }

    /**
     * Counts the nodes that carry a label, read straight from the graph.
     *
     * @param label the node label to count
     * @return how many nodes carry the label
     */
    private fun countNodes(label: String): Long? = persistenceManager.maybeGetOne(
        cypherQuery("MATCH (n:$label) RETURN count(n) AS count")
            .transform(Long::class.java),
    )
}

/** Runs the [DrivineChunkHistoryStoreContractCases] on the shared Neo4j testcontainer. */
class DrivineChunkHistoryStoreContractIntegrationTest : DrivineChunkHistoryStoreContractCases() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }
}
