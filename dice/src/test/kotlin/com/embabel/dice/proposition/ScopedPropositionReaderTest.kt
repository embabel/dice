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
import com.embabel.dice.proposition.PropositionQuery.OrderBy
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
        val clocks = mutableListOf<ReadClock>()
        var receivedLimit: Int? = null
        var receivedTokens: List<String>? = null
        fun record(operation: String, spec: ScopedReadSpec, clock: ReadClock) {
            calls += operation
            specs += spec
            clocks += clock
            failure?.let { throw it }
        }
        fun eligible(spec: ScopedReadSpec, clock: ReadClock): List<Proposition> = rows.filter {
            val key = PropositionKey(it.contextId.value, it.id)
            it.contextId.value in spec.scope.contextIdValues &&
                (spec.includedKeys == null || key in spec.includedKeys) && key !in spec.excludedKeys &&
                spec.fragments.all { fragment -> fragment.matchesFilters(it, clock.asOf) }
        }
        override fun query(spec: ScopedReadSpec, clock: ReadClock, order: OrderBy, limit: Int): List<Proposition> {
            record("query", spec, clock)
            receivedLimit = limit
            val eligible = eligible(spec, clock)
            val ordered = when (order) {
                OrderBy.NONE -> eligible
                OrderBy.EFFECTIVE_CONFIDENCE_DESC -> eligible.sortedByDescending {
                    it.effectiveConfidenceAt(clock.asOf, clock.rankingDecayK)
                }
                OrderBy.CREATED_DESC -> eligible.sortedByDescending { it.created }
                OrderBy.REVISED_DESC -> eligible.sortedByDescending { it.lastTouched }
                OrderBy.LAST_ACCESSED_DESC -> eligible.sortedByDescending { it.lastAccessed }
                OrderBy.REINFORCE_COUNT_DESC -> eligible.sortedByDescending { it.reinforceCount }
                OrderBy.IMPORTANCE_DESC -> eligible.sortedByDescending { it.importance }
            }
            return returnedRows ?: ordered.take(limit)
        }
        override fun count(spec: ScopedReadSpec, clock: ReadClock): Long {
            record("count", spec, clock)
            return reportedCount ?: eligible(spec, clock).size.toLong()
        }
        override fun keywordOverlap(
            spec: ScopedReadSpec, clock: ReadClock, tokens: List<String>, limit: Int,
        ): List<Proposition> {
            record("keyword", spec, clock)
            receivedTokens = tokens
            receivedLimit = limit
            return returnedRows ?: eligible(spec, clock).filter { p ->
                tokens.any { p.text.contains(it, ignoreCase = true) }
            }.take(limit)
        }
    }

    private class VectorStore : NativeStore(), ContextSetVectorSearchCapable {
        override var supportsScopedVectors = true
        var receivedBudget: ExactVectorBudget? = null
        var vectorRows: List<SimilarityResult<Proposition>>? = null
        override fun findSimilarWithScores(
            request: TextSimilaritySearchRequest, spec: ScopedReadSpec, clock: ReadClock, budget: ExactVectorBudget,
        ): List<SimilarityResult<Proposition>> {
            record("vector", spec, clock)
            receivedBudget = budget
            receivedLimit = request.topK
            return vectorRows ?: eligible(spec, clock).take(request.topK).map { SimilarityResult.create(it, 0.8) }
        }
    }

    private fun newRequest(topK: Int = 10, similarityThreshold: Double = 0.5) =
        TextSimilaritySearchRequest(query = "private query", topK = topK, similarityThreshold = similarityThreshold)

    private fun session(store: PropositionStore, limits: ScopedReadLimits = ScopedReadLimits.DEFAULT) =
        ScopedPropositionReader.open(store, scope, limits).snapshot(instant, 3.0)

    private fun failure(expected: ScopedReadFailure, operation: () -> Unit): ScopedReadException {
        val ex = assertThrows(ScopedReadException::class.java, operation)
        assertEquals(expected, ex.failure)
        assertEquals(expected.name, ex.message)
        assertTrue(ex.suppressed.isEmpty())
        return ex
    }

    @Test
    fun `legacy repository is unsupported without any inherited read fallback`() {
        val store = LegacyStore()
        failure(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { ScopedPropositionReader.open(store, scope) }
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `runtime support checked on open and every terminal even with zero work`() {
        val store = VectorStore()
        store.supportsScopedReads = false
        failure(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { ScopedPropositionReader.open(store, scope) }
        store.supportsScopedReads = true
        val read = session(store)
        store.supportsScopedReads = false
        failure(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { read.query(OrderBy.NONE, 0) }
        failure(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { read.count() }
        failure(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { read.keywordOverlap(emptyList(), 0) }
        failure(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { read.findSimilarWithScores(newRequest(topK = 0)) }
        assertTrue(store.calls.isEmpty())
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `vectors require explicit capability and enabled runtime flag before any probe`() {
        val nonvector = NativeStore()
        failure(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED) { session(nonvector).findSimilarWithScores(request) }
        val vector = VectorStore().apply { supportsScopedVectors = false }
        val read = session(vector)
        failure(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED) { read.findSimilarWithScores(request) }
        failure(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED) { read.findSimilarWithScores(newRequest(topK = 0)) }
        assertTrue(nonvector.calls.isEmpty())
        assertTrue(vector.calls.isEmpty())
        assertEquals(0, nonvector.legacyCalls + vector.legacyCalls)
    }

    @Test
    fun `default limits are a thousand rows and the default budget, and the block matches the withers`() {
        val defaults = ScopedReadLimits.DEFAULT
        assertEquals(1000, ScopedReadLimits.DEFAULT_MAX_ROWS)
        assertEquals(1000, defaults.maxRows)
        assertSame(ExactVectorBudget.DEFAULT, defaults.vectorBudget)
        val fromBlock = ScopedReadLimits { maxRows = 7 }
        val fromWither = defaults.withMaxRows(7)
        assertEquals(fromWither.maxRows, fromBlock.maxRows)
        assertSame(fromWither.vectorBudget, fromBlock.vectorBudget)
        assertEquals(1000, defaults.maxRows)
        val budget = ExactVectorBudget(3, Duration.ofSeconds(1))
        val withBudget = fromWither.withVectorBudget(budget)
        assertSame(budget, withBudget.vectorBudget)
        assertEquals(7, withBudget.maxRows)
        assertSame(ExactVectorBudget.DEFAULT, fromWither.vectorBudget)
        assertSame(budget, ScopedReadLimits { vectorBudget = budget }.vectorBudget)
    }

    @Test
    fun `a terminal accepts maxRows and rejects maxRows plus one and minus one before any store call`() {
        val store = VectorStore()
        val base = ScopedPropositionReader.open(store, scope)
        val read = base.withLimits(ScopedReadLimits.DEFAULT.withMaxRows(2)).snapshot(instant, 3.0)
        assertSame(ScopedReadLimits.DEFAULT, base.limits)
        for (order in OrderBy.entries) {
            assertEquals(2, read.query(order, 2).size)
            assertEquals(2, store.receivedLimit)
        }
        read.keywordOverlap(listOf("fact"), 2)
        assertEquals(2, store.receivedLimit)
        read.findSimilarWithScores(newRequest(topK = 2))
        assertEquals(2, store.receivedLimit)
        store.calls.clear()
        for (limit in listOf(-1, 3, Int.MAX_VALUE)) {
            failure(ScopedReadFailure.INVALID_SCOPED_READ) { read.query(OrderBy.NONE, limit) }
            failure(ScopedReadFailure.INVALID_SCOPED_READ) { read.keywordOverlap(emptyList(), limit) }
            failure(ScopedReadFailure.INVALID_SCOPED_READ) { read.findSimilarWithScores(newRequest(topK = limit)) }
        }
        assertTrue(store.calls.isEmpty())
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `negative maxRows is rejected as an illegal argument`() {
        for (value in listOf(-1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ScopedReadLimits(value, ExactVectorBudget.DEFAULT) }
            assertThrows(IllegalArgumentException::class.java) { ScopedReadLimits.DEFAULT.withMaxRows(value) }
            assertThrows(IllegalArgumentException::class.java) { ScopedReadLimits { maxRows = value } }
        }
        assertEquals(0, ScopedReadLimits(0, ExactVectorBudget.DEFAULT).maxRows)
    }

    @Test
    fun `zero suppresses only requested work after validation and count remains eligible Long total`() {
        val store = VectorStore()
        val read = session(store, ScopedReadLimits(0, ExactVectorBudget.DEFAULT))
        assertTrue(read.query(OrderBy.NONE, 0).isEmpty())
        assertTrue(read.keywordOverlap(listOf("fact"), 0).isEmpty())
        assertTrue(read.findSimilarWithScores(newRequest(topK = 0)).isEmpty())
        assertTrue(store.calls.isEmpty())
        assertEquals(2L, read.count())
        store.reportedCount = Int.MAX_VALUE.toLong() + 10L
        assertEquals(Int.MAX_VALUE.toLong() + 10L, read.count())
        store.reportedCount = -1
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.count() }
    }

    @Test
    fun `one frozen instant and separate fragment and ranking decay survive every terminal`() {
        val store = VectorStore()
        val statuses = mutableSetOf(PropositionStatus.ACTIVE)
        val reader = ScopedPropositionReader.open(store, scope).restrict(
            PropositionQuery(statuses = statuses, minEffectiveConfidence = 0.0, decayK = 0.5),
        )
        statuses.clear()
        statuses += PropositionStatus.STALE
        val read = reader.snapshot(instant, 9.0).restrict(PropositionQuery(minConfidence = 0.1, decayK = 7.0))
        read.query(OrderBy.EFFECTIVE_CONFIDENCE_DESC, 10)
        read.count()
        read.keywordOverlap(listOf("fact"), 10)
        read.findSimilarWithScores(request)
        assertEquals(setOf(ReadClock(instant, 9.0)), store.clocks.toSet())
        for (spec in store.specs) {
            assertEquals(read.spec.fragments, spec.fragments)
            assertEquals(listOf(0.5, 7.0), spec.fragments.map { it.decayK })
            assertTrue(spec.fragments.all { it.effectiveConfidenceAsOf == null && it.limit == null })
            assertEquals(setOf(PropositionStatus.ACTIVE), spec.fragments.first().statuses)
        }
        assertEquals(2L, read.count())
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
                read.query(OrderBy.EFFECTIVE_CONFIDENCE_DESC, 10).map { it.id })
        }
        assertEquals(instant, read.clock.asOf)
    }

    @Test
    fun `outsider restrictions and keys and conflicting clocks fail without backend calls`() {
        val store = VectorStore()
        val reader = ScopedPropositionReader.open(store, scope)
        val read = reader.snapshot(instant, 3.0)
        val outside = listOf(PropositionKey("C", "secret"))
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { reader.restrict(PropositionQuery.againstContext("C")) }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { read.restrict(PropositionQuery.againstContext("C")) }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { reader.includingOnly(outside) }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { reader.excluding(outside) }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { read.includingOnly(outside) }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { read.excluding(outside) }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) {
            read.restrict(PropositionQuery(effectiveConfidenceAsOf = instant.plusSeconds(1)))
        }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) {
            reader.restrict(PropositionQuery(effectiveConfidenceAsOf = instant.plusSeconds(1))).snapshot(instant, 3.0)
        }
        assertTrue(store.calls.isEmpty())
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `criteria carrying a limit or an order are the caller's invalid read and reach no store`() {
        val store = VectorStore()
        val reader = ScopedPropositionReader.open(store, scope)
        val read = reader.snapshot(instant, 3.0)
        val ordered = PropositionQuery(orderBy = OrderBy.CREATED_DESC)
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { reader.restrict(PropositionQuery(limit = 1)) }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { read.restrict(PropositionQuery(limit = 1)) }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { reader.restrict(ordered) }
        failure(ScopedReadFailure.INVALID_SCOPED_READ) { read.restrict(ordered) }
        assertTrue(store.calls.isEmpty())
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `foreign owner anywhere in result batch fails atomically across all list terminals`() {
        val store = VectorStore()
        val read = session(store)
        store.returnedRows = listOf(row("A", "visible"), row("C", "secret"))
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.query(OrderBy.NONE, 10) }
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.keywordOverlap(listOf("fact"), 10) }
        store.vectorRows = store.returnedRows!!.map { SimilarityResult.create(it, 0.9) }
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.findSimilarWithScores(request) }
        assertEquals(0, store.legacyCalls)
    }

    @Test
    fun `same ID with wrong allowed owner fails included grants across all list terminals`() {
        val store = VectorStore()
        val read = session(store).includingOnly(listOf(PropositionKey("A", "shared")))
        store.returnedRows = listOf(row("B", "shared"))
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.query(OrderBy.NONE, 10) }
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.keywordOverlap(listOf("fact"), 10) }
        store.vectorRows = store.returnedRows!!.map { SimilarityResult.create(it, 0.9) }
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.findSimilarWithScores(request) }
    }

    @Test
    fun `excluded key fails across all list terminals`() {
        val store = VectorStore()
        val read = session(store).excluding(listOf(PropositionKey("A", "shared")))
        store.returnedRows = listOf(row("A", "shared"))
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.query(OrderBy.NONE, 10) }
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.keywordOverlap(listOf("fact"), 10) }
        store.vectorRows = store.returnedRows!!.map { SimilarityResult.create(it, 0.9) }
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.findSimilarWithScores(request) }
    }

    @Test
    fun `duplicate compound keys blank IDs and oversized native batches fail atomically`() {
        val store = VectorStore()
        for (rows in listOf(listOf(row("A", "same"), row("A", "same")), listOf(row("A", " ")))) {
            store.returnedRows = rows
            failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { session(store).query(OrderBy.NONE, 10) }
            store.vectorRows = rows.map { SimilarityResult.create(it, 0.9) }
            failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { session(store).findSimilarWithScores(request) }
        }
        store.returnedRows = listOf(row("A", "one"), row("B", "two"))
        failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { session(store).query(OrderBy.NONE, 1) }
    }

    @Test
    fun `backend failure messages are sanitized and fixed typed vector failures survive unchanged`() {
        val store = VectorStore()
        for (error in listOf(IllegalStateException("private owner C"), UnsupportedOperationException("private query"))) {
            store.failure = error
            failure(ScopedReadFailure.SCOPED_READ_FAILED) { session(store).query(OrderBy.NONE, 10) }
            failure(ScopedReadFailure.SCOPED_READ_FAILED) { session(store).count() }
            failure(ScopedReadFailure.SCOPED_READ_FAILED) { session(store).keywordOverlap(listOf("private token"), 10) }
            failure(ScopedReadFailure.SCOPED_READ_FAILED) { session(store).findSimilarWithScores(request) }
        }
        val legacy = FilteredVectorUnsupportedException()
        store.failure = legacy
        val wrapped = failure(ScopedReadFailure.SCOPED_READ_FAILED) { session(store).findSimilarWithScores(request) }
        assertSame(legacy, wrapped.cause)
        for (error in listOf(
            ScopedReadException(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED),
            ScopedReadException(ScopedReadFailure.EXACT_VECTOR_DEADLINE_EXCEEDED),
        )) {
            store.failure = error
            val thrown = assertThrows(error.javaClass) { session(store).findSimilarWithScores(request) }
            assertSame(error, thrown)
        }
    }

    @Test
    fun `a store failure is reported as a read failure that keeps the store exception as its cause`() {
        val store = VectorStore()
        val error = IllegalStateException("store is down")
        store.failure = error
        val terminals = listOf<() -> Unit>(
            { session(store).query(OrderBy.NONE, 10) },
            { session(store).count() },
            { session(store).keywordOverlap(listOf("fact"), 10) },
            { session(store).findSimilarWithScores(request) },
        )
        for (terminal in terminals) {
            assertSame(error, failure(ScopedReadFailure.SCOPED_READ_FAILED, terminal).cause)
        }
    }

    @Test
    fun `an illegal argument thrown by the store is a store failure, not the caller's invalid read`() {
        val store = VectorStore()
        val error = IllegalArgumentException("store rejected its own statement")
        store.failure = error
        val terminals = listOf<() -> Unit>(
            { session(store).query(OrderBy.NONE, 10) },
            { session(store).count() },
            { session(store).keywordOverlap(listOf("fact"), 10) },
            { session(store).findSimilarWithScores(request) },
        )
        for (terminal in terminals) {
            val ex = failure(ScopedReadFailure.SCOPED_READ_FAILED, terminal)
            assertEquals(ScopedReadFailure.SCOPED_READ_FAILED, ex.failure)
            assertSame(error, ex.cause)
        }
    }

    @Test
    fun `a restriction outside the scope is the caller's invalid read and keeps the validation error`() {
        val store = VectorStore()
        val reader = ScopedPropositionReader.open(store, scope)
        for (restrict in listOf<() -> Unit>(
            { reader.restrict(PropositionQuery.againstContext("C")) },
            { reader.snapshot(instant, 3.0).restrict(PropositionQuery.againstContext("C")) },
        )) {
            val ex = failure(ScopedReadFailure.INVALID_SCOPED_READ, restrict)
            assertEquals(ScopedReadFailure.INVALID_SCOPED_READ, ex.failure)
            assertInstanceOf(IllegalArgumentException::class.java, ex.cause)
        }
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `failure keeps its cause and its message is the failure name`() {
        val cause = IllegalStateException("x")
        val ex = ScopedReadException(ScopedReadFailure.SCOPED_READ_FAILED, cause)
        assertEquals(ScopedReadFailure.SCOPED_READ_FAILED, ex.failure)
        assertEquals("SCOPED_READ_FAILED", ex.message)
        assertSame(cause, ex.cause)
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
            failure(ScopedReadFailure.SCOPED_READ_RESULT_INVALID) { read.findSimilarWithScores(request) }
        }
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
