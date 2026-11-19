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
import org.drivine.mapper.SubtypeRegistry
import org.springframework.test.util.ReflectionTestUtils
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
     * Twelve propositions all close to one another make 66 pairs above the threshold. Each anchor's
     * search is cut to two in the database, so no more than two pairs an anchor ever reach the JVM.
     */
    @Test
    fun `findClusters reads at most topK pairs an anchor from SQLite however many pass the threshold`() {
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
        counting.scoredRows = 0

        val clusters = shaped.findClusters(similarityThreshold = 0.9, topK = 2, query = PropositionQuery.forContextId(ContextId("cluster-dense")))

        assertTrue(counting.scoredRows in 1..names.size * 2, "read ${counting.scoredRows} pair rows for ${names.size} anchors")
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

/** Passes every call on, counting the scored rows vector searches return. */
private class RowCountingPersistenceManager(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    var scoredRows = 0

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val rows = delegate.query(spec)
        scoredRows += rows.count { it is Map<*, *> && it.containsKey("score") && it.containsKey("value") }
        return rows
    }
}
