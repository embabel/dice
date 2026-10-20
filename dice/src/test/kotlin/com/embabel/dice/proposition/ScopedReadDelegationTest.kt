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
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.proposition.store.Neo4jRagPropositionRepository
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant

class ScopedReadDelegationTest {
    private val scope = ContextReadScope.ofValues(listOf("A", "B", "A"))
    private val asOf = Instant.parse("2026-10-15T00:00:00Z")
    private val budget = ExactVectorBudget(7, Duration.ofMillis(250))
    private val request = TextSimilaritySearchRequest("alpha", topK = 2, similarityThreshold = 0.1)
    private val clock = ReadClock(asOf, 0.5)
    private val spec = ScopedReadSpec.of(scope)
        .restrict(PropositionQuery(minConfidence = 0.5, statuses = setOf(PropositionStatus.ACTIVE)))
        .restrict(PropositionQuery(pinned = true, minImportance = 0.4))
        .includingOnly(listOf(PropositionKey("A", "a"), PropositionKey("B", "b")))
        .excluding(listOf(PropositionKey("B", "b")))

    @Test
    fun `all wrapper shapes preserve the exact scoped call arguments and results`() {
        forEachRecordingWrapper { delegate, wrapper ->
            val capable = wrapper as ContextSetVectorSearchCapable
            assertTrue(capable.supportsScopedReads)
            assertTrue(capable.supportsScopedVectors)
            assertSame(delegate.rows, capable.query(spec, clock, PropositionQuery.OrderBy.CREATED_DESC, 2))
            assertEquals(9L, capable.count(spec, clock))
            val tokens = listOf("alpha", "beta", "alpha")
            assertSame(delegate.rows, capable.keywordOverlap(spec, clock, tokens, 3))
            assertSame(delegate.scored, capable.findSimilarWithScores(request, spec, clock, budget))
            assertEquals(listOf("query", "count", "keyword", "vector"), delegate.calls.map { it.operation })
            delegate.calls.forEach {
                assertEquals(scope, it.scope)
                assertSame(spec, it.spec)
                assertSame(clock, it.clock)
            }
            assertEquals(PropositionQuery.OrderBy.CREATED_DESC, delegate.calls[0].order)
            assertEquals(2, delegate.calls[0].limit)
            assertSame(tokens, delegate.calls[2].tokens)
            assertEquals(3, delegate.calls[2].limit)
            assertSame(request, delegate.calls[3].request)
            assertSame(budget, delegate.calls[3].budget)
        }
    }

    @Test
    fun `empty included keys remain deny all through every wrapper`() {
        val denied = spec.includingOnly(emptySet())
        forEachRecordingWrapper { delegate, wrapper ->
            val capable = wrapper as ContextSetVectorSearchCapable
            capable.query(denied, clock, PropositionQuery.OrderBy.NONE, 1)
            capable.count(denied, clock)
            capable.keywordOverlap(denied, clock, listOf("alpha"), 1)
            capable.findSimilarWithScores(request, denied, clock, budget)
            assertEquals(4, delegate.calls.size)
            delegate.calls.forEach {
                assertSame(denied, it.spec)
                assertEquals(emptySet<PropositionKey>(), it.spec.includedKeys)
            }
        }
    }

    @Test
    fun `runtime support flags reject every operation before touching delegates`() {
        forEachRecordingWrapper { delegate, wrapper ->
            val capable = wrapper as ContextSetVectorSearchCapable
            delegate.reads = false
            delegate.vectors = false
            assertFalse(capable.supportsScopedReads)
            assertFalse(capable.supportsScopedVectors)
            assertUnsupported(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { capable.query(spec, clock, PropositionQuery.OrderBy.NONE, 1) }
            assertUnsupported(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { capable.count(spec, clock) }
            assertUnsupported(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { capable.keywordOverlap(spec, clock, listOf("alpha"), 1) }
            assertUnsupported(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED) { capable.findSimilarWithScores(request, spec, clock, budget) }
            assertTrue(delegate.calls.isEmpty())
            delegate.reads = true
            assertTrue(capable.supportsScopedReads)
            assertFalse(capable.supportsScopedVectors)
            assertUnsupported(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED) {
                ScopedPropositionReader.open(wrapper, scope).snapshot(asOf, 0.5).findSimilarWithScores(request)
            }
            assertTrue(delegate.calls.isEmpty())
            delegate.reads = false
            delegate.vectors = true
            assertFalse(capable.supportsScopedReads)
            assertTrue(capable.supportsScopedVectors)
        }
    }

    @Test
    fun `plain third party stores never acquire scoped support or call legacy entity operations`() {
        val plain = untouched<PropositionRepository>()
        val wrappers = listOf(
            EventEmittingPropositionRepository(plain),
            EventEmittingPropositionRepository.wrapping(plain),
            Neo4jRagPropositionRepository(plain, untouched()),
        )
        wrappers.forEach { wrapper ->
            val capable = wrapper as ContextSetVectorSearchCapable
            assertFalse(capable.supportsScopedReads)
            assertFalse(capable.supportsScopedVectors)
            assertUnsupported(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { capable.query(spec, clock, PropositionQuery.OrderBy.NONE, 1) }
            assertUnsupported(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { capable.count(spec, clock) }
            assertUnsupported(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { capable.keywordOverlap(spec, clock, listOf("alpha"), 1) }
            assertUnsupported(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED) { capable.findSimilarWithScores(request, spec, clock, budget) }
            assertUnsupported(ScopedReadFailure.SCOPED_READ_UNSUPPORTED) { ScopedPropositionReader.open(wrapper, scope) }
        }
    }

    @Test
    fun `read only capability remains usable without proposition vectors`() {
        wrapperFactories().forEach { wrap ->
            val recording = Recording()
            val readOnly = object : PropositionRepository by untouched(), ContextSetReadCapable by recording {}
            val wrapper = wrap(readOnly) as ContextSetVectorSearchCapable
            assertTrue(wrapper.supportsScopedReads)
            assertFalse(wrapper.supportsScopedVectors)
            assertSame(recording.rows, wrapper.query(spec, clock, PropositionQuery.OrderBy.NONE, 1))
            assertEquals(9L, wrapper.count(spec, clock))
            assertSame(recording.rows, wrapper.keywordOverlap(spec, clock, listOf("alpha"), 1))
            assertUnsupported(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED) { wrapper.findSimilarWithScores(request, spec, clock, budget) }
            assertEquals(listOf("query", "count", "keyword"), recording.calls.map { it.operation })
            val noEmbedder = wrap(InMemoryPropositionRepository()) as ContextSetVectorSearchCapable
            assertTrue(noEmbedder.supportsScopedReads)
            assertFalse(noEmbedder.supportsScopedVectors)
            assertUnsupported(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED) { noEmbedder.findSimilarWithScores(request, spec, clock, budget) }
        }
    }

    private fun wrapperFactories(): List<(PropositionRepository) -> PropositionStore> = listOf(
        { EventEmittingPropositionRepository(it) },
        { EventEmittingPropositionRepository.wrapping(it) },
        { Neo4jRagPropositionRepository(it, untouched()) },
    )

    private fun forEachRecordingWrapper(check: (Recording, PropositionStore) -> Unit) {
        val plain = Recording()
        check(plain, EventEmittingPropositionRepository(plain))
        val factory = Recording()
        check(factory, EventEmittingPropositionRepository.wrapping(factory))
        val provenance = ProvenanceRecording()
        val provenanceWrapper = EventEmittingPropositionRepository.wrapping(provenance)
        assertInstanceOf(ProvenanceSubtractingEventEmittingPropositionRepository::class.java, provenanceWrapper)
        check(provenance, provenanceWrapper)
        val revision = RevisionRecording()
        val revisionWrapper = EventEmittingPropositionRepository.wrapping(revision)
        assertInstanceOf(SourceRevisionEventEmittingPropositionRepository::class.java, revisionWrapper)
        check(revision, revisionWrapper)
        val rag = Recording()
        check(rag, Neo4jRagPropositionRepository(rag, untouched()))
    }

    private fun row(id: String, context: String) = Proposition(
        id = id, contextId = ContextId(context), text = "alpha", mentions = emptyList(),
        confidence = 0.8, pinned = true, created = asOf, contentRevised = asOf,
    )

    private fun assertUnsupported(failure: ScopedReadFailure, call: () -> Unit) {
        assertEquals(failure, assertThrows(ScopedReadException::class.java, call).failure)
    }

    private open inner class Recording : PropositionRepository by untouched(), ContextSetVectorSearchCapable {
        var reads = true
        var vectors = true
        var failure: RuntimeException? = null
        override val supportsScopedReads get() = reads
        override val supportsScopedVectors get() = vectors
        val calls = mutableListOf<Call>()
        val rows = listOf(row("a", "A"))
        val scored = listOf(SimilarityResult(rows.single(), 0.9))
        private fun record(call: Call) { calls += call; failure?.let { throw it } }
        override fun query(spec: ScopedReadSpec, clock: ReadClock, order: PropositionQuery.OrderBy, limit: Int): List<Proposition> {
            record(Call("query", spec.scope, spec, clock, order = order, limit = limit))
            return rows
        }
        override fun count(spec: ScopedReadSpec, clock: ReadClock): Long {
            record(Call("count", spec.scope, spec, clock))
            return 9L
        }
        override fun keywordOverlap(spec: ScopedReadSpec, clock: ReadClock, tokens: List<String>, limit: Int): List<Proposition> {
            record(Call("keyword", spec.scope, spec, clock, tokens = tokens, limit = limit))
            return rows
        }
        override fun findSimilarWithScores(request: TextSimilaritySearchRequest, spec: ScopedReadSpec, clock: ReadClock, budget: ExactVectorBudget): List<SimilarityResult<Proposition>> {
            record(Call("vector", spec.scope, spec, clock, request = request, budget = budget))
            return scored
        }
    }

    private open inner class ProvenanceRecording : Recording(), ProvenanceSubtractionCapable {
        override fun subtractFoldedEvidence(propositionId: String, provenanceRefs: List<String>, grounding: Collection<String>, sourceIds: Collection<String>): Proposition? = error("Unexpected write")
    }

    private inner class RevisionRecording : ProvenanceRecording(), SourceRevisionQueryCapable {
        override fun findBySourceKey(contextIdValue: String, sourceKey: String): List<Proposition> = error("Unexpected legacy read")
        override fun findBySourceRevision(contextIdValue: String, ref: SourceRevisionRef): List<Proposition> = error("Unexpected legacy read")
        override fun findRevisionlessBySourceLocator(contextIdValue: String, locator: SourceLocator): List<Proposition> = error("Unexpected legacy read")
    }

    private data class Call(
        val operation: String,
        val scope: ContextReadScope,
        val spec: ScopedReadSpec,
        val clock: ReadClock,
        val order: PropositionQuery.OrderBy? = null,
        val limit: Int? = null,
        val tokens: List<String>? = null,
        val request: TextSimilaritySearchRequest? = null,
        val budget: ExactVectorBudget? = null,
    )

    companion object {
        private inline fun <reified T> untouched(): T = T::class.java.cast(
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                error("Unexpected legacy/entity/write call: ${method.name}")
            },
        )
    }
}
