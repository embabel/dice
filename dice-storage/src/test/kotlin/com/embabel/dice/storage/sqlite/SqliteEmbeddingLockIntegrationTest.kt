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
import com.embabel.dice.storage.cypherQuery
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
 * A save waiting on a slow embedding service must not hold SQLite's write lock while it waits.
 * SQLite has one writer for the whole file, so a lock held across the embedding call would turn
 * every other write away. The file here waits only 200 ms for a lock, and another write is made
 * while the save is still waiting for its embedding.
 */
@SpringBootTest(classes = [TestApplication::class, SqliteEmbeddingLockIntegrationTest.ShortBusyTimeout::class])
class SqliteEmbeddingLockIntegrationTest {

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
        persistenceManager.execute(cypherQuery("MATCH (n:LockProbe) DETACH DELETE n"))
    }

    @Test
    fun `another write goes through while a save waits for its embedding`() {
        val embedding = HeldEmbeddingService(FakeEmbeddingService())
        val held = DrivinePropositionRepository(graphObjectManager, persistenceManager, embedding, transactionManager)
        val context = ContextId("held-embed-ctx")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val save = executor.submit<Proposition> {
                held.save(
                    Proposition(
                        contextId = context,
                        text = "The ghost hums a slow tune",
                        mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
                        confidence = 0.9,
                    ),
                )
            }
            assertTrue(embedding.started.await(10, TimeUnit.SECONDS), "the save never asked for its embedding")

            persistenceManager.execute(cypherQuery("CREATE (:LockProbe {id: 'probe'})"))

            embedding.release.countDown()
            save.get(30, TimeUnit.SECONDS)
            assertEquals(1, repository.findByContextId(context).size)
        } finally {
            embedding.release.countDown()
            executor.shutdown()
            executor.awaitTermination(60, TimeUnit.SECONDS)
        }
    }
}

/** Gives [delegate]'s embeddings, each only once [release] opens; [started] opens on the first ask. */
private class HeldEmbeddingService(private val delegate: EmbeddingService) : EmbeddingService by delegate {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun embed(text: String): FloatArray {
        started.countDown()
        release.await(30, TimeUnit.SECONDS)
        return delegate.embed(text)
    }

    override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)
}
