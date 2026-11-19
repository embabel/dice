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
     * Twelve propositions all close to one another make 66 pairs above the threshold. The database
     * keeps each anchor's best two, so no more than two rows an anchor ever reach the JVM.
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
            graphObjectManager,
            counting,
            FixedEmbeddingService(vectors, FakeEmbeddingService()),
            transactionManager,
        )
        names.forEach { shaped.save(ghost("$it fact", "cluster-dense", id = it)) }
        counting.pairRows = 0

        val clusters = shaped.findClusters(similarityThreshold = 0.9, topK = 2, query = PropositionQuery.forContextId(ContextId("cluster-dense")))

        assertTrue(counting.pairRows in 1..names.size * 2, "read ${counting.pairRows} pair rows for ${names.size} anchors")
        assertEquals(11, clusters.size)
        clusters.forEach { cluster ->
            assertTrue(cluster.similar.size <= 2)
            assertTrue(cluster.similar.all { it.match.id > cluster.anchor.id })
        }
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

/** Passes every call on, counting the rows of each pair query. */
private class RowCountingPersistenceManager(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    var pairRows = 0

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val rows = delegate.query(spec)
        if (spec.parameters.containsKey("topK")) pairRows += rows.size
        return rows
    }
}
