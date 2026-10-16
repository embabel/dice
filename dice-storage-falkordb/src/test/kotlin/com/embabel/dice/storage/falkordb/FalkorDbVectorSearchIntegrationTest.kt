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
package com.embabel.dice.storage.falkordb

import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.github.dockerjava.api.model.ExposedPort
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.ConsecutiveFailureBreaker
import com.embabel.dice.proposition.ContextSetVectorSearchCapable
import com.embabel.dice.proposition.DecaySweepConfig
import com.embabel.dice.proposition.DecaySweepResult
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionReembedIncompleteException
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.StrictPolicyReadCapable
import com.embabel.dice.proposition.store.InMemoryDecayManager
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.NativeSchemaCommandExecutor
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class FalkorDbVectorSearchIntegrationTest {
    private lateinit var provider: FalkorDbConnectionProvider
    private lateinit var repository: FalkorDbPropositionRepository
    private lateinit var model: SyntheticEmbeddingService
    private val now = Instant.parse("2026-04-05T06:07:08Z")

    private class SyntheticEmbeddingService : EmbeddingService {
        override val name = "deterministic-test"
        override val provider = "synthetic"
        override val pricingModel: PricingModel? = null
        override var dimensions = 2
        val vectors = mutableMapOf<String, FloatArray>()
        val failures = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        override fun embed(text: String): FloatArray {
            calls += text
            check(text !in failures) { "Synthetic embedding refused" }
            return vectors[text] ?: FloatArray(dimensions) { if (it == 0) 1f else 0f }
        }
        override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)
    }

    @BeforeEach
    fun setUp() {
        provider = server.provider("vectors_${UUID.randomUUID().toString().replace("-", "")}")
        model = SyntheticEmbeddingService()
        repository = FalkorDbPropositionRepository(provider).withEmbeddingService(model)
    }

    @AfterEach
    fun closeProvider() = provider.end()

    private fun proposition(id: String, context: String = "a") = Proposition(
        id = id, contextId = ContextId(context), text = id, mentions = emptyList(), confidence = 0.9,
        created = now, contentRevised = now, metadataRevised = now, lastAccessed = now, decay = 0.0,
    )

    private fun request(text: String = "query", threshold: Double = 0.0, topK: Int = 20) =
        TextSimilaritySearchRequest(text, threshold, topK)

    private fun intercept(before: (QuerySpecification<*>) -> Unit): ConnectionProvider =
        object : ConnectionProvider by provider, NativeSchemaCommandExecutor by provider {
            override fun connect(): Connection {
                val delegate = provider.connect()
                return object : Connection by delegate {
                    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
                        before(spec)
                        return delegate.query(spec)
                    }
                }
            }
        }

    @Test
    fun `portable decay sweep persists stale status and retains vectors across reconnect`() {
        val fact = proposition("decay-candidate").copy(
            confidence = 0.1, decay = 0.99, created = Instant.EPOCH,
            contentRevised = Instant.EPOCH, lastAccessed = Instant.EPOCH,
        )
        repository.save(fact)
        val result = InMemoryDecayManager(FalkorDbPropositionRepository(provider)).sweepAll(
            DecaySweepConfig(),
        )
        assertTrue(result is DecaySweepResult.Swept)
        assertEquals(listOf(fact.id), result.transitioned.map { it.id })
        val reconnected = FalkorDbPropositionRepository(provider).withEmbeddingService(model)
        val stale = reconnected.findById(fact.id)!!
        assertEquals(PropositionStatus.STALE, stale.status)
        assertEquals(fact.contentRevised, stale.contentRevised)
        val matches = reconnected.findSimilarWithScores(request(),
            PropositionQuery.againstContext("a").copy(statuses = setOf(PropositionStatus.STALE)))
        assertEquals(listOf(fact.id), matches.map { it.match.id })
        assertEquals(1.0, matches.single().score)
    }

    @Test
    fun `native cosine matches portable scores thresholds and Unicode tie order`() {
        val oracle = InMemoryPropositionRepository(model)
        model.vectors += mapOf(
            "same" to floatArrayOf(1f, 0f), "orthogonal" to floatArrayOf(0f, 1f),
            "opposite" to floatArrayOf(-1f, 0f), "zero" to floatArrayOf(0f, 0f),
            "boundary" to floatArrayOf(0.1f, 0.3f), "huge" to floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE),
            "tiny" to floatArrayOf(Float.MIN_VALUE, Float.MIN_VALUE),
        )
        val rows = model.vectors.keys.map { proposition(it) } +
            listOf(proposition("𐀀", "𐀀"), proposition("\uE000", "𐀀"), proposition("a", "\uE000"))
        rows.forEach { repository.save(it); oracle.save(it) }
        val threshold = oracle.findSimilarWithScores(request()).single { it.match.id == "boundary" }.score
        for (cutoff in listOf(-1.0, 0.0, threshold, Math.nextUp(threshold), 1.0)) {
            for (topK in listOf(0, 1, 20)) {
                val query = request(threshold = cutoff, topK = topK)
                val expected = oracle.findSimilarWithScores(query)
                val actual = repository.findSimilarWithScores(query)
                assertEquals(expected.map { it.match.id }, actual.map { it.match.id }, "cutoff=$cutoff topK=$topK")
                expected.zip(actual).forEach { (a, b) -> assertEquals(a.score, b.score) }
                assertTrue(actual.all { it.score >= cutoff }, "returned scores honor the requested threshold")
            }
        }
        val all = repository.findSimilarWithScores(request(threshold = -1.0)).associate { it.match.id to it.score }
        assertEquals(1.0, all["same"])
        assertEquals(0.0, all["orthogonal"])
        assertEquals(0.0, all["zero"])
        assertEquals(-1.0, all["opposite"])
        model.vectors["query"] = floatArrayOf(0f, 0f)
        assertTrue(repository.findSimilarWithScores(request()).all { it.score == 0.0 })
    }

    @Test
    fun `all structured predicates run before native ranking and top-K`() {
        val oracle = InMemoryPropositionRepository(model)
        val rows = (0..11).map { i ->
            model.vectors["fact-$i"] = floatArrayOf(1f, i / 5f)
            proposition("fact-$i", if (i < 3) "outside" else "a").copy(
                confidence = i / 12.0, importance = i / 12.0, level = i % 4, reinforceCount = i, sourceIds = listOf("source"),
                mentions = if (i % 2 == 0) listOf(EntityMention("entity", "Thing", "e")) else emptyList(),
                status = if (i % 2 == 0) PropositionStatus.ACTIVE else PropositionStatus.SUPERSEDED,
                pinned = i % 2 == 0, metadata = mapOf(DiceMetadataKeys.TRUST_SCORE to i / 12.0),
                created = now.minusSeconds(i.toLong()), contentRevised = now.minusSeconds(i.toLong()),
                metadataRevised = now.minusSeconds(i.toLong()), lastAccessed = now.minusSeconds(i.toLong()),
            )
        }
        rows.forEach { repository.save(it); oracle.save(it) }
        val queries = listOf(
            PropositionQuery.againstContext("a"), PropositionQuery(entityId = "e"),
            PropositionQuery(anyEntityIds = listOf("e")), PropositionQuery(allEntityIds = listOf("e")),
            PropositionQuery(anyEntityIds = emptyList()), PropositionQuery(allEntityIds = emptyList()),
            PropositionQuery(statuses = setOf(PropositionStatus.SUPERSEDED)), PropositionQuery(minLevel = 3),
            PropositionQuery(maxLevel = 0), PropositionQuery(minConfidence = 0.7),
            PropositionQuery(minEffectiveConfidence = 0.7), PropositionQuery(belowEffectiveConfidence = 0.3),
            PropositionQuery(minImportance = 0.7), PropositionQuery(minReinforceCount = 9),
            PropositionQuery(minTrustScore = 0.7), PropositionQuery(pinned = false),
            PropositionQuery(createdBefore = now.minusSeconds(8)), PropositionQuery(createdAfter = now.minusSeconds(8)),
            PropositionQuery(revisedBefore = now.minusSeconds(8)), PropositionQuery(revisedAfter = now.minusSeconds(8)),
            PropositionQuery(accessedBefore = now.minusSeconds(8)), PropositionQuery(accessedAfter = now.minusSeconds(8)),
            PropositionQuery.againstContext("a").copy(statuses = setOf(PropositionStatus.ACTIVE), minConfidence = 0.5),
            PropositionQuery.againstContext("a").copy(limit = 0),
        )
        for ((index, q) in queries.withIndex()) {
            val filter = q.copy(effectiveConfidenceAsOf = now)
            val query = request(topK = 1)
            val expected = oracle.findSimilarWithScores(query, filter)
            val actual = repository.findSimilarWithScores(query, filter)
            assertEquals(expected.map { it.match }, actual.map { it.match }, "filter $index")
            expected.zip(actual).forEach { (a, b) -> assertEquals(a.score, b.score) }
        }
        val scores = oracle.findSimilarWithScores(request()).map { it.score }.distinct()
        for (cutoff in scores.flatMap { listOf(it, Math.nextUp(it)) }) {
            val query = request(threshold = cutoff)
            val actual = repository.findSimilarWithScores(query)
            assertEquals(oracle.findSimilarWithScores(query), actual, "threshold=$cutoff")
            assertTrue(actual.all { it.score >= cutoff }, "returned scores honor threshold=$cutoff")
        }
        val global = repository.findSimilarWithScores(request(topK = 1))
        assertTrue(global.all { it.match.contextId.value == "outside" })
        assertTrue(global.filter { it.match.contextId.value == "a" }.isEmpty(), "post-filtering loses the eligible hit")
        assertEquals("fact-3", repository.findSimilarWithScores(request(topK = 1), PropositionQuery.againstContext("a")).single().match.id)
    }

    @Test
    fun `native vector reads hydrate only top-K in one server statement`() {
        repeat(10) { repository.save(proposition("p$it")) }
        var observing = false
        var calls = 0
        val native = FalkorDbPropositionRepository(intercept { spec ->
            if (observing) {
                calls++
                val statement = spec.statement!!.text
                assertTrue(statement.contains("reduce(dot"))
                assertTrue(statement.indexOf("p.context =") < statement.indexOf("reduce(dot"))
                assertTrue(statement.contains("ORDER BY score DESC"))
                assertTrue(statement.contains("LIMIT"))
                assertFalse(statement.contains("db.idx.vector"))
            }
        }, embeddingService = model)
        observing = true
        assertEquals(2, native.findSimilarWithScores(request(topK = 2), PropositionQuery.againstContext("a")).size)
        assertEquals(1, calls)
    }

    @Test
    fun `invalid embedded inputs fail before writes and malformed persisted vectors are excluded`() {
        val original = repository.save(proposition("stable"))
        for (bad in listOf(floatArrayOf(), floatArrayOf(Float.NaN, 0f), floatArrayOf(Float.POSITIVE_INFINITY, 0f))) {
            model.vectors["bad"] = bad
            assertThrows<IllegalArgumentException> { repository.save(original.copy(text = "bad")) }
            assertEquals(original, repository.findById(original.id))
            assertThrows<IllegalArgumentException> { repository.findSimilarWithScores(request("bad")) }
        }
        val access = FalkorDbAccess(provider, null)
        val malformed = listOf<Any?>(null, emptyList<Double>(), listOf(1.0), listOf("bad", "data"), "bad")
        malformed.forEachIndexed { i, vector ->
            repository.save(proposition("bad-$i"))
            access.write("MATCH (p:DiceProposition {id: $" + "id}) SET p.embedding = $" + "vector RETURN {value:1} AS result",
                mapOf("id" to "bad-$i", "vector" to vector))
        }
        for ((index, expression) in listOf("exp(1000.0)", "sqrt(-1.0)").withIndex()) {
            FalkorDbPropositionRepository(provider).save(proposition("nonfinite-$index"))
            access.write("MATCH (p:DiceProposition {id: $" + "id}) SET p.embedding = [1.0, $expression] RETURN {value:1} AS result",
                mapOf("id" to "nonfinite-$index"))
        }
        assertTrue(access.query("MATCH (p:DiceProposition {id:'nonfinite-1'}) " +
            "RETURN {value:toLower(toString(p.embedding[1]))} AS result").single()["value"] in setOf("nan", "-nan"))
        assertEquals(listOf("stable"), repository.findSimilarWithScores(request()).map { it.match.id })
        assertEquals(1 + malformed.size + 2, repository.count())
        assertThrows<IllegalArgumentException> { repository.findSimilarWithScores(request(topK = -1)) }
    }

    @Test
    fun `embedding or native write failures preserve aggregate and vector together`() {
        val original = repository.save(proposition("stable"))
        model.failures += "refused"
        assertThrows<IllegalStateException> { repository.save(original.copy(text = "refused")) }
        assertThrows<IllegalStateException> { repository.save(proposition("refused")) }
        assertEquals(1, repository.count())
        assertEquals(original, repository.findById(original.id))
        val armed = AtomicBoolean(true)
        val broken = FalkorDbPropositionRepository(intercept { spec ->
            if (spec.statement!!.text.contains("AS accepted") && armed.compareAndSet(true, false)) {
                val properties = spec.parameters["properties"] as Map<*, *>
                spec.bind(spec.parameters + ("properties" to (properties + ("bad" to mapOf("nested" to true)))))
            }
        }, embeddingService = model)
        model.vectors["changed"] = floatArrayOf(0f, 1f)
        assertThrows<RuntimeException> { broken.save(original.copy(text = "changed")) }
        assertEquals(original, repository.findById(original.id))
        assertEquals(1.0, repository.findSimilarWithScores(request()).single().score)
    }

    @Test
    fun `metadata writes preserve vectors while an unconfigured text replacement invalidates them`() {
        val original = repository.save(proposition("stable"))
        val plain = FalkorDbPropositionRepository(provider)
        model.failures += original.text
        plain.pin(original.id)
        plain.addProvenance(original.id, listOf(ProvenanceEntry(UriLocator("https://example.test/evidence"))))
        plain.touchAccessed(listOf(original.id))
        assertEquals(1.0, repository.findSimilarWithScores(request()).single().score)
        plain.save(assertNotNull(plain.findById(original.id)))
        assertEquals(1, repository.findSimilarWithScores(request()).size)
        plain.save(original.copy(text = "new text"))
        assertTrue(repository.findSimilarWithScores(request()).isEmpty())
        assertFalse(plain.supportsVector)
        assertTrue(repository.supportsVector)
        assertTrue(plain.findSimilarWithScores(request(), PropositionQuery()).isEmpty())
        assertEquals(0, plain.reembedAll().propositions)
        assertFalse(ContextSetVectorSearchCapable::class.java.isInstance(repository))
        assertFalse(StrictPolicyReadCapable::class.java.isInstance(repository))
    }

    @Test
    fun `cluster membership pair deduplication and limits precede top-K`() {
        val oracle = InMemoryPropositionRepository(model)
        val rows = (0..5).map { proposition("outside-$it", "outside") } +
            listOf("a", "b", "c").map { proposition(it) }
        rows.forEach { repository.save(it); oracle.save(it) }
        val filter = PropositionQuery.againstContext("a")
        model.failures += rows.map { it.text }
        model.calls.clear()
        val actual = repository.findClusters(0.9, 1, filter)
        assertEquals(oracle.findClusters(0.9, 1, filter), actual)
        assertEquals(listOf("b", "c"), actual.map { it.similar.single().match.id })
        assertEquals(oracle.findClusters(0.9, 1, filter.copy(limit = 2)), repository.findClusters(0.9, 1, filter.copy(limit = 2)))
        assertTrue(repository.findClusters(0.0, 0, filter).isEmpty())
        assertTrue(model.calls.isEmpty(), "clustering uses persisted anchor vectors")
        FalkorDbPropositionRepository(provider).save(proposition("unembedded"))
        assertTrue(repository.findClusters(0.9, 1, filter).none { it.anchor.id == "unembedded" })
    }

    @Test
    fun `partial reembedding continues and retry converges for same and changed dimensions`() {
        val rows = listOf("a", "b", "c").map { repository.save(proposition(it)) }
        for (dimensions in listOf(2, 3)) {
            model.dimensions = dimensions
            model.vectors.clear()
            rows.forEach { model.vectors[it.text] = FloatArray(dimensions) { i -> if (i == 1) 1f else 0f } }
            model.vectors["query"] = FloatArray(dimensions) { i -> if (i == 1) 1f else 0f }
            model.failures += "b"
            model.calls.clear()
            val failure = assertThrows<PropositionReembedIncompleteException> { repository.reembedAll() }
            assertEquals(listOf("b"), failure.failedPropositionIds)
            assertTrue(failure.notAttemptedPropositionIds.isEmpty())
            assertEquals(2, failure.reembedded)
            assertFalse(failure.report.indexRecreated)
            assertEquals(listOf("a", "b", "c"), model.calls)
            val hits = repository.findSimilarWithScores(request(threshold = 0.5)).map { it.match.id }
            assertEquals(listOf("a", "c"), hits)
            model.failures.clear()
            val retry = repository.reembedAll()
            assertEquals(3, retry.propositions)
            assertFalse(retry.indexRecreated)
            assertEquals(listOf("a", "b", "c"), repository.findSimilarWithScores(request(threshold = 1.0)).map { it.match.id })
        }
        assertEquals(rows, repository.findAll())
    }

    @Test
    fun `reembedding a dead model reports failed and not attempted IDs without data loss`() {
        val rows = (0..8).map { repository.save(proposition("fact-$it")) }
        model.failures += rows.map { it.text }
        model.calls.clear()
        val failure = assertThrows<PropositionReembedIncompleteException> { repository.reembedAll() }
        val cap = ConsecutiveFailureBreaker.DEFAULT_MAX_CONSECUTIVE_FAILURES
        assertEquals(rows.take(cap).map { it.id }, failure.failedPropositionIds)
        assertEquals(rows.drop(cap).map { it.id }, failure.notAttemptedPropositionIds)
        assertEquals(cap, model.calls.size)
        assertEquals(0, failure.reembedded)
        assertEquals(rows, repository.findAll())
        model.failures.clear()
        assertEquals(rows.size, repository.reembedAll().propositions)
    }

    @Test
    fun `reembedding retries concurrent changes without overwriting newer evidence or text`() {
        repository.save(proposition("a"))
        val armed = AtomicBoolean(true)
        val raced = FalkorDbPropositionRepository(intercept { spec ->
            if (spec.statement!!.text.contains("AS accepted") && armed.compareAndSet(true, false)) {
                repository.save(proposition("a").copy(text = "updated", pinned = true))
            }
        }, embeddingService = model)
        model.vectors["updated"] = floatArrayOf(0f, 1f)
        assertEquals(1, raced.reembedAll().propositions)
        assertEquals("updated", repository.findById("a")?.text)
        assertEquals(true, repository.findById("a")?.pinned)
        assertEquals(0.0, repository.findSimilarWithScores(request()).single().score)
    }

    @Test
    fun `vectors survive provider reconnect and server restart without calling the embedder`() {
        FalkorDbTestServer().use { owned ->
            owned.start()
            val graph = "durable"
            val originalProvider = owned.provider(graph)
            try {
                FalkorDbPropositionRepository(originalProvider, embeddingService = model).save(proposition("durable"))
                model.failures += "durable"
                val snapshot = owned.execInContainer("redis-cli", "SAVE")
                assertEquals(0, snapshot.exitCode)
                assertTrue(snapshot.stdout.contains("OK"))
                owned.dockerClient.restartContainerCmd(owned.containerId).exec()
                var ready = false
                repeat(100) {
                    if (!ready) {
                        ready = runCatching { owned.execInContainer("redis-cli", "PING").stdout.contains("PONG") }.getOrDefault(false)
                        if (!ready) Thread.sleep(100)
                    }
                }
                assertTrue(ready, "owned FalkorDB did not restart")
                // Docker can reassign an ephemeral host port on restart; inspect the live mapping.
                val port = owned.dockerClient.inspectContainerCmd(owned.containerId).exec()
                    .networkSettings.ports.bindings.getValue(ExposedPort(6379)).single().hostPortSpec.toInt()
                val reconnected = FalkorDbConnectionProvider("reconnected", owned.host, port, null, graph,
                    FalkorDbTransactionMode.WARN, SubtypeRegistry(), CypherDialect.FALKORDB)
                try {
                    val restored = FalkorDbPropositionRepository(reconnected, embeddingService = model)
                    assertEquals(1.0, restored.findSimilarWithScores(request()).single().score)
                    assertEquals("durable", restored.findAll().single().text)
                } finally {
                    reconnected.end()
                }
            } finally {
                originalProvider.end()
            }
        }
    }

    companion object {
        @Container
        @JvmField
        internal val server = FalkorDbTestServer()
    }
}
