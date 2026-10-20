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
import com.embabel.dice.proposition.ApprovedText
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
import com.embabel.dice.proposition.StrictPolicyReadCapable
import com.embabel.dice.proposition.StrictReadRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeoutException

class PolicyReadContractTest {
    enum class Backend { MEMORY, JSON, JSON_RELOADED }

    @TempDir
    lateinit var directory: Path

    private val asOf = Instant.parse("2026-01-20T00:00:00Z")
    private val scope = ContextReadScope.ofValues(listOf("B", "A", "A"))
    private val spec = ScopedReadSpec.of(scope)
    private val clock = ReadClock(asOf, 0.7)
    private val timeout = Duration.ofSeconds(5)

    private fun row(id: String, context: String, text: String = "alpha beta", confidence: Double = 0.8) = Proposition(
        id = id, contextId = ContextId(context), text = text, mentions = emptyList(), confidence = confidence,
        created = asOf, contentRevised = asOf, metadataRevised = asOf, lastAccessed = asOf,
    )

    private fun key(row: Proposition) = PropositionKey(row.contextId.value, row.id)

    /** The local strict engine over a row list, looking rows up by id the way both stores do. */
    private fun engine(nanoTime: () -> Long = System::nanoTime, rows: () -> List<Proposition>) =
        LocalPolicyReadOperations(LocalScopedReadOperations(nanoTime, rows)) { id -> rows().firstOrNull { it.id == id } }
    private fun grant(row: Proposition, text: ApprovedText = ApprovedText.Stored) =
        StrictReadRow(key(row), text)

    private fun repository(
        backend: Backend, rows: List<Proposition>, embedder: EmbeddingService? = null,
    ): PropositionRepository {
        val path = directory.resolve("${backend.name}.json")
        val repository = if (backend == Backend.MEMORY) InMemoryPropositionRepository(embedder)
        else JsonFilePropositionRepository(path, embedder)
        repository.saveAll(rows.reversed())
        return if (backend == Backend.JSON_RELOADED) JsonFilePropositionRepository(path, embedder) else repository
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `strict listing intersects grants before K and uses only list order`(backend: Backend) {
        val a = row("a", "A", confidence = 1.0)
        val b = row("b", "B", confidence = 0.1)
        val hidden = row("hidden", "B", confidence = 1.0)
        val forbidden = row("forbidden", "C", confidence = 1.0)
        val repository = repository(backend, listOf(a, b, hidden, forbidden))
        val reads = repository as StrictPolicyReadCapable
        assertTrue(reads.supportsStrictPolicyReads)
        val grants = listOf(grant(b), grant(a))
        assertEquals(listOf("b"), reads.queryByGrantOrder(spec, clock, grants, 1, timeout).map { it.id })
        assertEquals(listOf("b", "a"), reads.queryByGrantOrder(spec, clock, grants, 10, timeout).map { it.id })
        val before = repository.findAll().associateBy { it.id }
        assertEquals(listOf("b"), reads.keywordByApprovedText(spec, clock, grants, listOf("alpha"), 1, timeout).map { it.id })
        assertEquals(before, repository.findAll().associateBy { it.id })
        assertEquals(listOf(a), reads.queryByGrantOrder(spec, clock, listOf(grant(a)), 1, timeout))
        assertTrue(reads.queryByGrantOrder(spec, clock, listOf(StrictReadRow(PropositionKey("A", "b"), ApprovedText.Stored)), 1, timeout).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `strict rows rank by their place in the request list rather than by key`(backend: Backend) {
        // Key order, storage order and confidence would all put a first, so only the list order gives b, a.
        val a = row("a", "A", confidence = 1.0)
        val b = row("b", "A", confidence = 0.1)
        val reads = repository(backend, listOf(b, a)) as StrictPolicyReadCapable
        val grants = listOf(grant(b), grant(a))
        assertEquals(listOf("b", "a"), reads.queryByGrantOrder(spec, clock, grants, 10, timeout).map { it.id })
        assertEquals(listOf("b", "a"), reads.keywordByApprovedText(spec, clock, grants, listOf("alpha"), 10, timeout).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `approved replacement and omitted text cannot read the stored text`(backend: Backend) {
        val literal = row("literal", "A", "hidden secret alpha beta", 1.0)
        val stored = row("stored", "B", "ALPHA BETA", 0.1)
        val omitted = row("omitted", "B", "alpha beta secret", 1.0)
        val hidden = row("hidden", "B", "alpha beta secret", 1.0)
        val reads = repository(backend, listOf(literal, stored, omitted, hidden, row("forbidden", "C"))) as StrictPolicyReadCapable
        val grants = listOf(
            grant(literal, ApprovedText.Literal("beta")),
            grant(omitted, ApprovedText.Omitted), grant(stored),
        )
        assertEquals(listOf("stored", "literal"), reads.keywordByApprovedText(spec, clock, grants, listOf(" ALPHA ", "alpha", "BETA", ""), 10, timeout).map { it.id })
        assertTrue(reads.keywordByApprovedText(spec, clock, grants, listOf("secret"), 10, timeout).isEmpty())
        assertEquals(listOf("stored"), reads.keywordByApprovedText(spec, clock, grants, listOf("alpha"), 1, timeout).map { it.id })
        assertEquals(listOf("literal", "stored"), reads.keywordByApprovedText(spec, clock, grants, listOf("beta"), 10, timeout).map { it.id })
        assertEquals(listOf("literal", "omitted", "stored"), reads.queryByGrantOrder(spec, clock, grants, 10, timeout).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `hidden field changes cannot change strict ranks with fixed approved text and order`(backend: Backend) {
        val a = row("a", "A", "hidden alpha", 1.0)
        val b = row("b", "B", "no match", 0.1)
        val hidden = row("hidden", "B", "alpha beta", 1.0)
        val repository = repository(backend, listOf(a, b, hidden, row("forbidden", "C")))
        val reads = repository as StrictPolicyReadCapable
        val grants = listOf(grant(b, ApprovedText.Literal("alpha beta")), grant(a, ApprovedText.Literal("alpha")))
        fun ranks() = listOf(
            reads.queryByGrantOrder(spec, clock, grants, 10, timeout).map(::key),
            reads.keywordByApprovedText(spec, clock, grants, listOf("alpha", "beta"), 10, timeout).map(::key),
        )
        val expected = ranks()
        repository.save(a.copy(text = "beta", confidence = 0.0, decay = 1.0, importance = 0.0, reinforceCount = 0, created = Instant.EPOCH, sourceIds = listOf("secret-a")))
        repository.save(b.copy(text = "secret secret", confidence = 1.0, importance = 1.0, reinforceCount = 100, metadata = mapOf("hidden" to "beta")))
        repository.save(hidden.copy(text = "alpha", confidence = 0.0, status = PropositionStatus.STALE, grounding = listOf("secret")))
        assertEquals(expected, ranks())
        assertEquals(listOf(PropositionKey("B", "b"), PropositionKey("A", "a")), expected[1])
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `strict membership retains included keys exclusions and every fragment before ranking`(backend: Backend) {
        val a = row("a", "A")
        val allowed = row("allowed", "B").copy(importance = 0.9, pinned = true)
        val excluded = row("excluded", "B").copy(importance = 0.9, pinned = true)
        val low = row("low", "B").copy(importance = 0.1, pinned = true)
        val unpinned = row("unpinned", "B").copy(importance = 0.9)
        val notIncluded = row("not-included", "B").copy(importance = 0.9, pinned = true)
        val rows = listOf(a, allowed, excluded, low, unpinned, notIncluded)
        val reads = repository(backend, rows + row("forbidden", "C")) as StrictPolicyReadCapable
        val grants = rows.map { grant(it) }
        val restricted = spec.includingOnly(rows.filter { it != notIncluded }.map(::key))
            .excluding(listOf(key(excluded)))
            .restrict(PropositionQuery(contextId = ContextId("B"), pinned = true))
            .restrict(PropositionQuery(minImportance = 0.7))
        assertEquals(listOf(allowed), reads.queryByGrantOrder(restricted, clock, grants, 1, timeout))
        assertEquals(listOf(allowed), reads.keywordByApprovedText(restricted, clock, grants, listOf("alpha"), 1, timeout))
        val denied = restricted.includingOnly(emptyList())
        assertTrue(reads.queryByGrantOrder(denied, clock, grants, 10, timeout).isEmpty())
        assertTrue(reads.keywordByApprovedText(denied, clock, grants, listOf("alpha"), 10, timeout).isEmpty())
        val impossible = restricted.restrict(PropositionQuery(contextId = ContextId("A")))
        assertTrue(reads.queryByGrantOrder(impossible, clock, grants, 10, timeout).isEmpty())
        assertTrue(reads.keywordByApprovedText(impossible, clock, grants, listOf("alpha"), 10, timeout).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `ordinary count vector and direct keys reuse the same included row intersection`(backend: Backend) {
        val service = mock<EmbeddingService>()
        whenever(service.embed(any<String>())).thenReturn(floatArrayOf(1f, 0f))
        val a = row("a", "A")
        val b = row("b", "B")
        val hidden = row("hidden", "B")
        val repository = repository(backend, listOf(a, b, hidden, row("forbidden", "C")), service)
        val session = ScopedPropositionReader.open(repository, scope).snapshot(asOf, 0.7)
            .includingOnly(listOf(key(a), key(b))).excluding(listOf(key(a)))
        assertEquals(1L, session.count())
        assertEquals(listOf(b), session.query(PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, 1))
        assertEquals(listOf(b), session.includingOnly(listOf(key(b), key(hidden))).query(PropositionQuery.OrderBy.NONE, 10))
        assertTrue(session.includingOnly(listOf(key(hidden))).query(PropositionQuery.OrderBy.NONE, 10).isEmpty())
        val vectors = repository as ContextSetVectorSearchCapable
        val result = vectors.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "alpha", topK = 1, similarityThreshold = 0.0),
            spec.includingOnly(listOf(key(b))), clock, ExactVectorBudget(1, timeout),
        )
        assertEquals(listOf(b), result.map { it.match })
        assertEquals(0L, session.includingOnly(emptyList()).count())
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `invalid strict operations fail before any partial result and leave rows intact`(backend: Backend) {
        val a = row("a", "A")
        val repository = repository(backend, listOf(a, row("hidden", "B"), row("forbidden", "C")))
        val reads = repository as StrictPolicyReadCapable
        val valid = listOf(grant(a))
        val invalidRows = listOf(
            valid + valid,
            valid + grant(row("c", "C")),
            List(1001) { StrictReadRow(PropositionKey("A", "row-$it"), ApprovedText.Omitted) },
        )
        for (grants in invalidRows) {
            assertThrows(IllegalArgumentException::class.java) { reads.queryByGrantOrder(spec, clock, grants, 1, timeout) }
            assertThrows(IllegalArgumentException::class.java) { reads.keywordByApprovedText(spec, clock, grants, listOf("alpha"), 1, timeout) }
        }
        for (duration in listOf(Duration.ZERO, Duration.ofNanos(-1), timeout.plusNanos(1))) {
            assertThrows(IllegalArgumentException::class.java) { reads.queryByGrantOrder(spec, clock, valid, 1, duration) }
            assertThrows(IllegalArgumentException::class.java) { reads.keywordByApprovedText(spec, clock, valid, listOf("alpha"), 1, duration) }
        }
        for (limit in listOf(-1, 1001)) {
            assertThrows(IllegalArgumentException::class.java) { reads.queryByGrantOrder(spec, clock, valid, limit, timeout) }
            assertThrows(IllegalArgumentException::class.java) { reads.keywordByApprovedText(spec, clock, valid, listOf("alpha"), limit, timeout) }
        }
        val before = repository.findAll().associateBy { it.id }
        assertEquals(listOf(a), reads.queryByGrantOrder(spec, clock, valid, 1, timeout))
        assertEquals(1L, reads.count(spec.includingOnly(listOf(key(a))), clock))
        assertEquals(before, repository.findAll().associateBy { it.id })
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `empty strict inputs and zero limits return no candidates`(backend: Backend) {
        val a = row("a", "A")
        val reads = repository(backend, listOf(a)) as StrictPolicyReadCapable
        assertTrue(reads.queryByGrantOrder(spec, clock, emptyList(), 10, timeout).isEmpty())
        assertTrue(reads.keywordByApprovedText(spec, clock, emptyList(), listOf("alpha"), 10, timeout).isEmpty())
        assertTrue(reads.queryByGrantOrder(spec, clock, listOf(grant(a)), 0, timeout).isEmpty())
        assertTrue(reads.keywordByApprovedText(spec, clock, listOf(grant(a)), listOf("alpha"), 0, timeout).isEmpty())
        assertTrue(reads.keywordByApprovedText(spec, clock, listOf(grant(a)), listOf("", " "), 10, timeout).isEmpty())
    }

    @Test
    fun `strict reads fetch granted rows by id without scanning the store`() {
        val a = row("a", "A")
        val b = row("b", "B")
        var scans = 0
        val looked = mutableListOf<String>()
        val engine = LocalPolicyReadOperations(LocalScopedReadOperations { scans++; listOf(a, b) }) { id ->
            looked += id
            listOf(a, b).firstOrNull { it.id == id }
        }
        val grants = listOf(grant(b), grant(a))
        assertEquals(listOf(b, a), engine.queryByGrantOrder(spec, clock, grants, 10, timeout))
        assertEquals(listOf(b, a), engine.keywordByApprovedText(spec, clock, grants, listOf("alpha"), 10, timeout))
        assertEquals(listOf("b", "a", "b", "a"), looked)
        assertEquals(0, scans)
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `a grant never returns a row another context stored under the same id`(backend: Backend) {
        val other = row("same", "B")
        val reads = repository(backend, listOf(other)) as StrictPolicyReadCapable
        val foreign = listOf(StrictReadRow(PropositionKey("A", "same"), ApprovedText.Stored))
        assertTrue(reads.queryByGrantOrder(spec, clock, foreign, 10, timeout).isEmpty())
        assertTrue(reads.keywordByApprovedText(spec, clock, foreign, listOf("alpha"), 10, timeout).isEmpty())
        val own = listOf(grant(other))
        assertEquals(listOf(other), reads.queryByGrantOrder(spec, clock, own, 10, timeout))
        assertEquals(listOf(other), reads.keywordByApprovedText(spec, clock, own, listOf("alpha"), 10, timeout))
        val excluded = spec.excluding(listOf(key(other)))
        assertTrue(reads.queryByGrantOrder(excluded, clock, own, 10, timeout).isEmpty())
        assertTrue(reads.keywordByApprovedText(excluded, clock, own, listOf("alpha"), 10, timeout).isEmpty())
    }

    @Test
    fun `strict grants exclude hidden rows before reading membership fields`() {
        val a = row("a", "A")
        val hidden = row("hidden", "B").copy(metadata = object : Map<String, Any> by emptyMap() {
            override fun get(key: String): Any = error("Hidden metadata was read")
        })
        val engine = engine { listOf(hidden, a, row("c", "C")) }
        val restricted = ScopedReadSpec.of(scope).restrict(PropositionQuery(minTrustScore = 0.5))
        assertEquals(listOf(a), engine.queryByGrantOrder(restricted, clock, listOf(grant(a)), 1, timeout))
        assertEquals(listOf(a), engine.keywordByApprovedText(restricted, clock, listOf(grant(a)), listOf("alpha"), 1, timeout))
    }

    @Test
    fun `empty or invalid requests never consult resident rows`() {
        var calls = 0
        val engine = engine { calls++; error("Backend called") }
        assertTrue(engine.queryByGrantOrder(ScopedReadSpec.of(scope), clock, emptyList(), 1, Duration.ofNanos(1)).isEmpty())
        assertTrue(engine.keywordByApprovedText(ScopedReadSpec.of(scope), clock, emptyList(), listOf("alpha"), 1, Duration.ofNanos(1)).isEmpty())
        val a = row("a", "A")
        assertTrue(engine.queryByGrantOrder(ScopedReadSpec.of(scope), clock, listOf(grant(a)), 0, timeout).isEmpty())
        assertTrue(engine.keywordByApprovedText(ScopedReadSpec.of(scope), clock, listOf(grant(a)), listOf(" "), 1, timeout).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { engine.queryByGrantOrder(ScopedReadSpec.of(scope), clock, listOf(grant(a), grant(a)), 1, timeout) }
        assertThrows(IllegalArgumentException::class.java) { engine.keywordByApprovedText(ScopedReadSpec.of(scope), clock, listOf(grant(a), grant(a)), listOf("alpha"), 1, timeout) }
        assertEquals(0, calls)
    }

    @Test
    fun `a slow resident read fails both strict reads with a timeout cause`() {
        val a = row("a", "A")
        var ticks = 0L
        val limit = Duration.ofMillis(1)
        val engine = engine({ ticks }) {
            ticks += limit.toNanos()
            listOf(a)
        }
        val grants = listOf(grant(a))
        listOf<() -> Unit>(
            { engine.queryByGrantOrder(ScopedReadSpec.of(scope), clock, grants, 1, limit) },
            { engine.keywordByApprovedText(ScopedReadSpec.of(scope), clock, grants, listOf("alpha"), 1, limit) },
        ).forEach { read ->
            val failure = assertThrows(ScopedReadException::class.java) { read() }
            assertEquals(ScopedReadFailure.SCOPED_READ_FAILED, failure.failure)
            assertTrue(failure.cause is TimeoutException)
        }
    }

    @Test
    fun `a strict read that finishes one nanosecond inside its limit succeeds`() {
        val a = row("a", "A")
        var ticks = 0L
        val limit = Duration.ofMillis(1)
        val engine = engine({ ticks }) {
            ticks += limit.toNanos() - 1
            listOf(a)
        }
        assertEquals(listOf(a), engine.queryByGrantOrder(ScopedReadSpec.of(scope), clock, listOf(grant(a)), 1, limit))
        ticks = 0L
        assertEquals(listOf(a), engine.keywordByApprovedText(ScopedReadSpec.of(scope), clock, listOf(grant(a)), listOf("alpha"), 1, limit))
    }

    @Test
    fun `late resident failures cannot publish a partial list keyword result or count`() {
        val a = row("a", "A")
        val broken = object : AbstractList<Proposition>() {
            override val size = 2
            override fun get(index: Int): Proposition = if (index == 0) a else error("Late read failure")
        }
        val scoped = LocalScopedReadOperations { broken }
        val engine = LocalPolicyReadOperations(scoped) { id -> broken.firstOrNull { it.id == id } }
        val grants = listOf(grant(a), grant(row("late", "A")))
        var published: List<Proposition>? = null
        assertThrows(IllegalStateException::class.java) { published = engine.queryByGrantOrder(ScopedReadSpec.of(scope), clock, grants, 1, timeout) }
        assertNull(published)
        assertThrows(IllegalStateException::class.java) { published = engine.keywordByApprovedText(ScopedReadSpec.of(scope), clock, grants, listOf("alpha"), 1, timeout) }
        assertNull(published)
        var count: Long? = null
        assertThrows(IllegalStateException::class.java) { count = scoped.count(ScopedReadSpec.of(scope).includingOnly(listOf(key(a))), clock) }
        assertNull(count)
    }

    @Test
    fun `normalization uses root locale and strict rows keep grant order`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val a = row("a", "A", "INDIGO")
            val b = row("b", "B", "INDIGO")
            val engine = engine { listOf(a, b) }
            val grants = listOf(grant(b), grant(a))
            assertEquals(listOf(b, a), engine.keywordByApprovedText(ScopedReadSpec.of(scope), clock, grants, listOf(" INDIGO ", "indigo"), 2, timeout))
            assertEquals(listOf(b, a), engine.queryByGrantOrder(ScopedReadSpec.of(scope), clock, grants, 2, timeout))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
