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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.EnumSet

/**
 * Tests for [ReadQuery], [ReadKeywordQuery], [ReadVectorSearchRequest] and [ReadOrder].
 *
 * Contract:
 * E1 A query starts with no order and no limit, and each step returns a new query.
 * E2 A limit below 1 cannot be built, on a query or on a keyword request.
 * E3 The query block and the chain give the same query.
 * E4 A search request has a default size, no floor and empty criteria, and its block and chain agree.
 * E5 A search for blank text or for fewer than one match cannot be built.
 * E6 The request types carry no context and cover the fields of the requests they stand in for.
 * E7 A keyword request lower-cases its tokens, drops duplicates and rejects an empty list and a blank token.
 * E8 The keyword block and the chain give the same request.
 * E9 The orders are the orders a PropositionQuery has.
 * E10 requireOnly on a request names a criterion or an order the store does not apply, and no order always passes.
 * E11 toString on a search or a keyword request shows the size of the text or tokens, never the words themselves.
 */
class ReadRequestsTest {

    private val aboutAlice = ReadCriteria.empty().withEntityId("alice")

    @Test
    fun `E1 a query starts with no order and no limit and each step returns a new query`() {
        val query = ReadQuery.of(aboutAlice)
        assertSame(aboutAlice, query.criteria)
        assertEquals(ReadOrder.NONE, query.order)
        assertNull(query.limit)
        val recent = query.orderedByCreated().withLimit(20)
        assertEquals(ReadOrder.CREATED_DESC, recent.order)
        assertTrue(recent.limit == 20)
        assertNull(query.limit)
        assertEquals(recent, ReadQuery.of(aboutAlice).withLimit(20).withOrder(ReadOrder.CREATED_DESC))
    }

    @Test
    fun `E2 a limit below 1 cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) { ReadQuery.of(aboutAlice).withLimit(0) }
        assertThrows(IllegalArgumentException::class.java) { ReadQuery.of(aboutAlice).withLimit(-1) }
        assertThrows(IllegalArgumentException::class.java) { ReadQuery(aboutAlice) { limit = 0 } }
        assertThrows(IllegalArgumentException::class.java) { ReadKeywordQuery.forTokens("peanut").withLimit(0) }
        assertThrows(IllegalArgumentException::class.java) { ReadKeywordQuery(listOf("peanut")) { limit = -1 } }
        assertTrue(ReadQuery.of(aboutAlice).withLimit(1).limit == 1)
        assertTrue(ReadQuery.of(aboutAlice).withLimit(Int.MAX_VALUE).limit == Int.MAX_VALUE)
    }

    @Test
    fun `E3 the query block and the chain give the same query`() {
        val block = ReadQuery(aboutAlice) {
            order = ReadOrder.IMPORTANCE_DESC
            limit = 5
        }
        assertEquals(ReadQuery.of(aboutAlice).orderedByImportance().withLimit(5), block)
        assertEquals(ReadQuery.of(aboutAlice), ReadQuery(aboutAlice))
    }

    @Test
    fun `E4 a search request has a default size, no floor and empty criteria`() {
        val request = ReadVectorSearchRequest.forText("peanut allergy")
        assertEquals(VectorSearchRequest.DEFAULT_TOP_K, request.topK)
        assertNull(request.minScore)
        assertEquals(ReadCriteria.empty(), request.criteria)
        val floor = SimilarityScore.ofCosine(0.6)
        val chain = request.withCriteria(aboutAlice).withTopK(5).withMinScore(floor)
        val block = ReadVectorSearchRequest("peanut allergy") {
            criteria = aboutAlice
            topK = 5
            minScore = floor
        }
        assertEquals(chain, block)
        assertNull(chain.withMinScore(null).minScore)
    }

    @Test
    fun `E5 a search for blank text or for fewer than one match cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) { ReadVectorSearchRequest.forText(" ") }
        assertThrows(IllegalArgumentException::class.java) { ReadVectorSearchRequest.forText("x").withTopK(0) }
        assertThrows(IllegalArgumentException::class.java) { ReadVectorSearchRequest("x") { topK = -1 } }
    }

    @Test
    fun `E6 the request types carry no context and cover the fields they stand in for`() {
        // ReadQuery carries the order and the limit that ReadCriteria leaves out of a
        // PropositionQuery. The search request has the fields of VectorSearchRequest, with
        // criteria in place of its PropositionQuery filter.
        val queryFields = fieldNames(ReadQuery::class.java)
        val keywordFields = fieldNames(ReadKeywordQuery::class.java)
        val searchFields = fieldNames(ReadVectorSearchRequest::class.java)
        assertEquals(setOf("criteria", "order", "limit"), queryFields)
        assertEquals(queryFields + "tokens", keywordFields)
        assertEquals(fieldNames(VectorSearchRequest::class.java) - "filter", searchFields - "criteria")
        assertTrue((queryFields + keywordFields + searchFields).none { it.contains("context", ignoreCase = true) })
    }

    @Test
    fun `E7 a keyword request lower-cases its tokens and drops duplicates`() {
        val request = ReadKeywordQuery.forTokens("Peanut", "allergy", "PEANUT")
        assertEquals(listOf("peanut", "allergy"), request.tokens)
        assertEquals(ReadCriteria.empty(), request.criteria)
        assertEquals(ReadOrder.NONE, request.order)
        assertNull(request.limit)
        assertEquals(request, ReadKeywordQuery.forTokens(listOf("peanut", "ALLERGY")))
        assertEquals(listOf("peanut "), ReadKeywordQuery.forTokens("Peanut ").tokens)
        assertThrows(IllegalArgumentException::class.java) { ReadKeywordQuery.forTokens(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ReadKeywordQuery.forTokens("peanut", " ") }
        assertThrows(IllegalArgumentException::class.java) { ReadKeywordQuery(listOf("")) }
        assertThrows(UnsupportedOperationException::class.java) {
            (request.tokens as MutableList<String>).add("egg")
        }
    }

    @Test
    fun `E8 the keyword block and the chain give the same request`() {
        val chain = ReadKeywordQuery.forTokens("peanut", "allergy")
            .withCriteria(aboutAlice)
            .withOrder(ReadOrder.CREATED_DESC)
            .withLimit(10)
        val block = ReadKeywordQuery(listOf("peanut", "allergy")) {
            criteria = aboutAlice
            order = ReadOrder.CREATED_DESC
            limit = 10
        }
        assertEquals(chain, block)
        assertEquals(chain.hashCode(), block.hashCode())
        assertEquals(listOf("peanut", "allergy"), chain.tokens)
    }

    @Test
    fun `E9 a new query order turns this test red until a read order accounts for it`() {
        assertEquals(PropositionQuery.OrderBy.entries.map { it.name }, ReadOrder.entries.map { it.name })
    }

    @Test
    fun `E10 requireOnly on a request names what the store does not apply`() {
        val criteria = EnumSet.of(ReadCriterion.ENTITY_ID)
        val orders = EnumSet.of(ReadOrder.CREATED_DESC)
        val recent = ReadQuery.of(aboutAlice).orderedByCreated()
        recent.requireOnly(criteria, orders, StoreCapability.SCOPED_READS)
        ReadQuery.of(aboutAlice).requireOnly(criteria, emptySet(), StoreCapability.SCOPED_READS)

        val order = assertThrows(UnsupportedCapabilityException::class.java) {
            recent.orderedByImportance().requireOnly(criteria, orders, StoreCapability.SCOPED_READS)
        }
        assertEquals(StoreCapability.SCOPED_READS, order.capability)
        assertTrue(order.message.orEmpty().contains("IMPORTANCE_DESC"))

        val pinned = ReadCriteria.empty().withPinned(true)
        val keyword = assertThrows(UnsupportedCapabilityException::class.java) {
            ReadKeywordQuery.forTokens("peanut").withCriteria(pinned)
                .requireOnly(criteria, orders, StoreCapability.SCOPED_READS)
        }
        assertTrue(keyword.message.orEmpty().contains("PINNED"))

        val search = assertThrows(UnsupportedCapabilityException::class.java) {
            ReadVectorSearchRequest.forText("peanut allergy").withCriteria(pinned)
                .requireOnly(criteria, StoreCapability.SCOPED_VECTOR_SEARCH)
        }
        assertEquals(StoreCapability.SCOPED_VECTOR_SEARCH, search.capability)
    }

    @Test
    fun `E11 toString on a search or a keyword request hides the words the user typed`() {
        val secret = "peanut-allergy-secret"
        val search = ReadVectorSearchRequest.forText(secret).withCriteria(aboutAlice).toString()
        assertFalse(search.contains(secret), search)
        assertTrue(search.contains("text=<21 chars>"), search)
        assertTrue(search.contains("topK=${VectorSearchRequest.DEFAULT_TOP_K}"), search)

        val keyword = ReadKeywordQuery.forTokens(secret, "egg").withCriteria(aboutAlice).toString()
        assertFalse(keyword.contains(secret), keyword)
        assertFalse(keyword.contains("egg"), keyword)
        assertTrue(keyword.contains("tokens=<2 tokens>"), keyword)
        assertTrue(keyword.contains("criteria="), keyword)
    }

    /**
     * The names of the instance fields a class declares.
     *
     * @param type The class to read.
     * @return The field names, without static and compiler-made fields.
     */
    private fun fieldNames(type: Class<*>): Set<String> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()
}
