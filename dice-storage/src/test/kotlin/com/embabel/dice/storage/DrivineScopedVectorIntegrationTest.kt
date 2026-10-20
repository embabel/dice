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
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.proposition.store.ScopedReadRules
import com.embabel.dice.temporal.TemporalMetadata
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import kotlin.test.*

@SpringBootTest(classes = [TestApplication::class])
class DrivineScopedVectorIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired private lateinit var repository: DrivinePropositionRepository
    @Autowired private lateinit var manager: PersistenceManager
    @Autowired private lateinit var graph: StatelessGraphObjectManager
    @Autowired private lateinit var registry: DatabaseRegistry
    @Autowired private lateinit var sources: DataSourceMap
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val at = Instant.parse("2026-10-15T12:00:00.000000123Z")
    private val scope = ContextReadScope.ofValues(listOf("vectors-A", "vectors-B"))
    private val budget = ExactVectorBudget(100, Duration.ofSeconds(20))
    private val unit = FloatArray(16) { if (it == 0) 1f else 0f }
    private val vectors = mutableMapOf<String, FloatArray>()
    private val embedder = object : EmbeddingService by FakeEmbeddingService() {
        override fun embed(text: String): FloatArray = vectors[text] ?: unit
    }
    private fun executor() = Neo4jScopedReadExecutor.from(manager, registry, sources)
    private fun manual(service: EmbeddingService = embedder) =
        DrivinePropositionRepository(graph, manager, service, transactionManager, "retained-test-index")
    private fun native() = manual().withNativeReadExecutor(executor())
    private fun spec() = ScopedReadSpec.of(scope)
    private val clock = ReadClock(at, 2.0)
    private fun request(k: Int = 10, threshold: Double = 0.0) =
        TextSimilaritySearchRequest(query = "query", topK = k, similarityThreshold = threshold)
    private fun proposition(id: String, context: String = "vectors-A") = Proposition(
        id = id, text = "text $id $context", contextId = ContextId(context), confidence = 0.9,
        decay = 0.1, created = at.minusSeconds(864000), contentRevised = at.minusSeconds(172800),
        metadataRevised = at.minusSeconds(259200), lastAccessed = at.minusSeconds(86400),
        mentions = listOf(EntityMention("one", "fixture", "entity-1"), EntityMention("two", "fixture", "entity-2")),
        level = 2, sourceIds = listOf("source"), reinforceCount = 3, importance = 0.6, metadata = mapOf("dice.trust.score" to 0.8),
    )
    private fun write(statement: String, parameters: Map<String, Any?> = emptyMap()) {
        manager.execute(QuerySpecification.withStatement(statement).bind(parameters))
    }
    private fun seed(p: Proposition, vector: FloatArray = unit) {
        vectors[p.text] = vector
        repository.save(p)
        write("MATCH (p:Proposition {id: ${'$'}id}) SET p.embedding = ${'$'}vector",
            mapOf("id" to p.id, "vector" to vector.toList()))
    }

    @AfterEach fun clean() { repository.clearAll(); vectors.clear() }

    @Test
    fun `equal scores fall back to context then id in code point order before K`() {
        // U+FF61 sorts before U+10000 by code point but after it in UTF-16, where U+10000 is U+D800 U+DC00.
        listOf(proposition("𐀀", "｡"), proposition("｡", "｡"), proposition("c1", "𐀀"), proposition("c2"))
            .forEach { seed(it) }
        val tied = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("vectors-A", "｡", "𐀀")))
        // UTF-16 order would give c2, c1, 𐀀, ｡.
        val expected = listOf("c2", "｡", "𐀀", "c1")
        listOf(1, 2, 4).forEach { k ->
            assertEquals(expected.take(k), native().findSimilarWithScores(request(k), tied, clock, budget).map { it.match.id }, "k=$k")
        }
    }

    @Test
    fun globalKPrefilterAndAdmission() {
        repeat(8) { seed(proposition("forbidden-$it", "vectors-C")) }
        repeat(8) { seed(proposition("hidden-$it", "vectors-B")) }
        val eligible = proposition("eligible").copy(mentions = List(5) { EntityMention("m$it", "fixture", "entity-1") })
        seed(eligible, FloatArray(16) { if (it < 2) 1f else 0f })
        val criteria = spec().includingOnly(listOf(PropositionKey("vectors-A", eligible.id)))
            .restrict(PropositionQuery(entityId = "entity-1"))
        val hits = native().findSimilarWithScores(request(1), criteria, clock, ExactVectorBudget(1, Duration.ofSeconds(20)))
        assertEquals(listOf(eligible), hits.map { it.match })
        assertEquals(1 / kotlin.math.sqrt(2.0), hits.single().score, 1e-6)
        assertEquals(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED, assertFailsWith<ScopedReadException> {
            native().findSimilarWithScores(request(1), spec(), clock, ExactVectorBudget(1, Duration.ofSeconds(20)))
        }.failure)
    }

    @Test
    fun `missing embeddings consume admission and overflow scores no malformed prefix`() {
        seed(proposition("a")); seed(proposition("missing")); seed(proposition("bad"))
        write("MATCH (p:Proposition {id: 'missing'}) REMOVE p.embedding")
        write("MATCH (p:Proposition {id: 'bad'}) SET p.embedding = 'private malformed vector'")
        assertEquals(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED, assertFailsWith<ScopedReadException> {
            native().findSimilarWithScores(request(1), spec(), clock, ExactVectorBudget(2, Duration.ofSeconds(20)))
        }.failure)
        assertEquals(listOf("a"), native().findSimilarWithScores(request(1), spec(), clock, budget).map { it.match.id })
        repository.delete("bad")
        assertEquals(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED, assertFailsWith<ScopedReadException> {
            native().findSimilarWithScores(request(), spec(), clock, ExactVectorBudget(1, Duration.ofSeconds(20)))
        }.failure)
        assertEquals(listOf("a"), native().findSimilarWithScores(request(), spec(), clock, budget).map { it.match.id })
    }

    @Test
    fun `raw cosine zero and threshold ties, and corrupt embeddings are skipped`() {
        val fixtures = listOf("identical" to unit, "orthogonal" to FloatArray(16) { if (it == 1) 1f else 0f },
            "opposite" to unit.map { -it }.toFloatArray(), "zero" to FloatArray(16))
        fixtures.forEach { (id, vector) -> seed(proposition(id), vector) }
        seed(proposition("identical-B", "vectors-B"))
        val all = native().findSimilarWithScores(request(), spec(), clock, budget)
        assertEquals(listOf("identical", "identical-B", "orthogonal", "zero"), all.map { it.match.id })
        assertEquals(listOf(1.0, 1.0, 0.0, 0.0), all.map { it.score })
        assertEquals(listOf("identical"), native().findSimilarWithScores(request(1, 0.5), spec(), clock, budget).map { it.match.id })
        val (rawStatement, rawParameters) = DrivineScopedVectorSearch.statement(spec(), clock, request(), budget, unit.toList())
        val rawScores = executor().read(Duration.ofSeconds(20)) { tx ->
            tx.run(rawStatement, rawParameters + ("threshold" to -1.0)).single()["results"].asList {
                it["p"].asNode()["id"].asString() to it["score"].asDouble()
            }.toMap()
        }
        assertEquals(-1.0, rawScores.getValue("opposite"))
        vectors["query"] = FloatArray(16)
        assertEquals(listOf("identical", "opposite", "orthogonal", "zero", "identical-B"),
            native().findSimilarWithScores(request(), spec(), clock, budget).map { it.match.id })
        vectors.remove("query")
        // A short, NaN or out-of-range embedding is skipped: the search answers as if the row were gone.
        val corrupt = listOf("[1.0]", "[i IN range(0, 15) | CASE WHEN i = 0 THEN 0.0 / 0.0 ELSE 0.0 END]",
            "[i IN range(0, 15) | CASE WHEN i = 0 THEN 1.0e100 ELSE 0.0 END]")
        val withCorrupt = corrupt.map { embedding ->
            write("MATCH (p:Proposition {id: 'opposite'}) SET p.embedding = $embedding")
            embedding to native().findSimilarWithScores(request(), spec(), clock, budget).map { it.match.id to it.score }
        }
        repository.delete("opposite")
        val withoutCorrupt = native().findSimilarWithScores(request(), spec(), clock, budget).map { it.match.id to it.score }
        withCorrupt.forEach { (embedding, results) -> assertEquals(withoutCorrupt, results, embedding) }
    }

    @Test
    fun `every filter and custom temporal confidence matches resident oracle before K`() {
        val first = proposition("a")
        val fixtures = listOf(first, first.copy(id = "b", contextId = ContextId("vectors-B"), text = "text b", pinned = true),
            first.copy(id = "c", contextId = ContextId("vectors-C"), text = "text c", confidence = 1.0),
            first.copy(id = "stale", text = "stale", status = PropositionStatus.STALE, mentions = emptyList()),
            first.copy(id = "old", text = "old", decay = 0.9, level = 0, importance = 0.3, reinforceCount = 0),
            first.copy(id = "fresh", text = "fresh", lastAccessed = at, metadata = mapOf("dice.trust.score" to 0.1)),
            first.copy(id = "end-only", text = "end-only", temporal = TemporalMetadata(validTo = at.minusSeconds(1))),
            first.copy(id = "closed", text = "closed", temporal = TemporalMetadata(validFrom = at.minusSeconds(86400), validTo = at)),
            first.copy(id = "open", text = "open", temporal = TemporalMetadata(validFrom = at.minusSeconds(172800))),
            first.copy(id = "invalid", text = "invalid", temporal = TemporalMetadata(invalidatedAt = at)),
            first.copy(id = "day-edge", text = "day-edge", lastAccessed = at.minusSeconds(86400).plusNanos(1)))
        val local = InMemoryPropositionRepository(embedder)
        fixtures.forEachIndexed { index, p ->
            seed(p, FloatArray(16) { if (it == 0) 1f else if (it == 1) index / 10f else 0f })
            local.save(p)
        }
        val filters = listOf(PropositionQuery(), PropositionQuery(contextId = first.contextId),
            PropositionQuery(entityId = "entity-1"), PropositionQuery(anyEntityIds = listOf("entity-2")),
            PropositionQuery(allEntityIds = listOf("entity-1", "entity-2", "entity-1")),
            PropositionQuery(anyEntityIds = emptyList()), PropositionQuery(allEntityIds = emptyList()),
            PropositionQuery(statuses = setOf(PropositionStatus.ACTIVE)), PropositionQuery(statuses = emptySet()),
            PropositionQuery(minLevel = 2), PropositionQuery(maxLevel = 0),
            PropositionQuery(createdAfter = first.created), PropositionQuery(createdBefore = first.created.minusNanos(1)),
            PropositionQuery(revisedAfter = first.lastTouched), PropositionQuery(revisedBefore = first.lastTouched),
            PropositionQuery(accessedAfter = first.lastAccessed.plusNanos(1)), PropositionQuery(accessedBefore = first.lastAccessed),
            PropositionQuery(minConfidence = 0.95), PropositionQuery(minImportance = 0.5),
            PropositionQuery(minReinforceCount = 2), PropositionQuery(pinned = true), PropositionQuery(minTrustScore = 0.8)) +
            listOf(0.0, 0.5, 2.0, 7.0).flatMap { k -> listOf(
                PropositionQuery(minEffectiveConfidence = 0.75, decayK = k),
                PropositionQuery(belowEffectiveConfidence = 0.75, decayK = k)) }
        filters.forEach { filter -> listOf(at, at.plusSeconds(86400)).forEach { instant ->
            val criteria = ScopedReadSpec.of(scope).restrict(filter)
            val readClock = ReadClock(instant, 2.0)
            listOf(1, 3, 100).forEach { k ->
                val expected = local.findSimilarWithScores(request(k), criteria, readClock, budget)
                val actual = native().findSimilarWithScores(request(k), criteria, readClock, budget)
                assertEquals(expected.map { it.match }, actual.map { it.match }, "$filter at $instant k=$k")
                expected.zip(actual).forEach { (a, b) -> assertEquals(a.score, b.score, 1e-6) }
            }
        } }
        val criteria = spec().restrict(PropositionQuery(entityId = "entity-1"))
            .restrict(PropositionQuery(pinned = false)).excluding(listOf(PropositionKey("vectors-A", "a")))
        assertEquals(local.findSimilarWithScores(request(), criteria, clock, budget).map { it.match },
            native().findSimilarWithScores(request(), criteria, clock, budget).map { it.match })
    }

    @Test
    fun `legacy default and live decay retain executor and budget in either wither order`() {
        seed(proposition("allowed").copy(decay = 0.0))
        repeat(6) { seed(proposition("forbidden-$it", "vectors-C")) }
        val query = PropositionQuery.forContextId(ContextId("vectors-A"))
        val customBudget = ExactVectorBudget(1, Duration.ofSeconds(20))
        val configured = listOf(manual().withExactVectorBudget(customBudget).withNativeReadExecutor(executor()),
            manual().withNativeReadExecutor(executor()).withExactVectorBudget(customBudget))
        configured.forEach { repo -> listOf(query, query.copy(minEffectiveConfidence = 0.1,
            effectiveConfidenceAsOf = at.plusSeconds(86400), decayK = 7.0)).forEach { filter ->
            assertEquals(listOf("allowed"), repo.findSimilarWithScores(request(1), filter).map { it.match.id })
        } }
        assertFalse(manual().supportsScopedVectors)
        assertEquals(listOf("allowed"), manual().findSimilarWithScores(request(1), PropositionQuery()).map { it.match.id })
        assertEquals(listOf("allowed"), native().findSimilarWithScores(request(1), PropositionQuery()).map { it.match.id })
        seed(proposition("overflow").copy(decay = 0.0))
        configured.forEach { repo -> listOf(query, query.copy(minEffectiveConfidence = 0.1,
            effectiveConfidenceAsOf = at, decayK = 7.0)).forEach { filter ->
            val failure = assertFailsWith<ScopedReadException> { repo.findSimilarWithScores(request(1), filter) }
            assertEquals(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED, failure.failure)
        } }
    }

    @Test
    fun `an executorless store reports scoped vectors unsupported`() {
        val failure = assertFailsWith<ScopedReadException> {
            manual().findSimilarWithScores(request(), ScopedReadSpec.of(scope), ReadClock(at, 2.0), budget)
        }
        assertEquals(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED, failure.failure)
    }

    @Test
    fun `the native statement never uses the vector index and returns K rows`() {
        seed(proposition("a")); seed(proposition("b"))
        val (statement, parameters) = DrivineScopedVectorSearch.statement(spec(), clock, request(1), budget, unit.toList())
        assertFalse(statement.contains("db.index.vector"))
        executor().read(Duration.ofSeconds(20)) { tx ->
            assertEquals(1, tx.run(statement, parameters).single()["results"].size())
        }
    }

    @Test
    fun closeThresholdAndTieMatchDoubleOracle() {
        val first = proposition("z-high")
        val second = proposition("a-low")
        seed(first, FloatArray(16) { if (it < 2) 1f else 0f })
        seed(second, FloatArray(16) { if (it == 0) 1f else if (it == 1) Math.nextUp(1f) else 0f })
        val local = InMemoryPropositionRepository(embedder)
        local.save(first); local.save(second)
        val expected = local.findSimilarWithScores(request(), spec(), clock, budget)
        val actual = native().findSimilarWithScores(request(), spec(), clock, budget)
        val high = expected.first { it.match.id == first.id }.score
        val nativeHigh = actual.first { it.match.id == first.id }.score
        val midpoint = (high + nativeHigh) / 2.0
        listOf(midpoint, high, Math.nextDown(high), Math.nextUp(high)).forEach { threshold ->
            val wanted = local.findSimilarWithScores(request(10, threshold), spec(), clock, budget)
            val found = native().findSimilarWithScores(request(10, threshold), spec(), clock, budget)
            assertEquals(wanted.map { it.match.id }, found.map { it.match.id }, "threshold=$threshold")
        }
        assertEquals(expected.map { it.match.id }, actual.map { it.match.id })
        val exact = proposition("z-exact")
        val close = proposition("a-close")
        seed(exact)
        seed(close, FloatArray(16) { if (it == 0) 1f else if (it == 1) 0.0001f else 0f })
        local.save(exact); local.save(close)
        val tieSpec = spec().includingOnly(listOf(PropositionKey("vectors-A", exact.id),
            PropositionKey("vectors-A", close.id)))
        val ordered = local.findSimilarWithScores(request(), tieSpec, clock, budget)
        val ranked = native().findSimilarWithScores(request(), tieSpec, clock, budget)
        assertEquals(listOf("z-exact", "a-close"), ranked.map { it.match.id })
        assertEquals(ordered.map { it.score }, ranked.map { it.score })
        listOf(Float.MAX_VALUE, Float.MIN_VALUE).forEachIndexed { index, magnitude ->
            val extreme = proposition("finite-float-$index")
            seed(extreme, FloatArray(16) { if (it == 0) magnitude else 0f })
            local.save(extreme)
            val extremeSpec = spec().includingOnly(listOf(PropositionKey("vectors-A", extreme.id)))
            assertEquals(local.findSimilarWithScores(request(), extremeSpec, clock, budget).single().score,
                native().findSimilarWithScores(request(), extremeSpec, clock, budget).single().score)
        }
    }

    @Test
    fun `a query vector longer than one scores exactly as the in-memory store does`() {
        // Every other case here uses a unit-length query, which would hide a query norm computed wrongly.
        val query = FloatArray(16) { (if (it % 3 == 0) -3.7f else 3.7f) * (it + 1) / 16f }
        vectors["query"] = query
        val stored = mapOf(
            "same-way" to query.map { it / 3.7f }.toFloatArray(),
            "mixed" to FloatArray(16) { if (it % 2 == 0) 0.25f * (it + 1) else -1.5f },
            "opposite" to query.map { -2f * it }.toFloatArray(),
            "sparse" to FloatArray(16) { if (it == 4) 9f else if (it == 9) -0.5f else 0f },
        )
        val local = InMemoryPropositionRepository(embedder)
        stored.forEach { (id, vector) -> proposition(id).also { seed(it, vector); local.save(it) } }
        val found = native().findSimilarWithScores(request(threshold = 0.0), spec(), clock, budget)
        assertEquals(local.findSimilarWithScores(request(), spec(), clock, budget).map { it.match.id }, found.map { it.match.id })
        found.forEach { result ->
            assertEquals(ScopedReadRules.cosineSimilarity(query, stored.getValue(result.match.id)), result.score, result.match.id)
        }
        val (statement, parameters) = DrivineScopedVectorSearch.statement(spec(), clock, request(), budget, query.toList())
        val raw = executor().read(Duration.ofSeconds(20)) { tx ->
            tx.run(statement, parameters + ("threshold" to -1.0)).single()["results"].asList {
                it["p"].asNode()["id"].asString() to it["score"].asDouble()
            }.toMap()
        }
        stored.forEach { (id, vector) -> assertEquals(ScopedReadRules.cosineSimilarity(query, vector), raw.getValue(id), id) }
    }

    @Test
    fun `E1 the embedder runs before the read transaction opens`() {
        seed(proposition("a"))
        // Inside the read, the executor would turn this into EXACT_VECTOR_DEADLINE_EXCEEDED, so getting
        // the same instance back proves embedding happens before the server's timeout starts.
        val timedOut = org.neo4j.driver.exceptions.ClientException("Neo.ClientError.Transaction.TransactionTimedOut", "embedder")
        val failing = object : EmbeddingService by embedder {
            override fun embed(text: String): FloatArray = throw timedOut
        }
        val thrown = assertFails {
            manual(failing).withNativeReadExecutor(executor()).findSimilarWithScores(request(), spec(), clock, budget)
        }
        assertSame(timedOut, thrown)
        assertEquals(listOf("a"), native().findSimilarWithScores(request(), spec(), clock,
            ExactVectorBudget(10, Duration.ofSeconds(1))).map { it.match.id })
        vectors["query"] = floatArrayOf(Float.NaN)
        assertFailsWith<IllegalArgumentException> { native().findSimilarWithScores(request(), spec(), clock, budget) }
        assertEquals(emptyList(), native().findSimilarWithScores(request(0), spec(), clock, budget))
    }
}
