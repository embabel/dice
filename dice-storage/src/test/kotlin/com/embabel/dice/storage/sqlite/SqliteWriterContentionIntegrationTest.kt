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
import com.embabel.dice.storage.GraphDecayManager
import com.embabel.dice.storage.TestApplication
import com.embabel.dice.storage.cypherQuery
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.NativeDriverSource
import org.drivine.manager.DelegatingPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.transaction.Transactions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Writers contending for the SQLite file. A SQLite transaction takes the file's write lock as it
 * begins and keeps it until it ends, so each case here would otherwise wait out the busy timeout
 * or fail at once.
 */
@SpringBootTest(classes = [TestApplication::class])
class SqliteWriterContentionIntegrationTest {

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var graphObjectManager: StatelessGraphObjectManager

    @Autowired
    private lateinit var embeddingService: EmbeddingService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var registry: DatabaseRegistry

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
    }

    /**
     * One thread saves inside its own transaction and so holds the file's write lock. A second
     * thread then saves a text on its own and is about to open its transaction. Only then does the
     * first thread save that same text. Neither may wait on the other, and both end up as one
     * proposition.
     */
    @Test
    fun `a save in a caller's transaction does not stall a standalone save of the same text`() {
        val standaloneOpening = CountDownLatch(1)
        val signalling = OpeningSignalTransactionManager(transactionManager, standaloneOpening)
        val repository = DrivinePropositionRepository(graphObjectManager, persistenceManager, embeddingService, signalling)
        val context = ContextId("lock-order-ctx")
        val text = "The ghost walks the east wing"
        val callerHasWritten = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val caller = executor.submit<Proposition> {
                TransactionTemplate(transactionManager).execute {
                    repository.save(ghost("The ghost was seen at midnight", context))
                    callerHasWritten.countDown()
                    check(standaloneOpening.await(10, TimeUnit.SECONDS)) { "the standalone save never opened its transaction" }
                    repository.save(ghost(text, context))
                }
            }
            assertTrue(callerHasWritten.await(10, TimeUnit.SECONDS), "the caller's first save did not finish")
            val standalone = executor.submit<Proposition> { repository.save(ghost(text, context)) }

            val savedByCaller = caller.get(10, TimeUnit.SECONDS)
            val savedStandalone = standalone.get(10, TimeUnit.SECONDS)

            assertEquals(savedByCaller.id, savedStandalone.id)
            assertEquals(1, repository.findByContextId(context).count { it.text == text })
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(60, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a save inside a Drivine transaction joins it without Spring`() {
        val holder = (persistenceManager as DelegatingPersistenceManager).contextHolder
        val noSpring = object : PlatformTransactionManager by transactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
                error("A Drivine transaction is already active")
        }
        val direct = DrivinePropositionRepository(graphObjectManager, persistenceManager, embeddingService, noSpring)
        val context = ContextId("native-transaction")
        val fact = ghost("The ghost watches the front door", context)

        Transactions(holder).transactionWithoutResult(rollback = true) {
            assertEquals(fact.id, direct.save(fact).id)
            assertEquals(fact.id, direct.findById(fact.id)?.id)
        }

        assertTrue(repository.findByContextId(context).isEmpty())
    }

    /**
     * Another connection holds the file's write lock for a moment. The sweep waits for it, up to the
     * busy timeout, and then writes, instead of failing at once.
     */
    @Test
    fun `a decay sweep waits for another writer and then refreshes`() {
        val context = ContextId("decay-contended-ctx")
        // Drivine's SQL stores keep only microseconds, and the sweep can't read an instant saved with
        // more (drivine4j#421). The clock gives more on Windows and on some JDKs on Linux.
        val at = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val saved = repository.save(
            ghost("The ghost waits its turn", context)
                .copy(created = at, contentRevised = at, metadataRevised = at, lastAccessed = at),
        )
        val dataSource = (registry.connectionProvider("neo") as NativeDriverSource<*>).nativeDriver as DataSource
        dataSource.connection.use { it.createStatement().execute("CREATE TABLE IF NOT EXISTS decay_holder (x INTEGER)") }
        val holding = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    connection.createStatement().execute("INSERT INTO decay_holder (x) VALUES (1)")
                    holding.countDown()
                    Thread.sleep(1500)
                    connection.commit()
                }
            }
            assertTrue(holding.await(10, TimeUnit.SECONDS), "the other writer never took the lock")

            GraphDecayManager(repository, persistenceManager).materialize(context)

            writer.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            dataSource.connection.use { it.createStatement().execute("DROP TABLE IF EXISTS decay_holder") }
        }
        val refreshed = persistenceManager.query(
            cypherQuery("MATCH (p:Proposition {id: ${'$'}id}) WHERE p.decayUpdatedAt IS NOT NULL RETURN p.id")
                .bind(mapOf("id" to saved.id))
                .transform(String::class.java),
        )
        assertEquals(1, refreshed.size)
    }

    /**
     * The caller's transaction holds the file's write lock. A touch of its own would wait for that
     * lock while the caller waits for the touch, so the touch joins the caller's transaction.
     */
    @Test
    fun `touchAccessed inside a caller's transaction joins it`() {
        val context = ContextId("touch-ctx")
        val earlier = repository.save(ghost("The ghost rattles the chains", context))
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    repository.save(ghost("The ghost hides in the pantry", context))
                    repository.touchAccessed(listOf(earlier.id))
                }
            }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(60, TimeUnit.SECONDS)
        }
        val touched = persistenceManager.query(
            cypherQuery("MATCH (p:Proposition {id: ${'$'}id}) WHERE p.lastAccessed IS NOT NULL RETURN p.id")
                .bind(mapOf("id" to earlier.id))
                .transform(String::class.java),
        )
        assertEquals(1, touched.size)
    }

    private fun ghost(text: String, context: ContextId) = Proposition(
        contextId = context,
        text = text,
        mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
        confidence = 0.9,
    )

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) = SqliteTestStore.registerProperties(registry)
    }
}

/**
 * Passes every call on, and counts down [opening] when a save asks for a transaction of its own:
 * the moment a standalone save holds its dedup stripe and is about to open its transaction.
 */
private class OpeningSignalTransactionManager(
    private val delegate: PlatformTransactionManager,
    private val opening: CountDownLatch,
) : PlatformTransactionManager {

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        if (definition?.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            opening.countDown()
        }
        return delegate.getTransaction(definition)
    }

    override fun commit(status: TransactionStatus) = delegate.commit(status)

    override fun rollback(status: TransactionStatus) = delegate.rollback(status)
}
