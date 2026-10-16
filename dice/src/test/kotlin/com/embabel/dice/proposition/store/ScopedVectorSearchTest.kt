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
package com.embabel.dice.proposition.store

import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ContextSetVectorSearchCapable
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedPropositionReader
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

class ScopedVectorSearchTest {
    @TempDir
    lateinit var directory: Path

    private val asOf = Instant.parse("2026-01-01T00:00:00Z")
    private val scope = ContextReadScope.ofValues(listOf("B", "A", "A"))
    private val spec get() = ScopedReadSpec.of(scope)
    private val clock = ReadClock(asOf, 2.0)
    private val budget = ExactVectorBudget(100, Duration.ofSeconds(5))
    private var fileNumber = 0

    private fun row(id: String, context: String = "A", text: String = id) = Proposition(
        id = id, contextId = ContextId(context), text = text, mentions = emptyList(), confidence = 0.9,
        created = asOf, contentRevised = asOf, metadataRevised = asOf,
    )

    private fun request(k: Int = 10, threshold: Double = 0.0) =
        TextSimilaritySearchRequest(query = "query", topK = k, similarityThreshold = threshold)

    private fun embedder(vectors: Map<String, FloatArray>, onQuery: () -> Unit = {}): EmbeddingService {
        val service = mock<EmbeddingService>()
        whenever(service.embed(any<String>())).thenAnswer {
            val text = it.getArgument<String>(0)
            if (text == "query") onQuery()
            vectors[text] ?: floatArrayOf(1f, 0f)
        }
        return service
    }

    private fun both(service: EmbeddingService, run: (PropositionRepository, ContextSetVectorSearchCapable) -> Unit) {
        val repositories = listOf(
            InMemoryPropositionRepository(service),
            JsonFilePropositionRepository(directory.resolve("store-${fileNumber++}.json"), service),
        )
        repositories.forEach { run(it, it as ContextSetVectorSearchCapable) }
    }

    private fun assertFailure(expected: ScopedReadFailure, read: () -> Unit) =
        assertEquals(expected, assertThrows(ScopedReadException::class.java) { read() }.failure)

    @Test
    fun `eligible membership precedes vector K and candidate budget`() {
        val service = embedder(mapOf("allowed" to floatArrayOf(0.8f, 0.6f)))
        both(service) { repository, vectors ->
            val allowed = row("allowed", "B")
            repository.save(allowed)
            repository.save(row("eager"))
            repository.save(row("hidden"))
            repeat(20) { repository.save(row("decoy-$it", "C")) }
            val filtered = spec.includingOnly(listOf(PropositionKey("B", "allowed"), PropositionKey("A", "eager")))
                .excluding(listOf(PropositionKey("A", "eager")))
            val result = vectors.findSimilarWithScores(request(1), filtered, clock, ExactVectorBudget(1, budget.timeout))
            assertEquals(listOf(allowed), result.map { it.match })
            assertEquals(0.8, result.single().score, 0.000001)
        }
    }

    @Test
    fun `all fragments constrain membership while query limits do not`() {
        both(embedder(emptyMap())) { repository, vectors ->
            val allowed = row("allowed", "B").copy(importance = 0.8, pinned = true)
            repository.save(allowed)
            repository.save(row("low-importance", "B").copy(pinned = true))
            repository.save(row("unpinned", "B").copy(importance = 0.8))
            repository.save(row("inactive", "B").copy(importance = 0.8, pinned = true, status = PropositionStatus.STALE))
            repository.save(row("wrong-context").copy(importance = 0.8, pinned = true))
            val filtered = spec.restrict(PropositionQuery(contextId = ContextId("B"), pinned = true))
                .restrict(PropositionQuery(minImportance = 0.7, statuses = setOf(PropositionStatus.ACTIVE)))
            assertEquals(listOf(allowed), vectors.findSimilarWithScores(request(), filtered, clock, budget).map { it.match })
            assertThrows(IllegalArgumentException::class.java) { spec.restrict(PropositionQuery(contextId = ContextId("B"), limit = 0)) }
        }
    }

    @Test
    fun `N succeeds and N plus one fails before query embedding or scoring`() {
        val candidates = listOf(row("one"), row("two", "B"))
        val engine = LocalScopedReadOperations { candidates }
        var embeds = 0
        var scores = 0
        fun run(cap: Int) = engine.findSimilarWithScores(
            request(), spec, clock, ExactVectorBudget(cap, budget.timeout),
            { embeds++; floatArrayOf(1f, 0f) }, { scores++; floatArrayOf(1f, 0f) },
        )
        assertFailure(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED) { run(1) }
        assertEquals(0, embeds)
        assertEquals(0, scores)
        assertEquals(2, run(2).size)
        assertEquals(1, embeds)
        assertEquals(2, scores)
    }

    @Test
    fun `both repository bridges enforce the explicit candidate cap`() {
        both(embedder(emptyMap())) { repository, vectors ->
            repository.save(row("one"))
            repository.save(row("two", "B"))
            assertFailure(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED) {
                vectors.findSimilarWithScores(request(1), spec, clock, ExactVectorBudget(1, budget.timeout))
            }
            assertEquals(2, vectors.findSimilarWithScores(request(), spec, clock, ExactVectorBudget(2, budget.timeout)).size)
        }
    }

    @Test
    fun `zero K empty membership and invalid scope do not embed`() {
        var calls = 0
        both(embedder(emptyMap()) { calls++ }) { repository, vectors ->
            repository.save(row("one"))
            assertTrue(vectors.findSimilarWithScores(request(0), spec, clock, budget).isEmpty())
            assertTrue(vectors.findSimilarWithScores(request(), spec.includingOnly(emptyList()), clock, budget).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { ContextReadScope.ofValues(emptyList()) }
            assertThrows(IllegalArgumentException::class.java) { ContextReadScope.ofValues(listOf(" ")) }
            assertThrows(IllegalArgumentException::class.java) {
                spec.restrict(PropositionQuery(contextId = ContextId("C")))
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `absent embedder is an explicit unsupported new tier even on an empty store`() {
        listOf(InMemoryPropositionRepository(), JsonFilePropositionRepository(directory.resolve("empty.json"))).forEach {
            val vectors = it as ContextSetVectorSearchCapable
            assertFalse(vectors.supportsScopedVectors)
            val failure = assertThrows(ScopedReadException::class.java) {
                vectors.findSimilarWithScores(request(), spec, clock, budget)
            }
            assertEquals(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED, failure.failure)
            assertThrows(ScopedReadException::class.java) {
                ScopedPropositionReader.open(it, scope).snapshot(asOf, 2.0).findSimilarWithScores(request())
            }
            assertTrue(it.findSimilarWithScores(request()).isEmpty())
        }
    }

    @Test
    fun `missing resident embeddings are skipped but still consume admission budget`() {
        val engine = LocalScopedReadOperations { listOf(row("missing"), row("present")) }
        val found = engine.findSimilarWithScores(request(), spec, clock, budget, { floatArrayOf(1f) }) {
            if (it.propositionId == "present") floatArrayOf(1f) else null
        }
        assertEquals(listOf("present"), found.map { it.match.id })
        assertFailure(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED) {
            engine.findSimilarWithScores(request(), spec, clock, ExactVectorBudget(1, budget.timeout),
                { fail("must not embed") }, { fail("must not read embeddings") })
        }
    }

    @Test
    fun `scoped search skips unusable stored vectors but still counts them against the budget`() {
        listOf(floatArrayOf(), floatArrayOf(1f), floatArrayOf(Float.NaN, 0f), floatArrayOf(Float.POSITIVE_INFINITY, 0f))
            .forEach { invalid ->
                both(embedder(mapOf("bad" to invalid, "zero" to floatArrayOf(0f, 0f)))) { repository, vectors ->
                    listOf("good", "bad", "zero").forEach { repository.save(row(it)) }
                    val found = vectors.findSimilarWithScores(request(), spec, clock, budget)
                    repository.delete("bad")
                    assertEquals(vectors.findSimilarWithScores(request(), spec, clock, budget).map { it.match.id to it.score }, found.map { it.match.id to it.score })
                    assertEquals(listOf("good" to 1.0, "zero" to 0.0), found.map { it.match.id to it.score })
                    repository.save(row("bad"))
                    assertFailure(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED) {
                        vectors.findSimilarWithScores(request(), spec, clock, ExactVectorBudget(2, budget.timeout))
                    }
                }
            }
    }

    @Test
    fun `plain vector search and clustering skip a malformed stored vector instead of failing`() {
        listOf(floatArrayOf(Float.NaN, 0f), floatArrayOf(Float.POSITIVE_INFINITY, 0f)).forEach { invalid ->
            both(embedder(mapOf("bad" to invalid))) { repository, _ ->
                repository.save(row("good"))
                repository.save(row("bad"))
                assertEquals(listOf("good"), repository.findSimilarWithScores(request()).map { it.match.id })
                assertEquals(listOf("good"), repository.findSimilarWithScores(request(), PropositionQuery()).map { it.match.id })
                assertDoesNotThrow { repository.findClusters(0.0, 10, PropositionQuery()) }
            }
        }
    }

    @Test
    fun `invalid query vectors fail even when all resident embeddings are absent`() {
        val engine = LocalScopedReadOperations { listOf(row("missing")) }
        listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(Float.NEGATIVE_INFINITY)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                engine.findSimilarWithScores(request(), spec, clock, budget, { invalid }, { null })
            }
        }
    }

    @Test
    fun `raw cosine threshold zero vectors and orthogonal ties have a defined oracle`() {
        val service = embedder(mapOf(
            "zero" to floatArrayOf(0f, 0f), "orthogonal" to floatArrayOf(0f, 1f),
            "opposite" to floatArrayOf(-1f, 0f), "near" to floatArrayOf(3f, 4f),
        ))
        both(service) { repository, vectors ->
            listOf("zero", "orthogonal", "opposite", "near").forEach { repository.save(row(it)) }
            val result = vectors.findSimilarWithScores(request(), spec, clock, budget)
            assertEquals(listOf("near", "orthogonal", "zero"), result.map { it.match.id })
            assertEquals(listOf(0.6, 0.0, 0.0), result.map { it.score })
            assertEquals(listOf("near"), vectors.findSimilarWithScores(request(threshold = 0.6), spec, clock, budget).map { it.match.id })
            assertTrue(vectors.findSimilarWithScores(request(threshold = 0.60001), spec, clock, budget).isEmpty())
        }
        assertEquals(-1.0, cosineSimilarity(floatArrayOf(1f), floatArrayOf(-1f)))
        assertEquals(1.0, cosineSimilarity(floatArrayOf(Float.MAX_VALUE), floatArrayOf(Float.MAX_VALUE)))
        assertEquals(1.0, cosineSimilarity(floatArrayOf(Float.MIN_VALUE), floatArrayOf(Float.MIN_VALUE)))
    }

    @Test
    fun `equal vector scores use context then proposition ID before global K`() {
        both(embedder(emptyMap())) { repository, vectors ->
            listOf(row("aa", "B"), row("zz"), row("az")).forEach(repository::save)
            val expected = listOf("A/az", "A/zz", "B/aa")
            fun keys(rows: List<Proposition>) = rows.map { "${it.contextId.value}/${it.id}" }
            assertEquals(expected, keys(vectors.findSimilarWithScores(request(), spec, clock, budget).map { it.match }))
            assertEquals(expected.take(2), keys(vectors.findSimilarWithScores(request(2), spec, clock, budget).map { it.match }))
            assertEquals(expected, keys(repository.findSimilarWithScores(request()).map { it.match }))
            assertEquals(expected.take(2), keys(repository.findSimilarWithScores(request(2), PropositionQuery()).map { it.match }))
        }
    }

    @Test
    fun `legacy query ordering and limit never pretruncate vector candidates`() {
        both(embedder(mapOf("weak" to floatArrayOf(0.8f, 0.6f)))) { repository, vectors ->
            repository.save(row("weak").copy(importance = 1.0))
            val best = row("best").copy(importance = 0.1)
            repository.save(best)
            repository.save(row("decoy", "C"))
            val query = PropositionQuery(contextId = ContextId("A"), limit = 1, orderBy = PropositionQuery.OrderBy.IMPORTANCE_DESC)
            assertEquals(listOf(best), repository.findSimilarWithScores(request(1), query).map { it.match })
            assertEquals(listOf(best), vectors.findSimilarWithScores(request(1), spec.restrict(query.copy(orderBy = PropositionQuery.OrderBy.NONE, limit = null)), clock, budget).map { it.match })
            assertThrows(IllegalArgumentException::class.java) { spec.restrict(query) }
            assertEquals(2, repository.findSimilarWithScores(request(), query.copy(limit = 0)).size)
        }
    }

    @Test
    fun `default and custom decay use identical legacy and scoped membership`() {
        both(embedder(emptyMap())) { repository, vectors ->
            val aged = row("aged").copy(decay = 1.0, contentRevised = asOf.minus(Duration.ofDays(90)),
                lastAccessed = asOf.minus(Duration.ofDays(90)))
            repository.save(aged)
            repository.save(row("fresh"))
            for (k in listOf(2.0, 0.0, 0.1)) {
                val query = PropositionQuery(contextId = ContextId("A"), effectiveConfidenceAsOf = asOf,
                    decayK = k, minEffectiveConfidence = 0.8)
                val expected = listOf(aged, row("fresh")).filter { it.effectiveConfidenceAt(asOf, k) >= 0.8 }
                    .sortedBy { it.id }.map { it.id }
                assertEquals(expected, repository.findSimilarWithScores(request(), query).map { it.match.id })
                assertEquals(expected, vectors.findSimilarWithScores(request(), spec.restrict(query.copy(effectiveConfidenceAsOf = null)), clock, budget).map { it.match.id })
            }
        }
    }

    @Test
    fun `JSON reload recomputes vectors with the same scoped scores and ties`() {
        val path = directory.resolve("reload.json")
        val service = embedder(mapOf("near" to floatArrayOf(3f, 4f)))
        val original = JsonFilePropositionRepository(path, service)
        listOf(row("aa", "B"), row("zz"), row("near")).forEach(original::save)
        val before = original.findSimilarWithScores(request(), spec, clock, budget)
        val after = JsonFilePropositionRepository(path, service).findSimilarWithScores(request(), spec, clock, budget)
        assertEquals(before.map { it.match.id to it.score }, after.map { it.match.id to it.score })
    }

    @Test
    fun `JSON reload with failed embedding preserves nonvector reads and skips missing vector`() {
        val path = directory.resolve("missing.json")
        JsonFilePropositionRepository(path).save(row("missing"))
        val failing = mock<EmbeddingService>()
        whenever(failing.embed(any<String>())).thenAnswer {
            if (it.getArgument<String>(0) == "missing") throw IllegalStateException("embed failed")
            floatArrayOf(1f, 0f)
        }
        val reloaded = JsonFilePropositionRepository(path, failing)
        assertEquals(1, reloaded.count())
        assertTrue(reloaded.findSimilarWithScores(request(), spec, clock, budget).isEmpty())
    }

    /** A nanosecond clock the deadline tests move by hand, so no test waits on the wall clock. */
    private class Ticks {
        var now = 0L
        fun advance(by: Duration) {
            now += by.toNanos()
        }
    }

    private val ticks = Ticks()
    private val limit = Duration.ofMillis(100)
    private val shortBudget = ExactVectorBudget(10, limit)

    private fun assertDeadline(read: () -> Unit) {
        val failure = assertThrows(ScopedReadException::class.java) { read() }
        assertEquals(ScopedReadFailure.EXACT_VECTOR_DEADLINE_EXCEEDED, failure.failure)
        assertTrue(failure.cause is TimeoutException)
    }

    @Test
    fun `E1 a row traversal that reaches the limit fails before embedding`() {
        val engine = LocalScopedReadOperations({ ticks.now }) { ticks.advance(limit); listOf(row("one")) }
        assertDeadline {
            engine.findSimilarWithScores(request(), spec, clock, shortBudget,
                { fail("expired candidates must not embed") }, { fail("expired candidates must not score") })
        }
    }

    @Test
    fun `E2 time spent in the embedder is never charged`() {
        val engine = LocalScopedReadOperations({ ticks.now }) { listOf(row("one")) }
        val found = engine.findSimilarWithScores(request(), spec, clock, shortBudget,
            { ticks.advance(limit.multipliedBy(10)); floatArrayOf(1f) }, { floatArrayOf(1f) })
        assertEquals(listOf("one"), found.map { it.match.id })
    }

    @Test
    fun `E3 a last vector read that reaches the limit fails with no result`() {
        val engine = LocalScopedReadOperations({ ticks.now }) { listOf(row("one"), row("two")) }
        assertDeadline {
            engine.findSimilarWithScores(request(), spec, clock, shortBudget, { floatArrayOf(1f) }) {
                if (it.propositionId == "two") ticks.advance(limit)
                floatArrayOf(1f)
            }
        }
    }

    @Test
    fun `E4 a read that finishes one nanosecond inside the limit succeeds`() {
        val engine = LocalScopedReadOperations({ ticks.now }) { listOf(row("one"), row("two")) }
        val found = engine.findSimilarWithScores(request(), spec, clock, shortBudget, { floatArrayOf(1f) }) {
            if (it.propositionId == "two") ticks.advance(limit.minusNanos(1))
            floatArrayOf(1f)
        }
        assertEquals(listOf("one", "two"), found.map { it.match.id })
    }

    @Test
    fun `E5 once the limit passes no further stored vector is read`() {
        val engine = LocalScopedReadOperations({ ticks.now }) { listOf(row("one"), row("two")) }
        assertDeadline {
            engine.findSimilarWithScores(request(), spec, clock, shortBudget, { floatArrayOf(1f) }) {
                check(it.propositionId != "two") { "no vector is read after the limit" }
                ticks.advance(limit)
                floatArrayOf(1f)
            }
        }
    }

    @Test
    fun `invalid request values fail without embedding`() {
        var calls = 0
        both(embedder(emptyMap()) { calls++ }) { repository, vectors ->
            repository.save(row("one"))
            assertThrows(IllegalArgumentException::class.java) { vectors.findSimilarWithScores(request(-1), spec, clock, budget) }
            for (threshold in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
                assertThrows(IllegalArgumentException::class.java) {
                    vectors.findSimilarWithScores(request(threshold = threshold), spec, clock, budget)
                }
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `E1 equal scores fall back to context then id in code point order`() {
        // U+FF61 sorts before U+10000 by code point but after it in UTF-16, where U+10000 is U+D800 U+DC00.
        both(embedder(emptyMap())) { repository, vectors ->
            listOf(row("𐀀", "｡"), row("｡", "｡"), row("c1", "𐀀"), row("c2")).forEach(repository::save)
            val tied = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("A", "｡", "𐀀")))
            val expected = listOf("c2", "｡", "𐀀", "c1")   // UTF-16 would give c2, c1, 𐀀, ｡
            assertEquals(expected.take(2), vectors.findSimilarWithScores(request(2), tied, clock, budget).map { it.match.id })
            assertEquals(expected, repository.findSimilarWithScores(request()).map { it.match.id })
            assertEquals(expected.take(2), repository.findSimilarWithScores(request(2), PropositionQuery()).map { it.match.id })
        }
    }

    @Test
    fun `E2 the JSON and in-memory stores anchor a cluster on the same id`() {
        // The JSON store clusters through the interface's default, the in-memory store through its own
        // override; both must anchor on U+FF61, which sorts first by code point but last in UTF-16.
        val anchors = mutableListOf<List<String>>()
        both(embedder(emptyMap())) { repository, _ ->
            listOf(row("𐀀"), row("｡")).forEach(repository::save)
            anchors += repository.findClusters(similarityThreshold = 0.9).map { it.anchor.id }
        }
        assertEquals(listOf(listOf("｡"), listOf("｡")), anchors)
    }
}
