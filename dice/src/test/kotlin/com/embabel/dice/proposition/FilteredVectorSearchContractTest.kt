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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class FilteredVectorSearchContractTest {

    private val request = TextSimilaritySearchRequest(query = "query", topK = 2, similarityThreshold = 0.5)

    /** A legacy backend that has never supplied a filtered-vector override. */
    private class LegacySpy : VectorSearchCapable {
        var vectorCalls = 0
        var queryCalls = 0
        private val fixtures = (0..4).map { hit("C-$it", "C", 0.99) } +
            listOf(hit("A-hit", "A", 0.8), hit("B-hit", "B", 0.7))

        override fun findSimilarWithScores(
            textSimilaritySearchRequest: TextSimilaritySearchRequest,
        ): List<SimilarityResult<Proposition>> {
            vectorCalls++
            return fixtures.filter { it.score >= textSimilaritySearchRequest.similarityThreshold }
                .sortedByDescending { it.score }
                .take(textSimilaritySearchRequest.topK)
        }

        override fun query(query: PropositionQuery): List<Proposition> {
            queryCalls++
            return fixtures.map { it.match }.filter { query.matchesFilters(it) }
        }

        companion object {
            private fun hit(id: String, context: String, score: Double) = SimilarityResult.create(
                Proposition(id = id, contextId = ContextId(context), text = "fact $id", mentions = emptyList(), confidence = 0.9),
                score,
            )
        }
    }

    @Test
    fun `legacy filtered default fails before either backend call despite eligible hits below global top-K`() {
        val spy = LegacySpy()
        for (context in listOf("A", "B")) {
            val failure = assertThrows(FilteredVectorUnsupportedException::class.java) {
                spy.findSimilarWithScores(request, PropositionQuery.againstContext(context))
            }
            assertEquals("FILTERED_VECTOR_UNSUPPORTED", failure.code)
            assertEquals(failure.code, failure.message)
        }
        assertEquals(0, spy.vectorCalls, "unsupported filtered reads must not execute global top-K")
        assertEquals(0, spy.queryCalls, "unsupported filtered reads must not enumerate candidates")
    }

    @Test
    fun `unfiltered legacy behavior still returns global top-K and exposes the starvation fixture`() {
        val spy = LegacySpy()
        val hits = spy.findSimilarWithScores(request)

        assertEquals(listOf("C-0", "C-1"), hits.map { it.match.id })
        assertEquals(1, spy.vectorCalls)
        assertEquals(0, spy.queryCalls)
        for (context in listOf("A", "B")) {
            val eligible = spy.query(PropositionQuery.againstContext(context))
            assertEquals(listOf("$context-hit"), eligible.map { it.id })
            assertTrue(hits.none { hit -> eligible.any { it.id == hit.match.id } })
        }
        assertEquals(listOf("C-0", "C-1"), spy.findSimilar(request).map { it.id })
    }

    @Test
    fun `filtered method retains the two-argument JVM descriptor and default implementation`() {
        val method = VectorSearchCapable::class.java.getMethod(
            "findSimilarWithScores",
            TextSimilaritySearchRequest::class.java,
            PropositionQuery::class.java,
        )
        assertEquals(List::class.java, method.returnType)
        assertTrue(method.isDefault, "legacy implementations inherit the method without adding an override")
    }

    @Test
    fun `vector failures expose only fixed codes and no backend cause`() {
        val failures = listOf(
            FilteredVectorUnsupportedException() to "FILTERED_VECTOR_UNSUPPORTED",
            ExactVectorBudgetExceededException() to "EXACT_VECTOR_BUDGET_EXCEEDED",
            ExactVectorDeadlineExceededException() to "EXACT_VECTOR_DEADLINE_EXCEEDED",
        )
        for ((failure, code) in failures) {
            assertEquals(code, (failure as VectorReadFailure).code)
            assertEquals(code, failure.message)
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
        }
        assertTrue(failures[0].first is UnsupportedOperationException)
        assertTrue(failures[1].first is IllegalStateException)
        assertTrue(failures[2].first is IllegalStateException)
    }

    @Test
    fun `exact budget default is finite and available as a Java static field`() {
        val budget = ExactVectorBudget::class.java.getField("DEFAULT").get(null) as ExactVectorBudget
        assertEquals(10_000, budget.maxCandidates)
        assertEquals(Duration.ofSeconds(5), budget.timeout)
    }

    @Test
    fun `exact budget rejects nonpositive candidates and overflow of cap plus one`() {
        for (cap in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                ExactVectorBudget(cap, Duration.ofSeconds(1))
            }
        }
        val largest = ExactVectorBudget(Int.MAX_VALUE - 1, Duration.ofNanos(1))
        assertEquals(Int.MAX_VALUE, Math.addExact(largest.maxCandidates, 1))
        assertEquals(1, ExactVectorBudget(1, Duration.ofNanos(1)).maxCandidates)
    }

    @Test
    fun `exact budget rejects zero negative and nonrepresentable timeout intervals`() {
        for (timeout in listOf(
            Duration.ZERO,
            Duration.ofNanos(-1),
            Duration.ofSeconds(-1),
            Duration.ofNanos(Long.MAX_VALUE).plusNanos(1),
            Duration.ofSeconds(Long.MAX_VALUE),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ExactVectorBudget(1, timeout)
            }
        }
        val largest = ExactVectorBudget(1, Duration.ofNanos(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, largest.timeout.toNanos())
        assertFalse(largest.timeout.isNegative)
    }
}
