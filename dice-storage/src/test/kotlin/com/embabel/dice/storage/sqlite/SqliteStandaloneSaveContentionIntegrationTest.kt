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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.FakeEmbeddingService
import com.embabel.dice.storage.TestApplication
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two saves of the same text, each on its own, while the embedding service takes far longer than
 * SQLite's busy timeout. The file here waits only 200 ms for a lock. A save that held the write
 * lock while it waited for its embedding would make the other save give up. Both must come back
 * as one proposition.
 */
@SpringBootTest(classes = [TestApplication::class, SqliteStandaloneSaveContentionIntegrationTest.ShortBusyTimeout::class])
class SqliteStandaloneSaveContentionIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) = SqliteTestStore.registerProperties(registry)
    }

    /** The `neo` datasource on a SQLite file of its own, whose connections wait 200 ms for a lock. */
    @TestConfiguration
    open class ShortBusyTimeout {
        @Bean
        open fun shortBusyTimeoutProvider(): ConnectionProvider {
            val file = Files.createTempFile("dice-sqlite-busy-", ".db").also { Files.delete(it) }
            file.toFile().deleteOnExit()
            return JdbcConnectionProvider.sqlite(name = "neo", path = file.toString(), busyTimeoutMillis = 200)
        }
    }

    @Autowired
    private lateinit var graphObjectManager: StatelessGraphObjectManager

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
    }

    @Test
    fun `two standalone saves of the same text agree on one proposition while embedding is slow`() {
        val slow = SlowEmbeddingService(FakeEmbeddingService(), delayMillis = 1_000)
        val slowRepository = DrivinePropositionRepository(graphObjectManager, persistenceManager, slow, transactionManager)
        val context = ContextId("slow-embed-ctx")
        fun proposition() = Proposition(
            contextId = context,
            text = "The ghost hums a slow tune",
            mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Proposition> { start.await(); slowRepository.save(proposition()) }
            val second = executor.submit<Proposition> { start.await(); slowRepository.save(proposition()) }
            start.countDown()

            val ids = setOf(first.get(30, TimeUnit.SECONDS).id, second.get(30, TimeUnit.SECONDS).id)

            assertEquals(1, ids.size, "two saves of one text came back as $ids")
            assertEquals(1, repository.findByContextId(context).size)
        } finally {
            executor.shutdown()
            executor.awaitTermination(60, TimeUnit.SECONDS)
        }
    }
}

/** Gives [delegate]'s embeddings, each after waiting [delayMillis]. */
private class SlowEmbeddingService(
    private val delegate: EmbeddingService,
    private val delayMillis: Long,
) : EmbeddingService by delegate {
    override fun embed(text: String): FloatArray {
        Thread.sleep(delayMillis)
        return delegate.embed(text)
    }

    override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)
}
