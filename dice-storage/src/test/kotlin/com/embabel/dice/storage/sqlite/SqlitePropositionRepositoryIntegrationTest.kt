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
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.FakeEmbeddingService
import com.embabel.dice.storage.TestApplication
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What the proposition store does on SQLite where it cannot do what it does on Neo4j. */
@SpringBootTest(classes = [TestApplication::class])
class SqlitePropositionRepositoryIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) = SqliteTestStore.registerProperties(registry)
    }

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var graphObjectManager: StatelessGraphObjectManager

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
    }

    /**
     * A new embedding width means the vector index is made again, and on SQLite Drivine records
     * that on a connection of its own, which would wait for the caller's write lock until SQLite
     * gave up. Inside a caller's transaction the call is refused at once, saying why.
     */
    @Test
    fun `reembedAll inside a transaction refuses at once when the vector index must change`() {
        repository.save(ghost("The ghost knocks twice", "reembed-ctx"))
        val wider = DrivinePropositionRepository(
            graphObjectManager,
            persistenceManager,
            FakeEmbeddingService(dimensions = 8),
            transactionManager,
        )

        val failure = runWithin(10) {
            runCatching {
                TransactionTemplate(transactionManager).execute {
                    repository.findAll()
                    wider.reembedAll()
                }
            }.exceptionOrNull()
        }

        assertTrue(failure is IllegalStateException, "expected a refusal, got $failure")
        assertTrue(failure!!.message!!.contains("transaction"), failure.message)
    }

    /**
     * Three propositions a < b < c. b's nearest is a, c's nearest is b, and a and c are far apart.
     * With one neighbour each, the pairs are a-b and b-c, as the in-memory store finds them.
     */
    @Test
    fun `findClusters keeps a pair that only the later member's search finds`() {
        val vectors = mapOf(
            "alpha fact" to floatArrayOf(1f, 0f),
            "beta fact" to floatArrayOf(0.9f, 0.4359f),
            "gamma fact" to floatArrayOf(0.5f, 0.866f),
        )
        val shaped = DrivinePropositionRepository(
            graphObjectManager,
            persistenceManager,
            FixedEmbeddingService(vectors, FakeEmbeddingService()),
            transactionManager,
        )
        shaped.save(ghost("alpha fact", "cluster-k", id = "a"))
        shaped.save(ghost("beta fact", "cluster-k", id = "b"))
        shaped.save(ghost("gamma fact", "cluster-k", id = "c"))

        val pairs = shaped.findClusters(similarityThreshold = 0.7, topK = 1, query = PropositionQuery.forContextId(ContextId("cluster-k")))
            .flatMap { cluster -> cluster.similar.map { cluster.anchor.id to it.match.id } }
            .toSet()

        assertEquals(setOf("a" to "b", "b" to "c"), pairs)
    }

    /**
     * Four propositions at 0, 5, 30 and 33 degrees. Each one's nearest is a or d's partner, so no
     * search names b and c together, yet c is b's closest among the later ids. With one neighbour
     * each, the pairs are a-b, b-c and c-d, as the in-memory store finds them.
     */
    @Test
    fun `findClusters keeps a pair that neither member's own search finds`() {
        fun at(degrees: Double) = floatArrayOf(
            Math.cos(Math.toRadians(degrees)).toFloat(),
            Math.sin(Math.toRadians(degrees)).toFloat(),
        )
        val vectors = mapOf("a fact" to at(0.0), "b fact" to at(5.0), "c fact" to at(30.0), "d fact" to at(33.0))
        val shaped = DrivinePropositionRepository(
            graphObjectManager,
            persistenceManager,
            FixedEmbeddingService(vectors, FakeEmbeddingService()),
            transactionManager,
        )
        listOf("a", "b", "c", "d").forEach { shaped.save(ghost("$it fact", "cluster-four", id = it)) }

        val pairs = shaped.findClusters(similarityThreshold = 0.9, topK = 1, query = PropositionQuery.forContextId(ContextId("cluster-four")))
            .flatMap { cluster -> cluster.similar.map { cluster.anchor.id to it.match.id } }
            .toSet()

        assertEquals(setOf("a" to "b", "b" to "c", "c" to "d"), pairs)
    }

    /**
     * Three candidates at -5, 0 and 10 degrees pair as a-b and b-c. Once the pass has searched, three
     * propositions of another context are saved exactly where b is. The clusters measure only the
     * candidates, so the pair b-c is still found, as it would be had the others been there all along.
     */
    @Test
    fun `findClusters keeps a pair when closer propositions of another context are saved during the pass`() {
        fun at(degrees: Double) = floatArrayOf(
            Math.cos(Math.toRadians(degrees)).toFloat(),
            Math.sin(Math.toRadians(degrees)).toFloat(),
        )
        val vectors = mapOf("a fact" to at(-5.0), "b fact" to at(0.0), "c fact" to at(10.0)) +
            (1..3).associate { "crowd $it fact" to at(0.0) }
        val embedding = FixedEmbeddingService(vectors, FakeEmbeddingService())
        val plain = DrivinePropositionRepository(graphObjectManager, persistenceManager, embedding, transactionManager)
        listOf("a", "b", "c").forEach { plain.save(ghost("$it fact", "cluster-crowded", id = it)) }
        val crowding = AfterFirstSearchPersistenceManager(persistenceManager) {
            (1..3).forEach { plain.save(ghost("crowd $it fact", "cluster-crowd-other", id = "x$it")) }
        }
        val clustering = DrivinePropositionRepository(countingObjects(crowding), crowding, embedding, transactionManager)

        val pairs = clustering.findClusters(similarityThreshold = 0.9, topK = 1, query = PropositionQuery.forContextId(ContextId("cluster-crowded")))
            .flatMap { cluster -> cluster.similar.map { cluster.anchor.id to it.match.id } }
            .toSet()

        assertTrue(crowding.ran, "the pass never searched")
        assertEquals(setOf("a" to "b", "b" to "c"), pairs)
    }

    /**
     * Twelve propositions all close to one another make 66 pairs above the threshold, and twenty
     * more of another context are stored beside them. The search measures the twelve alone, one row
     * each, and each anchor keeps at most two later neighbours.
     */
    @Test
    fun `findClusters measures only the candidates and keeps at most topK later neighbours an anchor`() {
        fun at(degrees: Double) = floatArrayOf(
            Math.cos(Math.toRadians(degrees)).toFloat(),
            Math.sin(Math.toRadians(degrees)).toFloat(),
        )
        val names = (0 until 12).map { "p%02d".format(it) }
        val vectors = names.mapIndexed { i, n -> "$n fact" to at(i.toDouble()) }.toMap()
        val counting = RowCountingPersistenceManager(persistenceManager)
        val shaped = DrivinePropositionRepository(
            countingObjects(counting),
            counting,
            FixedEmbeddingService(vectors, FakeEmbeddingService()),
            transactionManager,
        )
        names.forEach { shaped.save(ghost("$it fact", "cluster-dense", id = it)) }
        (0 until 20).forEach { shaped.save(ghost("p%02d fact".format(it % 12), "cluster-dense-other", id = "other-$it")) }
        counting.seedRows = 0

        val clusters = shaped.findClusters(similarityThreshold = 0.9, topK = 2, query = PropositionQuery.forContextId(ContextId("cluster-dense")))

        assertEquals(names.size, counting.seedRows, "measured ${counting.seedRows} seeds for ${names.size} candidates")
        assertEquals(11, clusters.size)
        clusters.forEach { cluster ->
            assertTrue(cluster.similar.size <= 2)
            assertTrue(cluster.similar.all { it.match.id > cluster.anchor.id })
        }
    }

    /**
     * Thirty propositions at a real embedding width. Each pair is scored by Drivine's own cosine, so
     * the clusters come back in well under a second, and a save started meanwhile goes through.
     */
    @Test
    fun `findClusters over thirty wide embeddings is quick and does not hold up a save`() {
        val random = java.util.Random(7)
        val base = FloatArray(1536) { random.nextFloat() }
        val vectors = (0 until 30).associate { i ->
            "w%02d fact".format(i) to FloatArray(1536) { base[it] + random.nextFloat() * 0.3f }
        }
        val shaped = DrivinePropositionRepository(
            graphObjectManager,
            persistenceManager,
            FixedEmbeddingService(vectors + ("late fact" to base), FakeEmbeddingService()),
            transactionManager,
        )
        vectors.keys.forEach { shaped.save(ghost(it, "cluster-wide", id = it.substringBefore(' '))) }
        val query = PropositionQuery.forContextId(ContextId("cluster-wide"))
        shaped.findClusters(similarityThreshold = 0.5, topK = 5, query = query)

        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = System.nanoTime()
            val clustering = executor.submit<Int> { shaped.findClusters(similarityThreshold = 0.5, topK = 5, query = query).size }
            shaped.save(ghost("late fact", "cluster-wide-save"))
            val clusters = clustering.get(10, TimeUnit.SECONDS)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            println("findClusters over 30 x 1536 with a concurrent save: $elapsedMs ms")
            assertTrue(clusters > 0)
            assertTrue(elapsedMs < 1000, "took $elapsedMs ms")
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Through Spring's own transaction advice, as the bean has it. A standalone clustering pass
     * opens no transaction of its own, so it holds no write lock while it searches, and a save made
     * while it is part way through goes straight through.
     */
    @Test
    fun `a save goes through while findClusters is part way through its searches`() {
        fun at(degrees: Double) = floatArrayOf(
            Math.cos(Math.toRadians(degrees)).toFloat(),
            Math.sin(Math.toRadians(degrees)).toFloat(),
        )
        val vectors = mapOf("q0 fact" to at(0.0), "q1 fact" to at(5.0), "q2 fact" to at(10.0))
        val pausing = PausingPersistenceManager(persistenceManager)
        val target = DrivinePropositionRepository(
            countingObjects(pausing),
            pausing,
            FixedEmbeddingService(vectors, FakeEmbeddingService()),
            transactionManager,
        )
        vectors.keys.forEach { target.save(ghost(it, "cluster-paused", id = it.substringBefore(' '))) }
        val clustering = advised(target)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val clusters = executor.submit<Int> {
                clustering.findClusters(similarityThreshold = 0.9, topK = 2, query = PropositionQuery.forContextId(ContextId("cluster-paused"))).size
            }
            assertTrue(pausing.inside.await(10, TimeUnit.SECONDS), "findClusters never reached its searches")

            val save = executor.submit<Proposition> { repository.save(ghost("a fact saved meanwhile", "cluster-paused-save")) }

            assertEquals(ContextId("cluster-paused-save"), save.get(5, TimeUnit.SECONDS).contextId)
            pausing.release.countDown()
            assertTrue(clusters.get(10, TimeUnit.SECONDS) > 0)
        } finally {
            pausing.release.countDown()
            executor.shutdown()
            executor.awaitTermination(60, TimeUnit.SECONDS)
        }
    }

    /** [target] behind Spring's transaction advice, read from its annotations as the context reads them. */
    private fun advised(target: DrivinePropositionRepository): DrivinePropositionRepository =
        ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvice(TransactionInterceptor(transactionManager as TransactionManager, AnnotationTransactionAttributeSource()))
        }.proxy as DrivinePropositionRepository

    /** Drivine's object manager over [persistence], made the way the context makes its own. */
    private fun countingObjects(persistence: PersistenceManager): StatelessGraphObjectManager {
        val objects = ReflectionTestUtils.getField(graphObjectManager, "objects")!!
        return StatelessGraphObjectManager(
            persistence,
            ReflectionTestUtils.getField(graphObjectManager, "objectMapper") as ObjectMapper,
            ReflectionTestUtils.getField(objects, "subtypeRegistry") as SubtypeRegistry,
        )
    }

    private fun ghost(text: String, context: String, id: String? = null): Proposition {
        val proposition = Proposition(
            contextId = ContextId(context),
            text = text,
            mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )
        return if (id == null) proposition else proposition.copy(id = id)
    }

    private fun <T> runWithin(seconds: Long, block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return executor.submit<T> { block() }.get(seconds, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
            executor.awaitTermination(60, TimeUnit.SECONDS)
        }
    }
}

/** Gives the listed texts the listed vectors, padded with zeros, and [fallback]'s for the rest. */
private class FixedEmbeddingService(
    private val vectors: Map<String, FloatArray>,
    private val fallback: EmbeddingService,
) : EmbeddingService by fallback {
    override fun embed(text: String): FloatArray =
        vectors[text]?.copyOf(fallback.dimensions) ?: fallback.embed(text)

    override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)
}

/** Passes every call on, counting the seeds a search among a set measures, one row each. */
private class RowCountingPersistenceManager(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    var seedRows = 0

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val rows = delegate.query(spec)
        seedRows += rows.count { it is Map<*, *> && it.containsKey("seed") && it.containsKey("near") }
        return rows
    }
}

/** Whether [rows] are what a vector search returns: scored rows, or each seed's scored neighbours. */
private fun searched(rows: List<*>): Boolean =
    rows.any { it is Map<*, *> && ((it.containsKey("score") && it.containsKey("value")) || it.containsKey("near")) }

/** Passes every call on, and runs [then] once, after the first vector search has answered. */
private class AfterFirstSearchPersistenceManager(
    private val delegate: PersistenceManager,
    private val then: () -> Unit,
) : PersistenceManager by delegate {
    var ran = false

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val rows = delegate.query(spec)
        if (!ran && searched(rows)) {
            ran = true
            then()
        }
        return rows
    }
}

/** Passes every call on. The first vector search to return rows waits for [release] before it answers. */
private class PausingPersistenceManager(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    val inside = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val rows = delegate.query(spec)
        if (inside.count > 0 && searched(rows)) {
            inside.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        return rows
    }
}
