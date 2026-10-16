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
package com.embabel.dice.agent

import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.proposition.store.JsonFilePropositionRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.time.Instant

class ScopedMemoryRetrieverTest {
    @TempDir
    lateinit var directory: Path
    private val asOf = Instant.parse("2026-01-01T00:00:00Z")
    private val scope = ContextReadScope.ofValues(listOf("B", "A", "A"))
    private val order = compareBy<CandidateEnvelope<String>> { it.key.contextIdValue }.thenBy { it.key.propositionId }

    private fun hit(id: String, context: String = "A", seeds: Set<String> = emptySet()) =
        CandidateEnvelope(PropositionKey(context, id), id, seeds)

    private class Recording : CandidateOperations<String> {
        var thrown: RuntimeException? = null
        var vectors = emptyList<CandidateEnvelope<String>>()
        var keywords = emptyList<CandidateEnvelope<String>>()
        var relatives = emptyList<CandidateEnvelope<String>>()
        var listing = emptyList<CandidateEnvelope<String>>()
        var failure: String? = null
        val calls = mutableListOf<String>()
        var seeds = emptySet<String>()
        private fun record(name: String) {
            calls += name
            if (name.startsWith("vector:")) thrown?.let { throw it }
            if (failure == name.substringBefore(":")) throw ScopedReadException("SCOPED_READ_FAILED")
        }
        override fun preflight(tiers: Set<MemoryTier>) { record("preflight") }
        override fun count(): Long { record("count"); return 7 }
        override fun list(limit: Int): List<CandidateEnvelope<String>> { record("list:$limit"); return listing }
        override fun vector(query: String, limit: Int): List<CandidateEnvelope<String>> { record("vector:$limit"); return vectors }
        override fun keyword(tokens: List<String>, limit: Int): List<CandidateEnvelope<String>> { record("keyword:$limit"); return keywords }
        override fun related(entityIds: Set<String>, limit: Int): List<CandidateEnvelope<String>> {
            record("related:$limit"); seeds = entityIds; return relatives
        }
    }

    private fun engine(operations: Recording, tiers: Set<MemoryTier> = MemoryTier.entries.toSet()) =
        MemorySelectionEngine(operations, order, tiers)

    @Test
    fun `duplicate tier rows contribute once and receive contiguous ranks`() {
        val op = Recording().apply {
            vectors = listOf(hit("a"), hit("a"), hit("b"))
            keywords = listOf(hit("b"))
        }
        assertEquals(listOf("b", "a"), engine(op).select("query", 2).entries.map { it.value })
        assertEquals(listOf("preflight", "vector:33", "keyword:33"), op.calls)
    }

    @Test
    fun `global fusion rewards consensus across contexts without quotas`() {
        val op = Recording().apply {
            vectors = listOf(hit("first"), hit("consensus", "B"), hit("third"))
            keywords = listOf(hit("consensus", "B"))
        }
        assertEquals(listOf("consensus", "first"), engine(op).select("query", 2).entries.map { it.value })
    }

    @Test
    fun `equal scores use earliest tier then caller order despite shuffled arrival`() {
        repeat(5) { seed ->
            val values = listOf(hit("x", "B"), hit("y", "A"))
            val shuffled = values.shuffled(kotlin.random.Random(seed))
            val op = Recording().apply { vectors = shuffled; keywords = shuffled.reversed() }
            assertEquals(listOf("y", "x"), engine(op).select("query", 2).entries.map { it.value })
        }
        val op = Recording().apply { vectors = listOf(hit("z")); keywords = listOf(hit("a")) }
        assertEquals(listOf("z", "a"), engine(op).select("query", 2).entries.map { it.value })
    }

    @Test
    fun `pool uses exact C plus one and excludes overflow row from fusion and seeds`() {
        val op = Recording().apply {
            vectors = (0..32).map { hit("v$it", seeds = setOf("e$it")) }
            keywords = listOf(hit("v32"))
        }
        val result = engine(op).select("query", 1)
        assertTrue(result.boundedPool)
        assertEquals("v0", result.entries.single().value)
        assertEquals(listOf("preflight", "vector:33", "keyword:33"), op.calls)
        assertEquals(400, candidateCapacity(Int.MAX_VALUE - 1))
        assertEquals(40, candidateCapacity(10))
    }

    @Test
    fun `bounded listing consumes C and does not perform tier probes`() {
        val op = Recording().apply { listing = (0..32).map { hit("$it") } }
        val result = engine(op).select("  ", 2)
        assertTrue(result.boundedPool)
        assertEquals(listOf("0", "1"), result.entries.map { it.value })
        assertEquals(listOf("preflight", "list:33"), op.calls)
    }

    @Test
    fun `expansion uses sixteen lexical eligible seeds only when direct unique hits are thin`() {
        val op = Recording().apply {
            vectors = listOf(hit("direct", seeds = (0..20).map { "entity-%02d".format(it) }.reversed().toSet()))
            keywords = vectors
            relatives = listOf(hit("related", "B"))
        }
        val result = engine(op).select("query", 3)
        assertEquals(2, result.entries.size)
        assertEquals((0..15).map { "entity-%02d".format(it) }.toSet(), op.seeds)
        assertEquals("related:33", op.calls.last())
    }

    @Test
    fun `invalid keys fail before fusion or expansion`() {
        val op = Recording().apply { vectors = listOf(hit("invalid", " ", setOf("secret"))) }
        assertThrows(ScopedReadException::class.java) { engine(op).select("query", 3) }
        assertEquals(listOf("preflight", "vector:33"), op.calls)
        assertTrue(op.seeds.isEmpty())
    }

    @Test
    fun `each configured tier and preflight fail closed`() {
        listOf("preflight", "vector", "keyword", "related").forEach { failing ->
            val op = Recording().apply { vectors = listOf(hit("seed", seeds = setOf("entity"))); failure = failing }
            assertThrows(ScopedReadException::class.java) { engine(op).select("query", 2) }
            assertEquals(failing, op.calls.last().substringBefore(":"))
        }
    }

    @Test
    fun `count is membership only and zero output does no work`() {
        val op = Recording()
        assertTrue(engine(op).select("query", 0).entries.isEmpty())
        assertTrue(op.calls.isEmpty())
        assertEquals(7L, engine(op).count())
        assertEquals(listOf("preflight", "count"), op.calls)
    }

    @Test
    fun `budget deadline and unsupported vector failures propagate unchanged`() {
        listOf(ExactVectorBudgetExceededException(), ExactVectorDeadlineExceededException(), FilteredVectorUnsupportedException()).forEach { failure ->
            val op = Recording().apply { thrown = failure }
            val actual = assertThrows(failure.javaClass) { engine(op).select("query", 1) }
            assertSame(failure, actual)
            assertEquals(listOf("preflight", "vector:33"), op.calls)
        }
    }

    @Test
    fun `keys include context and never collapse identical local identifiers`() {
        val op = Recording().apply { vectors = listOf(hit("same", "B"), hit("same", "A")) }
        assertEquals(setOf(PropositionKey("A", "same"), PropositionKey("B", "same")), engine(op).select("query", 2).entries.map { it.key }.toSet())
    }

    @Test
    fun `deduplication compacts ranks before reciprocal rank assignment`() {
        val op = Recording().apply {
            vectors = listOf(hit("a"), hit("a"), hit("b"))
            keywords = listOf(hit("b"), hit("a"))
        }
        val reverseOrder = order.reversed()
        assertEquals(listOf("b", "a"), MemorySelectionEngine(op, reverseOrder, MemoryTier.entries.toSet()).select("query", 2).entries.map { it.value })
    }

    private fun row(id: String, context: String = "A", text: String = "matching fact", entities: List<String> = emptyList(), confidence: Double = 0.9) = Proposition(
        id = id, contextId = ContextId(context), text = text,
        mentions = entities.map { EntityMention(it, "Entity", it) }, confidence = confidence,
        created = asOf, contentRevised = asOf, metadataRevised = asOf,
    )

    private fun embedder(): EmbeddingService {
        val service = mock<EmbeddingService>()
        whenever(service.embed(any<String>())).thenAnswer {
            when (it.getArgument<String>(0)) {
                "query", "matching fact" -> floatArrayOf(1f, 0f)
                else -> floatArrayOf(-1f, 0f)
            }
        }
        return service
    }

    private fun both(run: (PropositionRepository) -> Unit) {
        listOf(
            InMemoryPropositionRepository(embedder()),
            JsonFilePropositionRepository(directory.resolve("store.json"), embedder()),
        ).forEach(run)
    }

    private fun session(store: PropositionStore) = ScopedPropositionReader.open(store, scope).snapshot(asOf, 2.0)

    @Test
    fun `portable backends rank A and B globally and ignore conflicting identical C text`() {
        both { store ->
            store.save(row("b", "B"))
            store.save(row("a"))
            store.save(row("c", "C", entities = listOf("forbidden"), confidence = 1.0))
            val retriever = ScopedMemoryRetriever(session(store))
            assertEquals(listOf("a", "b"), retriever.select("query", 2).entries.map { it.value.id })
            assertEquals(2L, retriever.count())
            assertTrue(retriever.select("unmatched", 3).entries.isEmpty())
        }
    }

    @Test
    fun `eager exclusions precede listing and fusion K in portable stores`() {
        both { store ->
            repeat(40) { store.save(row("eager-$it", confidence = 1.0)) }
            store.save(row("allowed", "B"))
            val excluded = (0..39).map { PropositionKey("A", "eager-$it") }
            val retriever = ScopedMemoryRetriever(session(store).excluding(excluded))
            assertEquals(listOf("allowed"), retriever.select("", 1).entries.map { it.value.id })
            assertEquals(listOf("allowed"), retriever.select("query", 1).entries.map { it.value.id })
            assertEquals(1L, retriever.count())
        }
    }

    @Test
    fun `related expansion adds a conjunct instead of replacing existing entity eligibility`() {
        both { store ->
            store.save(row("direct", entities = listOf("required", "seed")))
            store.save(row("related", "B", "neighbor", listOf("required", "seed")))
            store.save(row("hidden", "B", "neighbor", listOf("seed")))
            store.save(row("forbidden", "C", entities = listOf("secret")))
            val base = session(store).restrict(PropositionQuery(entityId = "required", minConfidence = 0.8))
            val result = ScopedMemoryRetriever(base).select("query", 3)
            assertEquals(setOf("direct", "related"), result.entries.map { it.value.id }.toSet())
            assertEquals(2L, ScopedMemoryRetriever(base).count())
        }
    }

    private class RecordingBackend : PropositionStore by InMemoryPropositionRepository(), ContextSetVectorSearchCapable {
        var calls = 0
        var vectorsSupported = true
        override val supportsScopedVectors: Boolean get() = vectorsSupported
        var returned = emptyList<Proposition>()
        override fun query(scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int): List<Proposition> { calls++; return returned }
        override fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long { calls++; return 0 }
        override fun keywordOverlap(scope: ContextReadScope, spec: ScopedReadSpec, tokens: List<String>, limit: Int): List<Proposition> { calls++; return returned }
        override fun findSimilarWithScores(request: TextSimilaritySearchRequest, scope: ContextReadScope, spec: ScopedReadSpec, budget: ExactVectorBudget): List<SimilarityResult<Proposition>> {
            calls++; return returned.map { SimilarityResult(it, 1.0) }
        }
    }

    @Test
    fun `C plus one and final output preflight reject before any backend operation`() {
        val store = RecordingBackend()
        val limits = ScopedReadLimits.DEFAULT.withMaxResults(10).withMaxEager(0).withMaxProbe(32)
        val retriever = ScopedMemoryRetriever(ScopedPropositionReader.open(store, scope, limits).snapshot(asOf, 2.0))
        assertThrows(ScopedReadException::class.java) { retriever.select("query", 1) }
        assertThrows(ScopedReadException::class.java) { retriever.select("", 1) }
        assertThrows(ScopedReadException::class.java) { retriever.select("query", 11) }
        assertThrows(ScopedReadException::class.java) { retriever.select("query", 0) }
        assertEquals(0, store.calls)
    }

    @Test
    fun `foreign owner from backend aborts before keyword or entity expansion`() {
        val store = RecordingBackend().apply { returned = listOf(row("secret", "C", entities = listOf("secret-seed"))) }
        assertThrows(ScopedReadException::class.java) { ScopedMemoryRetriever(session(store)).select("query", 3) }
        assertEquals(1, store.calls)
    }

    @Test
    fun `listing does not require an unused vector tier`() {
        val store = InMemoryPropositionRepository()
        store.save(row("listed"))
        val retriever = ScopedMemoryRetriever(session(store))
        assertEquals(listOf("listed"), retriever.select("", 1).entries.map { it.value.id })
        assertEquals(1L, retriever.count())
        val recording = RecordingBackend().apply { vectorsSupported = false; returned = listOf(row("listed")) }
        val recorded = ScopedMemoryRetriever(session(recording))
        recorded.select("", 1)
        recorded.count()
        assertEquals(2, recording.calls)
        assertThrows(ScopedReadException::class.java) { recorded.select("query", 1) }
        assertEquals(2, recording.calls)
    }

    @Test
    fun `unsupported vectors abort search explicitly`() {
        val retriever = ScopedMemoryRetriever(session(InMemoryPropositionRepository()))
        val failure = assertThrows(ScopedReadException::class.java) { retriever.select("query", 1) }
        assertEquals("SCOPED_VECTOR_UNSUPPORTED", failure.code)
    }
}
