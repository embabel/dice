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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Contract for [FullTextSearchRequest].
 *
 * E1 forText sets the default limit and keeps the text and the filter as given.
 * E2 The block form and the chain form build equal requests.
 * E3 Blank text, a limit below one, and a filter with an ordering or a limit are rejected.
 */
class FullTextSearchRequestTest {

    private val filter = PropositionQuery.againstContext("c")

    @Test
    fun `E1 forText gives the default limit and keeps the text as written`() {
        val text = "\"peanut\" AND -allergy*"
        val request = FullTextSearchRequest.forText(text, filter)

        assertEquals(text, request.text)
        assertEquals(10, request.limit)
        assertEquals(FullTextSearchRequest.DEFAULT_LIMIT, request.limit)
        assertEquals(filter, request.filter)
    }

    @Test
    fun `E2 the block and the chain give equal requests`() {
        val fromBlock = FullTextSearchRequest("x", filter) { limit = 20 }
        val fromChain = FullTextSearchRequest.forText("x", filter).withLimit(20)

        assertEquals(fromChain, fromBlock)
        assertEquals(fromChain.hashCode(), fromBlock.hashCode())

        val noBlock = FullTextSearchRequest("x", filter)
        assertEquals(FullTextSearchRequest.forText("x", filter), noBlock)
    }

    @Test
    fun `E3 blank text, a limit below one, and a filter with an ordering or a limit are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FullTextSearchRequest.forText(" ", filter)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullTextSearchRequest("", filter)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullTextSearchRequest.forText("x", filter).withLimit(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullTextSearchRequest("x", filter) { limit = -1 }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullTextSearchRequest.forText("x", filter.orderedByCreated())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullTextSearchRequest("x", filter.withLimit(5))
        }
    }
}
