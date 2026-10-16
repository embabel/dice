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
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.ConsecutiveFailureBreaker
import com.embabel.dice.proposition.DecaySweepConfig
import com.embabel.dice.proposition.DecaySweepResult
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionReembedIncompleteException
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.store.InMemoryDecayManager
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class FalkorDbVectorSearchIntegrationTest {
    private lateinit var graph: FalkorDbTestGraph
    private lateinit var repository: FalkorDbPropositionRepository
    private lateinit var plain: FalkorDbPropositionRepository
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
        graph = server.graph()
        model = SyntheticEmbeddingService()
        repository = FalkorDbPropositionRepository(graph.objects, graph.persistence, model)
        plain = FalkorDbPropositionRepository(graph.objects, graph.persistence)
    }

    @AfterEach
    fun closeGraph() = graph.close()

    private fun proposition(id: String, context: String = "a") = Proposition(
        id = id, contextId = ContextId(context), text = id, mentions = emptyList(), confidence = 0.9,
        created = now, contentRevised = now, metadataRevised = now, lastAccessed = now, decay = 0.0,
    )

    private fun request(text: String = "query", threshold: Double = 0.0, topK: Int = 20) =
        TextSimilaritySearchRequest(text, threshold, topK)

    /** FalkorDB scores in single precision, so scores agree with DICE's to about seven places. */
    private fun assertSameResults(expected: List<SimilarityResult<Proposition>>, actual: List<SimilarityResult<Proposition>>, message: String) {
        assertEquals(expected.map { it.match }, actual.map { it.match }, message)
        expected.zip(actual).forEach { (a, b) -> assertEquals(a.score, b.score, 1e-6, message) }
    }

    @Test
    fun `decay sweep persists stale status and keeps the vector`() {
        val fact = proposition("decay-candidate").copy(
            confidence = 0.1, decay = 0.99, created = Instant.EPOCH,
            contentRevised = Instant.EPOCH, lastAccessed = Instant.EPOCH,
        )
        repository.save(fact)
        val result = InMemoryDecayManager(plain).sweepAll(DecaySweepConfig())
        assertTrue(result is DecaySweepResult.Swept)
        assertEquals(listOf(fact.id), result.transitioned.map { it.id })
        assertEquals(PropositionStatus.STALE, repository.findById(fact.id)!!.status)
        val matches = repository.findSimilarWithScores(request(),
            PropositionQuery.againstContext("a").copy(statuses = setOf(PropositionStatus.STALE)))
        assertEquals(listOf(fact.id), matches.map { it.match.id })
        assertEquals(1.0, matches.single().score, 1e-6)
    }

    @Test
    fun `cosine scores and thresholds match the in-memory store`() {
        val oracle = InMemoryPropositionRepository(model)
        model.vectors += mapOf(
            "same" to floatArrayOf(1f, 0f), "orthogonal" to floatArrayOf(0f, 1f),
            "opposite" to floatArrayOf(-1f, 0f), "zero" to floatArrayOf(0f, 0f),
            "boundary" to floatArrayOf(0.1f, 0.3f),
        )
        val rows = model.vectors.keys.map { proposition(it) }
        rows.forEach { repository.save(it); oracle.save(it) }
        for (cutoff in listOf(-1.0, 0.0, 0.3, 0.99)) {
            for (topK in listOf(0, 1, 20)) {
                val query = request(threshold = cutoff, topK = topK)
                val actual = repository.findSimilarWithScores(query)
                assertSameResults(oracle.findSimilarWithScores(query), actual, "cutoff=$cutoff topK=$topK")
                assertTrue(actual.all { it.score >= cutoff }, "returned scores honor the requested threshold")
            }
        }
        val all = repository.findSimilarWithScores(request(threshold = -1.0)).associate { it.match.id to it.score }
        assertEquals(0.0, all.getValue("zero"))
        assertEquals(-1.0, all.getValue("opposite"), 1e-6)
        assertThrows<IllegalArgumentException> { repository.findSimilarWithScores(request(topK = -1)) }
    }

    @Test
    fun `equal scores fall back to context then id in code point order`() {
        // U+FF61 sorts before U+10000 by code point but after it in UTF-16, where U+10000 is U+D800 U+DC00.
        listOf(proposition("𐀀", "｡"), proposition("｡", "｡"), proposition("c1", "𐀀"), proposition("c2"))
            .forEach { repository.save(it) }
        // Context first ("a", then "｡", then "𐀀"), then id. The hex columns give c2, c1, 𐀀, ｡ instead.
        assertEquals(listOf("c2", "｡", "𐀀", "c1"), repository.findSimilarWithScores(request()).map { it.match.id })
        val clusters = repository.findClusters(0.9, 5, PropositionQuery.againstContext("｡"))
        assertEquals(listOf("｡"), clusters.map { it.anchor.id })
        assertEquals(listOf("𐀀"), clusters.single().similar.map { it.match.id })
    }

    @Test
    fun `every structured filter runs before ranking and top-K`() {
        val oracle = InMemoryPropositionRepository(model)
        val rows = (0..11).map { i ->
            model.vectors["fact-$i"] = floatArrayOf(1f, i / 5f)
            proposition("fact-$i", if (i < 3) "outside" else "a").copy(
                confidence = i / 16.0, importance = i / 16.0, level = i % 4, reinforceCount = i, sourceIds = listOf("source"),
                mentions = if (i % 2 == 0) listOf(EntityMention("entity", "Thing", "e")) else emptyList(),
                status = if (i % 2 == 0) PropositionStatus.ACTIVE else PropositionStatus.SUPERSEDED,
                pinned = i % 2 == 0, metadata = mapOf(DiceMetadataKeys.TRUST_SCORE to i / 16.0),
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
        )
        for ((index, q) in queries.withIndex()) {
            val filter = q.copy(effectiveConfidenceAsOf = now)
            assertSameResults(
                oracle.findSimilarWithScores(request(topK = 1), filter),
                repository.findSimilarWithScores(request(topK = 1), filter),
                "filter $index",
            )
        }
        val global = repository.findSimilarWithScores(request(topK = 1))
        assertTrue(global.all { it.match.contextId.value == "outside" })
        assertEquals("fact-3", repository.findSimilarWithScores(request(topK = 1), PropositionQuery.againstContext("a")).single().match.id)
    }

    @Test
    fun `a filtered search refuses more eligible rows than its budget while the unfiltered one scans them all`() {
        val capped = repository.withExactVectorBudget(ExactVectorBudget(3, Duration.ofSeconds(5)))
        val filter = PropositionQuery.againstContext("a")
        // Rows the filter leaves out never count against the budget.
        listOf("outside-1", "outside-2").forEach { capped.save(proposition(it, "outside")) }
        listOf("fact-1", "fact-2", "fact-3").forEach { capped.save(proposition(it)) }
        assertEquals(listOf("fact-1", "fact-2"), capped.findSimilarWithScores(request(topK = 2), filter).map { it.match.id })

        // A row with no vector is still eligible, so it still counts, as it does in the other stores.
        plain.save(proposition("fact-4"))
        model.calls.clear()
        val failure = assertThrows<ScopedReadException> { capped.findSimilarWithScores(request(topK = 2), filter) }
        assertEquals(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED, failure.failure)
        assertTrue(model.calls.isEmpty(), "the budget is checked before the query is embedded")

        assertEquals(5, capped.findSimilarWithScores(request()).size, "the unfiltered search is not capped")
        assertEquals(3, repository.findSimilarWithScores(request(topK = 5), filter).size, "the default budget is far larger")
        assertEquals(listOf(2, 1), capped.findClusters(0.9, 5, filter).map { it.similar.size }, "clustering is not capped")
    }

    @Test
    fun `the budget counts only rows above the effective-confidence floor`() {
        val capped = repository.withExactVectorBudget(ExactVectorBudget(3, Duration.ofSeconds(5)))
        listOf("fact-1", "fact-2", "fact-3").forEach { capped.save(proposition(it)) }
        listOf("faded-1", "faded-2").forEach { capped.save(proposition(it).copy(confidence = 0.2)) }
        val unfloored = PropositionQuery.againstContext("a").copy(effectiveConfidenceAsOf = now)
        val floored = unfloored.copy(minEffectiveConfidence = 0.5)

        assertEquals(listOf("fact-1", "fact-2", "fact-3"), capped.findSimilarWithScores(request(), floored).map { it.match.id })
        val failure = assertThrows<ScopedReadException> { capped.findSimilarWithScores(request(), unfloored) }
        assertEquals(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED, failure.failure)
    }

    @Test
    fun `metadata writes keep vectors while an unembedded text change leaves them out`() {
        val original = repository.save(proposition("stable"))
        model.failures += original.text
        plain.pin(original.id)
        plain.addProvenance(original.id, listOf(ProvenanceEntry(UriLocator("https://example.test/evidence"))))
        plain.touchAccessed(listOf(original.id))
        assertEquals(1.0, repository.findSimilarWithScores(request()).single().score, 1e-6)
        plain.save(assertNotNull(plain.findById(original.id)))
        assertEquals(1, repository.findSimilarWithScores(request()).size)
        plain.save(original.copy(text = "new text"))
        assertTrue(repository.findSimilarWithScores(request()).isEmpty())
        assertFalse(plain.supportsVector)
        assertTrue(plain.findSimilarWithScores(request(), PropositionQuery()).isEmpty())
        assertEquals(0, plain.reembedAll().propositions)
    }

    @Test
    fun `clusters keep membership, pair order and per-anchor limits`() {
        val oracle = InMemoryPropositionRepository(model)
        val rows = (0..5).map { proposition("outside-$it", "outside") } + listOf("a", "b", "c").map { proposition(it) }
        rows.forEach { repository.save(it); oracle.save(it) }
        val filter = PropositionQuery.againstContext("a")
        model.failures += rows.map { it.text }
        model.calls.clear()
        val actual = repository.findClusters(0.9, 1, filter)
        assertEquals(oracle.findClusters(0.9, 1, filter).map { it.anchor to it.similar.map { s -> s.match } },
            actual.map { it.anchor to it.similar.map { s -> s.match } })
        assertEquals(listOf("b", "c"), actual.map { it.similar.single().match.id })
        assertEquals(1, repository.findClusters(0.9, 1, filter.copy(limit = 2)).size)
        assertTrue(repository.findClusters(0.0, 0, filter).isEmpty())
        assertTrue(model.calls.isEmpty(), "clustering uses the stored vectors")
    }

    @Test
    fun `partial re-embedding reports failures and a retry converges, also for a new dimension`() {
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
            assertEquals(2, failure.reembedded)
            assertEquals(listOf("a", "b", "c"), model.calls)
            assertEquals(listOf("a", "c"), repository.findSimilarWithScores(request(threshold = 0.5)).map { it.match.id })
            model.failures.clear()
            assertEquals(3, repository.reembedAll().propositions)
            assertEquals(listOf("a", "b", "c"), repository.findSimilarWithScores(request(threshold = 0.99)).map { it.match.id })
        }
        assertEquals(rows, repository.findAll())
    }

    @Test
    fun `re-embedding with a dead model stops at the breaker without losing data`() {
        val rows = (0..8).map { repository.save(proposition("fact-$it")) }
        model.failures += rows.map { it.text }
        model.calls.clear()
        val failure = assertThrows<PropositionReembedIncompleteException> { repository.reembedAll() }
        val cap = ConsecutiveFailureBreaker.DEFAULT_MAX_CONSECUTIVE_FAILURES
        assertEquals(rows.take(cap).map { it.id }, failure.failedPropositionIds)
        assertEquals(rows.drop(cap).map { it.id }, failure.notAttemptedPropositionIds)
        assertEquals(0, failure.reembedded)
        assertEquals(rows, repository.findAll())
        model.failures.clear()
        assertEquals(rows.size, repository.reembedAll().propositions)
    }

    companion object {
        @Container
        @JvmField
        internal val server = FalkorDbTestServer()
    }
}
