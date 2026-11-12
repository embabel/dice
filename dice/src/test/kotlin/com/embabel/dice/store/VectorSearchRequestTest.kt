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
package com.embabel.dice.store

import com.embabel.dice.proposition.PropositionQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Tests for VectorSearchRequest, the immutable request that a similarity search runs against.
 *
 * Contract:
 * E1  forText gives the text and filter as given, the default size of 10, and no score floor.
 * E2  The Kotlin block, the Java chain and the no-block call build equal requests with equal hash codes.
 * E3  A blank text, a size below 1, and a filter with an ordering or a limit throw IllegalArgumentException.
 */
class VectorSearchRequestTest {

    @Test
    fun `E1 forText gives the default size and no floor`() {
        val filter = PropositionQuery.againstContext("c")
        val request = VectorSearchRequest.forText("cats", filter)
        assertEquals("cats", request.text)
        assertEquals(filter, request.filter)
        assertEquals(10, request.topK)
        assertEquals(VectorSearchRequest.DEFAULT_TOP_K, request.topK)
        assertNull(request.minScore)
    }

    @Test
    fun `E2 the block and the chain give equal requests`() {
        val filter = PropositionQuery.againstContext("c")
        val score = SimilarityScore.ofCosine(0.6)
        val fromBlock = VectorSearchRequest("cats", filter) {
            topK = 5
            minScore = score
        }
        val fromChain = VectorSearchRequest.forText("cats", filter).withTopK(5).withMinScore(score)
        assertEquals(fromChain, fromBlock)
        assertEquals(fromChain.hashCode(), fromBlock.hashCode())
        assertEquals(VectorSearchRequest.forText("cats", filter), VectorSearchRequest("cats", filter))
        assertNull(VectorSearchRequest.forText("cats", filter).withMinScore(score).withMinScore(null).minScore)
    }

    @Test
    fun `E3 blank text, a size below one, and a filter with an ordering or a limit are rejected`() {
        val filter = PropositionQuery.againstContext("c")
        assertThrows(IllegalArgumentException::class.java) { VectorSearchRequest.forText("  ", filter) }
        assertThrows(IllegalArgumentException::class.java) { VectorSearchRequest("", filter) }
        assertThrows(IllegalArgumentException::class.java) { VectorSearchRequest.forText("cats", filter).withTopK(0) }
        assertThrows(IllegalArgumentException::class.java) { VectorSearchRequest("cats", filter) { topK = 0 } }
        assertThrows(IllegalArgumentException::class.java) {
            VectorSearchRequest.forText("cats", filter.orderedByCreated())
        }
        assertThrows(IllegalArgumentException::class.java) { VectorSearchRequest("cats", filter.withLimit(5)) }
        assertEquals(1, VectorSearchRequest.forText("cats", filter).withTopK(1).topK)
    }
}
