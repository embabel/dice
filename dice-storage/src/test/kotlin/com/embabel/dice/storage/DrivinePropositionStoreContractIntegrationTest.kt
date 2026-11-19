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
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.storage.model.PropositionView
import org.drivine.StaleObjectException
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.load
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
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

    @Test
    fun `touchAccessed from a read-only transaction records the access`() {
        val saved = repository.save(ghost("The ghost dusts the piano"))
        val touchedAt = Instant.now()

        runWithin(10) {
            readOnly().execute {
                repository.findById(saved.id)
                repository.touchAccessed(listOf(saved.id))
            }
        }

        val lastAccessed = repository.findById(saved.id)!!.lastAccessed
        assertFalse(lastAccessed.isBefore(touchedAt), "lastAccessed $lastAccessed predates the touch at $touchedAt")
    }

    @Test
    fun `reembedAll from a read-only transaction rewrites the vectors`() {
        val saved = repository.save(ghost("The ghost tunes the harp"))

        val report = runWithin(10) {
            readOnly().execute {
                repository.findById(saved.id)
                repository.reembedAll()
            }!!
        }

        assertEquals(1, report.propositions)
    }

    /**
     * A second extraction of a fact the store already holds, with nothing new to add, writes
     * nothing. It is answered from the store without the embedding service, which may be down.
     */
    @Test
    fun `a save that repeats a stored fact does not need the embedding service`() {
        val stored = repository.save(ghost("The ghost winds the clock"))
        val unembeddable = DrivinePropositionRepository(
            graphObjectManager,
            persistenceManager,
            UnavailableEmbeddingService(embeddingService),
            transactionManager,
        )

        val replayed = unembeddable.save(ghost("The ghost winds the clock"))

        assertEquals(stored.id, replayed.id)
    }

    /**
     * The replay check finds a stored match by context and text, then loads it. If another writer
     * moves that proposition to another context in between, the loaded one is no match: the save
     * goes on and writes in its own context.
     */
    @Test
    fun `a save does not answer with a match that moved to another context`() {
        val stored = repository.save(ghost("The ghost paints the door", context = "replay-a"))
        val moving = DrivinePropositionRepository(
            graphObjectManager,
            MovingAfterLookupPersistenceManager(persistenceManager, stored.id),
            embeddingService,
            transactionManager,
        )

        val saved = moving.save(ghost("The ghost paints the door", context = "replay-a"))

        assertEquals(ContextId("replay-a"), saved.contextId)
        assertEquals(1, repository.findByContextId(ContextId("replay-a")).size)
    }

    /**
     * The replay check reads a stored match and then its evidence. If another writer moves the match
     * to another context between those reads, the check sees the move and the save goes on in its
     * own context.
     */
    @Test
    fun `a save does not answer with a match that moved while its evidence was read`() {
        val stored = repository.save(ghost("The ghost opens the shutters", context = "replay-b"))
        val moving = DrivinePropositionRepository(
            graphObjectManager,
            MovingOnEvidenceReadPersistenceManager(persistenceManager, stored.id),
            embeddingService,
            transactionManager,
        )

        val saved = moving.save(ghost("The ghost opens the shutters", context = "replay-b"))

        assertNotEquals(stored.id, saved.id)
        assertEquals(ContextId("replay-b"), saved.contextId)
    }

    /**
     * Through Spring's own transaction advice, with a host JDBC manager for another database. A host
     * that catches a failed touch and carries on commits its own work: the touch's failure is not
     * the host's.
     */
    @Test
    fun `a host that catches a failed touchAccessed still commits its own work`() {
        val saved = repository.save(ghost("The ghost knocks twice", context = "touch-host"))
        val hostData = SQLiteDataSource().apply { url = "jdbc:sqlite:" + Files.createTempFile("dice-touch-host-", ".db") }
        val hostManager = DataSourceTransactionManager(hostData)
        val host = JdbcTemplate(hostData)
        host.execute("CREATE TABLE host_rows (x INTEGER)")
        val touching = ProxyFactory(
            DrivinePropositionRepository(graphObjectManager, TouchRefusingPersistenceManager(persistenceManager), embeddingService, hostManager),
        ).apply {
            isProxyTargetClass = true
            addAdvice(TransactionInterceptor(hostManager as TransactionManager, AnnotationTransactionAttributeSource()))
        }.proxy as DrivinePropositionRepository

        TransactionTemplate(hostManager).executeWithoutResult {
            host.update("INSERT INTO host_rows (x) VALUES (1)")
            assertThrows<IllegalStateException> { touching.touchAccessed(listOf(saved.id)) }
        }

        assertEquals(1, host.queryForObject("SELECT count(*) FROM host_rows", Int::class.java))
    }

    /**
     * The replay check reads a stored match and then its evidence. If another writer updates the
     * match, its confidence and its evidence, between those reads, the check does not answer with
     * the old node and the new evidence: what it returns is one version the store has held.
     */
    @Test
    fun `a replay never answers with a node and evidence from two different versions`() {
        val first = ProvenanceEntry(locator = UriLocator("https://example.com/first"), chunkId = "first")
        val second = ProvenanceEntry(locator = UriLocator("https://example.com/second"), chunkId = "second")
        val stored = repository.save(ghost("The ghost turns the key", context = "replay-c").copy(provenanceEntries = listOf(first)))
        val reinforcing = DrivinePropositionRepository(
            graphObjectManager,
            WritingOnEvidenceReadPersistenceManager(persistenceManager, stored.id) {
                repository.save(stored.copy(confidence = 0.4, provenanceEntries = listOf(first, second)))
            },
            embeddingService,
            transactionManager,
        )

        val replayed = reinforcing.save(ghost("The ghost turns the key", context = "replay-c").copy(provenanceEntries = listOf(first)))

        val held = repository.findById(stored.id)!!
        assertEquals(stored.id, replayed.id)
        assertEquals(
            Triple(held.confidence, held.reinforceCount, held.provenanceEntries.toSet()),
            Triple(replayed.confidence, replayed.reinforceCount, replayed.provenanceEntries.toSet()),
        )
    }

    private fun readOnly() = TransactionTemplate(transactionManager).apply { isReadOnly = true }

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

/** Passes every call on, except that the read a save makes after writing the node fails. */
private class FailingMetadataReadPersistenceManager(
    private val delegate: PersistenceManager,
) : PersistenceManager by delegate {
    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        check(spec.statement?.text?.contains("STARTS WITH 'metadata.'") != true) { "the read after the node write failed" }
        return delegate.query(spec)
    }
}

/** An embedding service that is down: every call fails. */
private class UnavailableEmbeddingService(delegate: EmbeddingService) : EmbeddingService by delegate {
    override fun embed(text: String): FloatArray = error("the embedding service is down")

    override fun embed(texts: List<String>): List<FloatArray> = error("the embedding service is down")
}

/**
 * Passes every call on. The first time a duplicate lookup finds [movedId], it moves that proposition
 * to another context before answering, as a concurrent writer could between the lookup and the load.
 */
private class MovingAfterLookupPersistenceManager(
    private val delegate: PersistenceManager,
    private val movedId: String,
) : PersistenceManager by delegate {
    private var moved = false

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? {
        val result = delegate.maybeGetOne(spec)
        if (!moved && result == movedId && spec.parameters.keys == setOf("contextId", "text", "excludeId")) {
            moved = true
            delegate.execute(
                cypherQuery("MATCH (p:Proposition {id: ${'$'}id}) SET p.contextId = 'replay-elsewhere'")
                    .bind(mapOf("id" to movedId)),
            )
        }
        return result
    }
}

/**
 * Passes every call on. The first time the evidence of [movedId] is read, it moves that proposition
 * to another context first, as a concurrent writer could between the node read and this one.
 */
private class MovingOnEvidenceReadPersistenceManager(
    private val delegate: PersistenceManager,
    private val movedId: String,
) : PersistenceManager by delegate {
    private var moved = false

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        if (!moved && spec.parameters.keys == setOf("ids") && (spec.parameters["ids"] as? Collection<*>)?.contains(movedId) == true) {
            moved = true
            delegate.execute(
                cypherQuery("MATCH (p:Proposition {id: ${'$'}id}) SET p.contextId = 'replay-elsewhere'")
                    .bind(mapOf("id" to movedId)),
            )
        }
        return delegate.query(spec)
    }
}

/** Passes every call on, and refuses the access touch. */
private class TouchRefusingPersistenceManager(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    override fun execute(spec: QuerySpecification<*>) {
        check(spec.parameters.keys != setOf("ids", "now")) { "touch refused" }
        delegate.execute(spec)
    }
}

/**
 * Passes every call on. The first time the evidence of [watchedId] is read, it runs [write] first,
 * as another save could between the node read and this one.
 */
private class WritingOnEvidenceReadPersistenceManager(
    private val delegate: PersistenceManager,
    private val watchedId: String,
    private val write: () -> Unit,
) : PersistenceManager by delegate {
    private var written = false

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        if (!written && spec.parameters.keys == setOf("ids") && (spec.parameters["ids"] as? Collection<*>)?.contains(watchedId) == true) {
            written = true
            write()
        }
        return delegate.query(spec)
    }
}
