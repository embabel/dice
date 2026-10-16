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
import com.embabel.dice.proposition.ExactVectorBudgetExceededException
import com.embabel.dice.proposition.ExactVectorDeadlineExceededException
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ScopedPropositionReader
import com.embabel.dice.proposition.ScopedReadException
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

class ScopedVectorSearchTest {
    @TempDir
    lateinit var directory: Path

    private val asOf = Instant.parse("2026-01-01T00:00:00Z")
    private val scope = ContextReadScope.ofValues(listOf("B", "A", "A"))
    private val spec get() = ScopedReadSpec.at(asOf, 2.0)
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

    @Test
    fun `eligible membership precedes vector K and candidate budget`() {
        val service = embedder(mapOf("allowed" to floatArrayOf(0.8f, 0.6f)))
        both(service) { repository, vectors ->
            val allowed = row("allowed", "B")
            repository.save(allowed)
            repository.save(row("eager"))
            repository.save(row("hidden"))
            repeat(20) { repository.save(row("decoy-$it", "C")) }
            val filtered = spec.includingOnly(scope, listOf(PropositionKey("B", "allowed"), PropositionKey("A", "eager")))
                .excluding(scope, listOf(PropositionKey("A", "eager")))
            val result = vectors.findSimilarWithScores(request(1), scope, filtered, ExactVectorBudget(1, budget.timeout))
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
            val filtered = spec.restrict(scope, PropositionQuery(contextId = ContextId("B"), pinned = true, limit = 0))
                .restrict(scope, PropositionQuery(minImportance = 0.7, statuses = setOf(PropositionStatus.ACTIVE)))
            assertEquals(listOf(allowed), vectors.findSimilarWithScores(request(), scope, filtered, budget).map { it.match })
        }
    }

    @Test
    fun `N succeeds and N plus one fails before query embedding or scoring`() {
        val candidates = listOf(row("one"), row("two", "B"))
        val engine = LocalScopedReadOperations { candidates }
        var embeds = 0
        var scores = 0
        fun run(cap: Int) = engine.findSimilarWithScores(
            request(), scope, spec, ExactVectorBudget(cap, budget.timeout),
            { embeds++; floatArrayOf(1f, 0f) }, { scores++; floatArrayOf(1f, 0f) },
        )
        assertThrows(ExactVectorBudgetExceededException::class.java) { run(1) }
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
            assertThrows(ExactVectorBudgetExceededException::class.java) {
                vectors.findSimilarWithScores(request(1), scope, spec, ExactVectorBudget(1, budget.timeout))
            }
            assertEquals(2, vectors.findSimilarWithScores(request(), scope, spec, ExactVectorBudget(2, budget.timeout)).size)
        }
    }

    @Test
    fun `zero K empty membership and invalid scope do not embed`() {
        var calls = 0
        both(embedder(emptyMap()) { calls++ }) { repository, vectors ->
            repository.save(row("one"))
            assertTrue(vectors.findSimilarWithScores(request(0), scope, spec, budget).isEmpty())
            assertTrue(vectors.findSimilarWithScores(request(), scope, spec.includingOnly(scope, emptyList()), budget).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { ContextReadScope.ofValues(emptyList()) }
            assertThrows(IllegalArgumentException::class.java) { ContextReadScope.ofValues(listOf(" ")) }
            assertThrows(IllegalArgumentException::class.java) {
                spec.restrict(scope, PropositionQuery(contextId = ContextId("C")))
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
                vectors.findSimilarWithScores(request(), scope, spec, budget)
            }
            assertEquals("SCOPED_VECTOR_UNSUPPORTED", failure.code)
            assertThrows(ScopedReadException::class.java) {
                ScopedPropositionReader.open(it, scope).snapshot(asOf, 2.0).findSimilarWithScores(request())
            }
            assertTrue(it.findSimilarWithScores(request()).isEmpty())
        }
    }

    @Test
    fun `missing resident embeddings are skipped but still consume admission budget`() {
        val engine = LocalScopedReadOperations { listOf(row("missing"), row("present")) }
        val found = engine.findSimilarWithScores(request(), scope, spec, budget, { floatArrayOf(1f) }) {
            if (it.propositionId == "present") floatArrayOf(1f) else null
        }
        assertEquals(listOf("present"), found.map { it.match.id })
        assertThrows(ExactVectorBudgetExceededException::class.java) {
            engine.findSimilarWithScores(request(), scope, spec, ExactVectorBudget(1, budget.timeout),
                { fail("must not embed") }, { fail("must not read embeddings") })
        }
    }

    @Test
    fun `invalid dimensions and nonfinite resident vectors fail atomically`() {
        listOf(floatArrayOf(), floatArrayOf(1f), floatArrayOf(Float.NaN, 0f), floatArrayOf(Float.POSITIVE_INFINITY, 0f))
            .forEach { invalid ->
                both(embedder(mapOf("bad" to invalid))) { repository, vectors ->
                    repository.save(row("good"))
                    repository.save(row("bad"))
                    assertThrows(IllegalArgumentException::class.java) {
                        vectors.findSimilarWithScores(request(), scope, spec, budget)
                    }
                }
            }
    }

    @Test
    fun `invalid query vectors fail even when all resident embeddings are absent`() {
        val engine = LocalScopedReadOperations { listOf(row("missing")) }
        listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(Float.NEGATIVE_INFINITY)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                engine.findSimilarWithScores(request(), scope, spec, budget, { invalid }, { null })
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
            val result = vectors.findSimilarWithScores(request(), scope, spec, budget)
            assertEquals(listOf("near", "orthogonal", "zero"), result.map { it.match.id })
            assertEquals(listOf(0.6, 0.0, 0.0), result.map { it.score })
            assertEquals(listOf("near"), vectors.findSimilarWithScores(request(threshold = 0.6), scope, spec, budget).map { it.match.id })
            assertTrue(vectors.findSimilarWithScores(request(threshold = 0.60001), scope, spec, budget).isEmpty())
        }
        assertEquals(-1.0, LocalScopedReadOperations.cosineSimilarity(floatArrayOf(1f), floatArrayOf(-1f)))
        assertEquals(1.0, LocalScopedReadOperations.cosineSimilarity(floatArrayOf(Float.MAX_VALUE), floatArrayOf(Float.MAX_VALUE)))
        assertEquals(1.0, LocalScopedReadOperations.cosineSimilarity(floatArrayOf(Float.MIN_VALUE), floatArrayOf(Float.MIN_VALUE)))
    }

    @Test
    fun `equal vector scores use context then proposition ID before global K`() {
        both(embedder(emptyMap())) { repository, vectors ->
            listOf(row("aa", "B"), row("zz"), row("az")).forEach(repository::save)
            val expected = listOf("A/az", "A/zz", "B/aa")
            fun keys(rows: List<Proposition>) = rows.map { "${it.contextId.value}/${it.id}" }
            assertEquals(expected, keys(vectors.findSimilarWithScores(request(), scope, spec, budget).map { it.match }))
            assertEquals(expected.take(2), keys(vectors.findSimilarWithScores(request(2), scope, spec, budget).map { it.match }))
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
            assertEquals(listOf(best), vectors.findSimilarWithScores(request(1), scope, spec.restrict(scope, query), budget).map { it.match })
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
                assertEquals(expected, vectors.findSimilarWithScores(request(), scope, spec.restrict(scope, query), budget).map { it.match.id })
            }
        }
    }

    @Test
    fun `JSON reload recomputes vectors with the same scoped scores and ties`() {
        val path = directory.resolve("reload.json")
        val service = embedder(mapOf("near" to floatArrayOf(3f, 4f)))
        val original = JsonFilePropositionRepository(path, service)
        listOf(row("aa", "B"), row("zz"), row("near")).forEach(original::save)
        val before = original.findSimilarWithScores(request(), scope, spec, budget)
        val after = JsonFilePropositionRepository(path, service).findSimilarWithScores(request(), scope, spec, budget)
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
        assertTrue(reloaded.findSimilarWithScores(request(), scope, spec, budget).isEmpty())
    }

    @Test
    fun `embedding overrun throws a deadline failure with no partial result`() {
        both(embedder(emptyMap()) { Thread.sleep(150) }) { repository, vectors ->
            repository.save(row("one"))
            assertThrows(ExactVectorDeadlineExceededException::class.java) {
                vectors.findSimilarWithScores(request(), scope, spec, ExactVectorBudget(10, Duration.ofMillis(100)))
            }
        }
    }

    @Test
    fun `deadline covers candidate traversal and vector lookup`() {
        val slowRows = LocalScopedReadOperations { Thread.sleep(150); listOf(row("one")) }
        val shortBudget = ExactVectorBudget(10, Duration.ofMillis(100))
        assertThrows(ExactVectorDeadlineExceededException::class.java) {
            slowRows.findSimilarWithScores(request(), scope, spec, shortBudget,
                { fail("expired candidates must not embed") }, { fail("expired candidates must not score") })
        }
        val engine = LocalScopedReadOperations { listOf(row("one"), row("two")) }
        assertThrows(ExactVectorDeadlineExceededException::class.java) {
            engine.findSimilarWithScores(request(), scope, spec, shortBudget, { floatArrayOf(1f) }) {
                Thread.sleep(150)
                floatArrayOf(1f)
            }
        }
    }

    @Test
    fun `invalid request values fail without embedding`() {
        var calls = 0
        both(embedder(emptyMap()) { calls++ }) { repository, vectors ->
            repository.save(row("one"))
            assertThrows(IllegalArgumentException::class.java) { vectors.findSimilarWithScores(request(-1), scope, spec, budget) }
            for (threshold in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
                assertThrows(IllegalArgumentException::class.java) {
                    vectors.findSimilarWithScores(request(threshold = threshold), scope, spec, budget)
                }
            }
        }
        assertEquals(0, calls)
    }
}
