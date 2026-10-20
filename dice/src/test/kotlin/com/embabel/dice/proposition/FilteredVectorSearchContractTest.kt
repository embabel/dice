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
import org.junit.jupiter.api.Assertions.assertThrows
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
            assertEquals("FILTERED_VECTOR_UNSUPPORTED", failure.message)
        }
        assertEquals(0, spy.vectorCalls, "unsupported filtered reads must not execute global top-K")
        assertEquals(0, spy.queryCalls, "unsupported filtered reads must not enumerate candidates")
    }

    @Test
    fun `budget accepts the largest values`() {
        val largest = ExactVectorBudget(Int.MAX_VALUE, Duration.ofSeconds(Long.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, largest.maxCandidates)
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE), largest.timeout)
        assertThrows(IllegalArgumentException::class.java) { ExactVectorBudget(0, Duration.ofSeconds(1)) }
        assertThrows(IllegalArgumentException::class.java) { ExactVectorBudget(1, Duration.ZERO) }
    }
}
