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

import com.embabel.dice.conformance.AbstractChunkHistoryStoreContractTest
import com.embabel.dice.incremental.ChunkHistoryStore
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the [AbstractChunkHistoryStoreContractTest] suite against [DrivineChunkHistoryStore] on the
 * shared Neo4j testcontainer. Each case starts and ends with an empty set of processed chunks.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineChunkHistoryStoreContractIntegrationTest : AbstractChunkHistoryStoreContractTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var chunkHistoryStore: DrivineChunkHistoryStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    override fun store(): ChunkHistoryStore = chunkHistoryStore

    @BeforeEach
    @AfterEach
    fun clearProcessedChunks() {
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (c:ProcessedChunk) DETACH DELETE c"))
    }

    @Test
    @Disabled("DrivineChunkHistoryStore does not override clearAll, so the call removes nothing.")
    override fun clearAllRemovesEveryContext() = super.clearAllRemovesEveryContext()
}
