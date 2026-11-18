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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.conformance.AbstractPropositionStoreContractTest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.storage.model.PropositionView
import org.drivine.StaleObjectException
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.load
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the [AbstractPropositionStoreContractTest] suite against
 * [DrivinePropositionRepository] on a real graph store: Neo4j here, SQLite in the `sqlite` package.
 * This is the half that catches a graph backend
 * silently disagreeing with the in-memory contract — substitutability enforced, not assumed.
 */
@SpringBootTest(classes = [TestApplication::class])
abstract class DrivinePropositionStoreContractCases : AbstractPropositionStoreContractTest() {

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var graphObjectManager: StatelessGraphObjectManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var embeddingService: EmbeddingService

    override fun store(): PropositionStore = repository

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
        persistenceManager.execute(cypherQuery("MATCH (s:Source) DETACH DELETE s"))
    }

    @Test
    fun `a save drops the metadata keys the map no longer carries`() {
        val saved = repository.save(
            Proposition(
                contextId = ContextId("metadata-ctx"),
                text = "The ghost haunts the manor",
                mentions = listOf(
                    EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT),
                ),
                confidence = 0.9,
                metadata = mapOf("keep" to "a", "drop" to "b"),
            ),
        )

        repository.save(saved.copy(metadata = mapOf("keep" to "a")))

        val keys = persistenceManager.query(
            cypherQuery("MATCH (p:Proposition {id: ${'$'}id}) UNWIND keys(p) AS key RETURN key")
                .bind(mapOf("id" to saved.id))
                .transform(String::class.java),
        )
        assertTrue("metadata.keep" in keys, "keys on the node: $keys")
        assertFalse("metadata.drop" in keys, "keys on the node: $keys")
        assertEquals(mapOf("keep" to "a"), repository.findById(saved.id)!!.metadata)
    }

    /**
     * The second save holds the proposition exactly as stored, less one metadata key. The node write
     * changes nothing, so the mark is the key removal's.
     */
    @Test
    fun `a save of a loaded proposition is refused after a metadata key is removed from it`() {
        val saved = repository.save(
            Proposition(
                contextId = ContextId("metadata-ctx"),
                text = "The ghost haunts the manor",
                mentions = listOf(
                    EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT),
                ),
                confidence = 0.9,
                metadata = mapOf("source" to "wiki"),
            ),
        )
        val loaded = requireNotNull(graphObjectManager.load<PropositionView>(saved.id))

        repository.save(saved.copy(metadata = emptyMap()))

        assertThrows<StaleObjectException> {
            graphObjectManager.save(loaded, DrivinePropositionRepository.REPLACE_MENTIONS)
        }
    }

    /**
     * One thread saves inside its own transaction and has already written, so on SQLite it holds
     * the file's write lock. A second thread then saves a text on its own and is about to open its
     * transaction. Only then does the first thread save that same text. Neither save may wait on
     * the other for longer than the first transaction takes, and both must end up as one
     * proposition.
     */
    @Test
    fun `a save in a caller's transaction does not stall a standalone save of the same text`() {
        val standaloneOpening = CountDownLatch(1)
        val signalling = OpeningSignalTransactionManager(transactionManager, standaloneOpening)
        val repository = DrivinePropositionRepository(graphObjectManager, persistenceManager, embeddingService, signalling)
        val context = ContextId("lock-order-ctx")
        fun proposition(text: String) = Proposition(
            contextId = context,
            text = text,
            mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )
        val text = "The ghost walks the east wing"
        val callerHasWritten = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val caller = executor.submit<Proposition> {
                TransactionTemplate(transactionManager).execute {
                    repository.save(proposition("The ghost was seen at midnight"))
                    callerHasWritten.countDown()
                    check(standaloneOpening.await(10, TimeUnit.SECONDS)) { "the standalone save never opened its transaction" }
                    repository.save(proposition(text))
                }
            }
            assertTrue(callerHasWritten.await(10, TimeUnit.SECONDS), "the caller's first save did not finish")
            val standalone = executor.submit<Proposition> { repository.save(proposition(text)) }

            val savedByCaller = caller.get(10, TimeUnit.SECONDS)
            val savedStandalone = standalone.get(10, TimeUnit.SECONDS)

            assertEquals(savedByCaller.id, savedStandalone.id)
            assertEquals(1, repository.findByContextId(context).count { it.text == text })
        } finally {
            executor.shutdown()
            executor.awaitTermination(60, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `findClusters groups propositions with the same embedding`() {
        // The same text in two contexts is not deduplicated, so the two share an embedding.
        repository.save(ghost("The ghost hums in the attic", context = "cluster-a"))
        repository.save(ghost("The ghost hums in the attic", context = "cluster-b"))
        repository.save(ghost("A lantern burns in the cellar", context = "cluster-a"))

        val clusters = repository.findClusters(similarityThreshold = 0.95, topK = 10, query = PropositionQuery())

        assertEquals(1, clusters.size, "clusters: $clusters")
        assertEquals(1, clusters.single().similar.size)
        assertEquals(1.0, clusters.single().similar.single().score, 0.001)
    }

    /**
     * On SQLite the caller's transaction holds the file's write lock once it has read, so a touch
     * that opened a transaction of its own would wait for the caller, which is waiting for it.
     */
    @Test
    fun `touchAccessed inside a transaction that has read the store finishes and records the access`() {
        val saved = repository.save(ghost("The ghost counts the stairs"))
        val touchedAt = Instant.now()

        runWithin(10) {
            TransactionTemplate(transactionManager).execute {
                repository.findById(saved.id)
                repository.touchAccessed(listOf(saved.id))
            }
        }

        val lastAccessed = repository.findById(saved.id)!!.lastAccessed
        assertFalse(lastAccessed.isBefore(touchedAt), "lastAccessed $lastAccessed predates the touch at $touchedAt")
    }

    @Test
    fun `reembedAll inside a transaction that has read the store finishes and rewrites the vectors`() {
        val saved = repository.save(ghost("The ghost rattles the shutters"))

        val report = runWithin(10) {
            TransactionTemplate(transactionManager).execute {
                repository.findById(saved.id)
                repository.reembedAll()
            }!!
        }

        assertEquals(1, report.propositions)
    }

    /**
     * A host can bring a transaction manager of its own, which knows nothing of Drivine. A save
     * made while that manager's transaction is open still writes all of itself or none of itself:
     * here the write after the node fails, and the node must not be left behind.
     */
    @Test
    fun `a save in a transaction of another manager writes nothing when it fails part way`() {
        val failing = DrivinePropositionRepository(
            graphObjectManager,
            FailingMetadataReadPersistenceManager(persistenceManager),
            embeddingService,
            transactionManager,
        )
        val proposition = ghost("The ghost slams the cellar door")

        TransactionTemplate(ForeignTransactionManager()).executeWithoutResult {
            assertThrows<IllegalStateException> { failing.save(proposition) }
        }

        assertNull(repository.findById(proposition.id), "the node outlived the failed save")
    }

    private fun ghost(text: String, context: String = "ghost-ctx") = Proposition(
        contextId = ContextId(context),
        text = text,
        mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
        confidence = 0.9,
    )

    /** Runs [block] on another thread and fails the test if it takes longer than [seconds]. */
    private fun <T> runWithin(seconds: Long, block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<T> { block() }
            return result.get(seconds, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
            executor.awaitTermination(60, TimeUnit.SECONDS)
        }
    }
}

/** Runs the [DrivinePropositionStoreContractCases] on the shared Neo4j testcontainer. */
class DrivinePropositionStoreContractIntegrationTest : DrivinePropositionStoreContractCases() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }
}

/**
 * Passes every call to [delegate], and counts down [opening] when a save asks for a transaction of
 * its own. That is the moment a standalone save has taken its dedup stripe and is about to open
 * its transaction.
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

/**
 * A transaction manager that knows nothing of Drivine, like one a host brings for a database of
 * its own. Its transactions hold nothing.
 */
private class ForeignTransactionManager : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()
    override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
    override fun doCommit(status: DefaultTransactionStatus) = Unit
    override fun doRollback(status: DefaultTransactionStatus) = Unit
}

/** Passes every call on, except that the read a save makes after writing the node fails. */
private class FailingMetadataReadPersistenceManager(
    private val delegate: PersistenceManager,
) : PersistenceManager by delegate {
    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        check(spec.statement?.text?.contains("STARTS WITH 'metadata.'") != true) { "the read after the node write failed" }
        return delegate.query(spec)
    }
}
