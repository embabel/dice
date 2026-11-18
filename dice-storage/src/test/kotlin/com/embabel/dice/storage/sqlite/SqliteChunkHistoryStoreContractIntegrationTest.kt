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
package com.embabel.dice.storage.sqlite

import com.embabel.agent.core.ContextId
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.DrivineChunkHistoryStoreContractCases
import com.embabel.dice.storage.cypherQuery
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Runs the [DrivineChunkHistoryStoreContractCases] on Drivine's SQLite backend. */
class SqliteChunkHistoryStoreContractIntegrationTest : DrivineChunkHistoryStoreContractCases() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) = SqliteTestStore.registerProperties(registry)
    }

    @Autowired
    private lateinit var chunks: DrivineChunkHistoryStore

    @Autowired
    private lateinit var persistence: PersistenceManager

    /**
     * SQLite keeps an instant as text, and text written outside DICE can spell one instant two
     * ways. The two records here are at the same instant, so the further window wins.
     */
    @Test
    fun `the bookmark is the further window when two spellings name the same instant`() {
        val create = "CREATE (:ProcessedChunk {id: ${'$'}id, contextId: 'spelling-ctx', contentHash: ${'$'}hash, " +
            "sourceId: 'source-1', startIndex: ${'$'}start, endIndex: ${'$'}end, processedAt: ${'$'}at})"
        persistence.execute(
            cypherQuery(create).bind(
                mapOf("id" to "spelling-ctx|h1", "hash" to "h1", "start" to 0, "end" to 20, "at" to "2026-01-01T12:00:05.5Z"),
            ),
        )
        persistence.execute(
            cypherQuery(create).bind(
                mapOf("id" to "spelling-ctx|h2", "hash" to "h2", "start" to 20, "end" to 40, "at" to "2026-01-01T12:00:05.500Z"),
            ),
        )

        assertEquals(40, chunks.getLastBookmark(BookmarkKey(ContextId("spelling-ctx"), "source-1"))?.endIndex)
    }
}
