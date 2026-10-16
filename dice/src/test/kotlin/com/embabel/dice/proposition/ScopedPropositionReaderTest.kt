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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration
import java.time.Instant

class ScopedPropositionReaderTest {
    private val scope = ContextReadScope.ofValues(listOf("A", "B"))
    private val instant = Instant.parse("2026-01-01T00:00:00Z")
    private val request = TextSimilaritySearchRequest(query = "private query", topK = 10, similarityThreshold = 0.5)

    private open class LegacyStore : PropositionRepository by mock(PropositionRepository::class.java) {
        var legacyCalls = 0
        override fun query(query: PropositionQuery): List<Proposition> { legacyCalls++; error("legacy query") }
        override fun findAll(): List<Proposition> { legacyCalls++; error("legacy findAll") }
        override fun count(query: PropositionQuery): Int { legacyCalls++; error("legacy count") }
        override fun keywordOverlap(base: PropositionQuery, tokens: List<String>, limit: Int): List<Proposition> {
            legacyCalls++; error("legacy keywords")
        }
        override fun findSimilarWithScores(
            textSimilaritySearchRequest: TextSimilaritySearchRequest,
        ): List<SimilarityResult<Proposition>> { legacyCalls++; error("global vector") }
    }

    private open class NativeStore : LegacyStore(), ContextSetReadCapable {
        override var supportsScopedReads = true
        var rows = listOf(row("A", "shared"), row("B", "shared"), row("C", "secret"))
        var returnedRows: List<Proposition>? = null
        var failure: RuntimeException? = null
        var reportedCount: Long? = null
        val calls = mutableListOf<String>()
        val specs = mutableListOf<ScopedReadSpec>()
        var receivedLimit: Int? = null
        var receivedTokens: List<String>? = null
        fun record(operation: String, spec: ScopedReadSpec) {
            calls += operation
            specs += spec
            failure?.let { throw it }
        }
        fun eligible(scope: ContextReadScope, spec: ScopedReadSpec): List<Proposition> = rows.filter {
            val key = PropositionKey(it.contextId.value, it.id)
            it.contextId.value in scope.contextIdValues &&
                (spec.includedKeys == null || key in spec.includedKeys) && key !in spec.excludedKeys &&
                spec.fragments.all { fragment -> fragment.matchesFilters(it, spec.asOf) }
        }
        override fun query(scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int): List<Proposition> {
            record("query", spec)
            receivedLimit = limit
            val eligible = eligible(scope, spec)
            val ordered = when (order) {
                ReadOrder.NONE -> eligible
                ReadOrder.EFFECTIVE_CONFIDENCE_DESC -> eligible.sortedByDescending {
                    it.effectiveConfidenceAt(spec.asOf, spec.rankingDecayK)
                }
                ReadOrder.CREATED_DESC -> eligible.sortedByDescending { it.created }
                ReadOrder.REVISED_DESC -> eligible.sortedByDescending { it.lastTouched }
                ReadOrder.LAST_ACCESSED_DESC -> eligible.sortedByDescending { it.lastAccessed }
                ReadOrder.REINFORCE_COUNT_DESC -> eligible.sortedByDescending { it.reinforceCount }
                ReadOrder.IMPORTANCE_DESC -> eligible.sortedByDescending { it.importance }
            }
            return returnedRows ?: ordered.take(limit)
        }
        override fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long {
            record("count", spec)
            return reportedCount ?: eligible(scope, spec).size.toLong()
        }
        override fun keywordOverlap(
            scope: ContextReadScope, spec: ScopedReadSpec, tokens: List<String>, limit: Int,
        ): List<Proposition> {
            record("keyword", spec)
            receivedTokens = tokens
            receivedLimit = limit
            return returnedRows ?: eligible(scope, spec).filter { p ->
                tokens.any { p.text.contains(it, ignoreCase = true) }
            }.take(limit)
        }
    }

    private class VectorStore : NativeStore(), ContextSetVectorSearchCapable {
        override var supportsScopedVectors = true
        var receivedBudget: ExactVectorBudget? = null
        var vectorRows: List<SimilarityResult<Proposition>>? = null
        override fun findSimilarWithScores(
            request: TextSimilaritySearchRequest, scope: ContextReadScope, spec: ScopedReadSpec, budget: ExactVectorBudget,
        ): List<SimilarityResult<Proposition>> {
            record("vector", spec)
            receivedBudget = budget
            receivedLimit = request.topK
            return vectorRows ?: eligible(scope, spec).take(request.topK).map { SimilarityResult.create(it, 0.8) }
        }
    }

    private fun newRequest(topK: Int = 10, similarityThreshold: Double = 0.5) =
        TextSimilaritySearchRequest(query = "private query", topK = topK, similarityThreshold = similarityThreshold)

    private fun session(store: PropositionStore, limits: ScopedReadLimits = ScopedReadLimits.DEFAULT) =
        ScopedPropositionReader.open(store, scope, limits).snapshot(instant, 3.0)

    private fun failure(code: String, operation: () -> Unit): ScopedReadException {
        val ex = assertThrows(ScopedReadException::class.java, operation)
        assertEquals(code, ex.code)
        assertEquals(code, ex.message)
        assertNull(ex.cause)
        assertTrue(ex.suppressed.isEmpty())
        return ex
    }

    @Test
    fun `legacy repository is unsupported without any inherited read fallback`() {
        val store = LegacyStore()
        failure("SCOPED_READ_UNSUPPORTED") { ScopedPropositionReader.open(store, scope) }
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `runtime support checked on open and every terminal even with zero work`() {
        val store = VectorStore()
        store.supportsScopedReads = false
        failure("SCOPED_READ_UNSUPPORTED") { ScopedPropositionReader.open(store, scope) }
        store.supportsScopedReads = true
        val read = session(store)
        store.supportsScopedReads = false
        failure("SCOPED_READ_UNSUPPORTED") { read.query(ReadOrder.NONE, 0) }
        failure("SCOPED_READ_UNSUPPORTED") { read.count() }
        failure("SCOPED_READ_UNSUPPORTED") { read.keywordOverlap(emptyList(), 0) }
        failure("SCOPED_READ_UNSUPPORTED") { read.findSimilarWithScores(newRequest(topK = 0)) }
        assertTrue(store.calls.isEmpty())
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `vectors require explicit capability and enabled runtime flag before any probe`() {
        val nonvector = NativeStore()
        failure("SCOPED_VECTOR_UNSUPPORTED") { session(nonvector).findSimilarWithScores(request) }
        val vector = VectorStore().apply { supportsScopedVectors = false }
        val read = session(vector)
        failure("SCOPED_VECTOR_UNSUPPORTED") { read.findSimilarWithScores(request) }
        failure("SCOPED_VECTOR_UNSUPPORTED") { read.findSimilarWithScores(newRequest(topK = 0)) }
        assertTrue(nonvector.calls.isEmpty())
        assertTrue(vector.calls.isEmpty())
        assertEquals(0, nonvector.legacyCalls + vector.legacyCalls)
    }

    @Test
    fun `limits are immutable configurable finite and probe covers output and eager allowances`() {
        val defaults = ScopedReadLimits.DEFAULT
        assertEquals(100, defaults.maxResults)
        assertEquals(401, defaults.maxProbe)
        assertEquals(100, defaults.maxEager)
        assertSame(ExactVectorBudget.DEFAULT, defaults.vectorBudget)
        val budget = ExactVectorBudget(3, Duration.ofSeconds(1))
        val limits = ScopedReadLimits { maxResults = 2; maxProbe = 4; maxEager = 3; vectorBudget = budget }
        assertEquals(2, limits.maxResults)
        assertEquals(4, limits.maxProbe)
        assertEquals(3, limits.maxEager)
        assertSame(budget, limits.vectorBudget)
        assertEquals(1, limits.withMaxResults(1).maxResults)
        assertEquals(5, limits.withMaxProbe(5).maxProbe)
        assertEquals(1, limits.withMaxEager(1).maxEager)
        assertSame(budget, defaults.withVectorBudget(budget).vectorBudget)
        for (value in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            failure("INVALID_SCOPED_READ") { ScopedReadLimits(value, 401, 100, budget) }
            failure("INVALID_SCOPED_READ") { ScopedReadLimits(100, value, 100, budget) }
            failure("INVALID_SCOPED_READ") { ScopedReadLimits(100, 401, value, budget) }
        }
        failure("INVALID_SCOPED_READ") { limits.withMaxProbe(1) }
        failure("INVALID_SCOPED_READ") { limits.withMaxEager(5) }
    }

    @Test
    fun `terminal requests admit default probe capacity and reject maxProbe plus one before reading`() {
        val store = VectorStore()
        val read = session(store)
        read.query(ReadOrder.NONE, 401)
        assertEquals(401, store.receivedLimit)
        read.keywordOverlap(listOf("fact"), 401)
        assertEquals(401, store.receivedLimit)
        read.findSimilarWithScores(newRequest(topK = 401))
        assertEquals(401, store.receivedLimit)
        store.calls.clear()
        for (limit in listOf(-1, 402, Int.MAX_VALUE)) {
            failure("INVALID_SCOPED_READ") { read.query(ReadOrder.NONE, limit) }
            failure("INVALID_SCOPED_READ") { read.keywordOverlap(emptyList(), limit) }
            failure("INVALID_SCOPED_READ") { read.findSimilarWithScores(newRequest(topK = limit)) }
        }
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `zero suppresses only requested work after validation and count remains eligible Long total`() {
        val store = VectorStore()
        val read = session(store, ScopedReadLimits(0, 0, 0, ExactVectorBudget.DEFAULT))
        assertTrue(read.query(ReadOrder.NONE, 0).isEmpty())
        assertTrue(read.keywordOverlap(listOf("fact"), 0).isEmpty())
        assertTrue(read.findSimilarWithScores(newRequest(topK = 0)).isEmpty())
        assertTrue(store.calls.isEmpty())
        assertEquals(2L, read.count())
        store.reportedCount = Int.MAX_VALUE.toLong() + 10L
        assertEquals(Int.MAX_VALUE.toLong() + 10L, read.count())
        store.reportedCount = -1
        failure("SCOPED_READ_RESULT_INVALID") { read.count() }
    }

    @Test
    fun `reader and session narrowing copy collections and preserve compound-key intersections`() {
        val store = VectorStore()
        val allowed = mutableListOf(PropositionKey("A", "shared"), PropositionKey("B", "shared"))
        val omitted = mutableListOf(PropositionKey("B", "shared"))
        val base = ScopedPropositionReader.open(store, scope)
        val bound = base.includingOnly(allowed).excluding(omitted)
        allowed.clear()
        omitted.clear()
        val read = bound.snapshot(instant, 3.0)
        assertEquals(listOf("A"), read.query(ReadOrder.NONE, 10).map { it.contextId.value })
        assertEquals(1L, read.count())
        assertEquals(listOf("A"), read.keywordOverlap(listOf("fact"), 10).map { it.contextId.value })
        assertEquals(listOf("A"), read.findSimilarWithScores(request).map { it.match.contextId.value })
        assertEquals(2L, base.snapshot(instant, 3.0).count())
        val narrowed = read.includingOnly(listOf(PropositionKey("B", "shared")))
        assertEquals(0L, narrowed.count())
        assertEquals(1L, read.count())
        assertEquals(0L, read.excluding(listOf(PropositionKey("A", "shared"))).count())
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `one frozen instant and separate fragment and ranking decay survive every terminal`() {
        val store = VectorStore()
        val statuses = mutableSetOf(PropositionStatus.ACTIVE)
        val reader = ScopedPropositionReader.open(store, scope).restrict(
            PropositionQuery(statuses = statuses, minEffectiveConfidence = 0.0, decayK = 0.5, limit = 1,
                orderBy = PropositionQuery.OrderBy.CREATED_DESC),
        )
        statuses.clear()
        statuses += PropositionStatus.STALE
        val read = reader.snapshot(instant, 9.0).restrict(PropositionQuery(minConfidence = 0.1, decayK = 7.0))
        read.query(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 10)
        read.count()
        read.keywordOverlap(listOf("fact"), 10)
        read.findSimilarWithScores(request)
        for (spec in store.specs) {
            assertSame(read.spec, spec)
            assertEquals(instant, spec.asOf)
            assertEquals(9.0, spec.rankingDecayK)
            assertEquals(listOf(0.5, 7.0), spec.fragments.map { it.decayK })
            assertTrue(spec.fragments.all { it.effectiveConfidenceAsOf == instant && it.limit == null })
            assertEquals(setOf(PropositionStatus.ACTIVE), spec.fragments.first().statuses)
        }
        assertEquals(2L, read.count(), "a stripped presentation limit must not cap eligible count")
    }

    @Test
    fun `fragment decay gates membership while ranking decay independently orders the same frozen rows`() {
        val store = NativeStore()
        val yesterday = instant.minus(Duration.ofDays(1))
        store.rows = listOf(
            row("A", "old-high").copy(confidence = 0.95, decay = 0.1,
                contentRevised = yesterday, lastAccessed = yesterday),
            row("B", "fresh-low").copy(confidence = 0.6, decay = 0.1,
                contentRevised = instant, lastAccessed = instant),
        )
        val read = ScopedPropositionReader.open(store, scope)
            .restrict(PropositionQuery(minEffectiveConfidence = 0.5, decayK = 0.1))
            .restrict(PropositionQuery(belowEffectiveConfidence = 1.0, decayK = 7.0))
            .snapshot(instant, 9.0)
        assertEquals(2L, read.count(), "both fragments must use their own decay rate")
        repeat(2) {
            assertEquals(listOf("fresh-low", "old-high"),
                read.query(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 10).map { it.id })
        }
        assertEquals(instant, read.spec.asOf)
    }

    @Test
    fun `outsider restrictions and keys and conflicting clocks fail without backend calls`() {
        val store = VectorStore()
        val reader = ScopedPropositionReader.open(store, scope)
        val read = reader.snapshot(instant, 3.0)
        val outside = listOf(PropositionKey("C", "secret"))
        failure("INVALID_SCOPED_READ") { reader.restrict(PropositionQuery.againstContext("C")) }
        failure("INVALID_SCOPED_READ") { read.restrict(PropositionQuery.againstContext("C")) }
        failure("INVALID_SCOPED_READ") { reader.includingOnly(outside) }
        failure("INVALID_SCOPED_READ") { reader.excluding(outside) }
        failure("INVALID_SCOPED_READ") { read.includingOnly(outside) }
        failure("INVALID_SCOPED_READ") { read.excluding(outside) }
        failure("INVALID_SCOPED_READ") {
            read.restrict(PropositionQuery(effectiveConfidenceAsOf = instant.plusSeconds(1)))
        }
        failure("INVALID_SCOPED_READ") {
            reader.restrict(PropositionQuery(effectiveConfidenceAsOf = instant.plusSeconds(1))).snapshot(instant, 3.0)
        }
        assertTrue(store.calls.isEmpty())
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `invalid finite bounds fail even when requested work is zero`() {
        val store = VectorStore()
        val reader = ScopedPropositionReader.open(store, scope)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            failure("INVALID_SCOPED_READ") { reader.snapshot(instant, value) }
            failure("INVALID_SCOPED_READ") { reader.restrict(PropositionQuery(decayK = value)) }
            failure("INVALID_SCOPED_READ") { session(store).restrict(PropositionQuery(minConfidence = value)) }
            failure("INVALID_SCOPED_READ") {
                session(store).findSimilarWithScores(newRequest(topK = 0, similarityThreshold = value))
            }
        }
        failure("INVALID_SCOPED_READ") {
            session(store).findSimilarWithScores(newRequest(topK = 0, similarityThreshold = 1.1))
        }
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `foreign owner anywhere in result batch fails atomically across all list terminals`() {
        val store = VectorStore()
        val read = session(store)
        store.returnedRows = listOf(row("A", "visible"), row("C", "secret"))
        failure("SCOPED_READ_RESULT_INVALID") { read.query(ReadOrder.NONE, 10) }
        failure("SCOPED_READ_RESULT_INVALID") { read.keywordOverlap(listOf("fact"), 10) }
        store.vectorRows = store.returnedRows!!.map { SimilarityResult.create(it, 0.9) }
        failure("SCOPED_READ_RESULT_INVALID") { read.findSimilarWithScores(request) }
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `same ID with wrong allowed owner fails included grants across all list terminals`() {
        val store = VectorStore()
        val read = session(store).includingOnly(listOf(PropositionKey("A", "shared")))
        store.returnedRows = listOf(row("B", "shared"))
        failure("SCOPED_READ_RESULT_INVALID") { read.query(ReadOrder.NONE, 10) }
        failure("SCOPED_READ_RESULT_INVALID") { read.keywordOverlap(listOf("fact"), 10) }
        store.vectorRows = store.returnedRows!!.map { SimilarityResult.create(it, 0.9) }
        failure("SCOPED_READ_RESULT_INVALID") { read.findSimilarWithScores(request) }
    }

    @Test
    fun `excluded key or structured membership violation fails across all list terminals`() {
        for (readBuilder in listOf<(ScopedReadSession) -> ScopedReadSession>(
            { it.excluding(listOf(PropositionKey("A", "shared"))) },
            { it.restrict(PropositionQuery(minConfidence = 1.0)) },
        )) {
            val store = VectorStore()
            val read = readBuilder(session(store))
            store.returnedRows = listOf(row("A", "shared"))
            failure("SCOPED_READ_RESULT_INVALID") { read.query(ReadOrder.NONE, 10) }
            failure("SCOPED_READ_RESULT_INVALID") { read.keywordOverlap(listOf("fact"), 10) }
            store.vectorRows = store.returnedRows!!.map { SimilarityResult.create(it, 0.9) }
            failure("SCOPED_READ_RESULT_INVALID") { read.findSimilarWithScores(request) }
        }
    }

    @Test
    fun `duplicate compound keys blank IDs and oversized native batches fail atomically`() {
        val store = VectorStore()
        for (rows in listOf(listOf(row("A", "same"), row("A", "same")), listOf(row("A", " ")))) {
            store.returnedRows = rows
            failure("SCOPED_READ_RESULT_INVALID") { session(store).query(ReadOrder.NONE, 10) }
            store.vectorRows = rows.map { SimilarityResult.create(it, 0.9) }
            failure("SCOPED_READ_RESULT_INVALID") { session(store).findSimilarWithScores(request) }
        }
        store.returnedRows = listOf(row("A", "one"), row("B", "two"))
        failure("SCOPED_READ_RESULT_INVALID") { session(store).query(ReadOrder.NONE, 1) }
    }

    @Test
    fun `backend failure messages are sanitized and fixed typed vector failures survive unchanged`() {
        val store = VectorStore()
        for (error in listOf(IllegalStateException("private owner C"), UnsupportedOperationException("private query"))) {
            store.failure = error
            failure("SCOPED_READ_FAILED") { session(store).query(ReadOrder.NONE, 10) }
            failure("SCOPED_READ_FAILED") { session(store).count() }
            failure("SCOPED_READ_FAILED") { session(store).keywordOverlap(listOf("private token"), 10) }
            failure("SCOPED_READ_FAILED") { session(store).findSimilarWithScores(request) }
        }
        for (error in listOf(
            FilteredVectorUnsupportedException(), ExactVectorBudgetExceededException(), ExactVectorDeadlineExceededException(),
        )) {
            store.failure = error
            val thrown = assertThrows(error.javaClass) { session(store).findSimilarWithScores(request) }
            assertSame(error, thrown)
        }
        failure("SCOPED_READ_FAILED") { throw ScopedReadException("sensitive arbitrary message") }
    }

    @Test
    fun `native vector receives finite configured budget and returns no invalid scores`() {
        val store = VectorStore()
        val budget = ExactVectorBudget(2, Duration.ofNanos(1))
        val read = session(store, ScopedReadLimits.DEFAULT.withVectorBudget(budget))
        assertEquals(2, read.findSimilarWithScores(request).size)
        assertSame(budget, store.receivedBudget)
        for (score in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.4)) {
            store.vectorRows = listOf(SimilarityResult.create(row("A", "one"), score))
            failure("SCOPED_READ_RESULT_INVALID") { read.findSimilarWithScores(request) }
        }
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `withLimits returns a new binding and all read orders reach native query`() {
        val store = NativeStore()
        val base = ScopedPropositionReader.open(store, scope)
        val reduced = base.withLimits(ScopedReadLimits(1, 2, 1, ExactVectorBudget.DEFAULT))
        assertSame(ScopedReadLimits.DEFAULT, base.limits)
        assertEquals(2, reduced.limits.maxProbe)
        for (order in ReadOrder.entries) {
            assertEquals(2, reduced.snapshot(instant, 3.0).query(order, 2).size)
        }
        failure("INVALID_SCOPED_READ") { reduced.snapshot(instant, 3.0).query(ReadOrder.NONE, 3) }
        assertEquals(0, store.legacyCalls)
    }

    companion object {
        private fun row(owner: String, id: String) = Proposition(
            id = id, contextId = ContextId(owner), text = "fact $id", confidence = 0.9,
            mentions = emptyList(), created = Instant.parse("2025-01-01T00:00:00Z"),
            contentRevised = Instant.parse("2025-01-01T00:00:00Z"),
        )
    }
}
