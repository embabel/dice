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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [ReadBudget].
 *
 * Contract:
 * E1 A budget with a row cap below 1, a negative candidate cap or a cap above MAX_CAP cannot be built.
 * E2 ofRows allows no similarity search, and each with step returns a new budget.
 * E3 requireRows passes at the cap and throws one above it, with the resource, the cap and the request.
 * E4 requireVectorCandidates passes at the cap and throws one above it.
 * E5 A negative count is rejected as an argument error, under any cap.
 * E6 A budget with no candidate cap refuses similarity search by name, whatever count it is passed.
 * E7 A budget refusal is a read refusal and is not an argument error.
 * E8 A count too large for an int is refused with the count the store passed.
 */
class ReadBudgetTest {

    @Test
    fun `E1 a budget with a cap out of range cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) { ReadBudget.ofRows(0) }
        assertThrows(IllegalArgumentException::class.java) { ReadBudget.ofRows(10).withMaxRows(-1) }
        assertThrows(IllegalArgumentException::class.java) { ReadBudget.ofRows(10).withMaxVectorCandidates(-1) }
        assertThrows(IllegalArgumentException::class.java) { ReadBudget.ofRows(Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { ReadBudget.ofRows(10).withMaxVectorCandidates(Int.MAX_VALUE) }
        assertEquals(Int.MAX_VALUE - 1, ReadBudget.ofRows(ReadBudget.MAX_CAP).maxRows)
    }

    @Test
    fun `E2 ofRows allows no similarity search and each with step returns a new budget`() {
        val rowsOnly = ReadBudget.ofRows(10)
        val both = rowsOnly.withMaxVectorCandidates(500)
        assertEquals(0, rowsOnly.maxVectorCandidates)
        assertFalse(rowsOnly.isVectorSearchAllowed)
        assertEquals(500, both.maxVectorCandidates)
        assertTrue(both.isVectorSearchAllowed)
        assertFalse(both.withMaxVectorCandidates(0).isVectorSearchAllowed)
        assertEquals(10, both.maxRows)
        assertEquals(both, ReadBudget.ofRows(3).withMaxVectorCandidates(500).withMaxRows(10))
    }

    @Test
    fun `E3 requireRows passes at the cap and throws one above it`() {
        val budget = ReadBudget.ofRows(10)
        budget.requireRows(0)
        budget.requireRows(10)
        budget.requireRows(10L)
        val refusal = assertThrows(ReadBudgetExceededException::class.java) { budget.requireRows(11) }
        assertEquals(ReadBudgetResource.ROWS, refusal.resource)
        assertEquals(10, refusal.allowed)
        assertEquals(11L, refusal.requested)
        assertEquals("The read needs 11 rows and its budget allows 10", refusal.message)
    }

    @Test
    fun `E4 requireVectorCandidates passes at the cap and throws one above it`() {
        val budget = ReadBudget.ofRows(10).withMaxVectorCandidates(500)
        budget.requireVectorSearch()
        budget.requireVectorCandidates(0)
        budget.requireVectorCandidates(500)
        val refusal = assertThrows(ReadBudgetExceededException::class.java) { budget.requireVectorCandidates(501) }
        assertEquals(ReadBudgetResource.VECTOR_CANDIDATES, refusal.resource)
        assertEquals(500, refusal.allowed)
        assertEquals(501L, refusal.requested)
    }

    @Test
    fun `E5 a negative count is rejected as an argument error`() {
        val budget = ReadBudget.ofRows(10).withMaxVectorCandidates(500)
        assertThrows(IllegalArgumentException::class.java) { budget.requireRows(-1) }
        assertThrows(IllegalArgumentException::class.java) { budget.requireRows(-1L) }
        assertThrows(IllegalArgumentException::class.java) { budget.requireVectorCandidates(-1) }
        assertThrows(IllegalArgumentException::class.java) { ReadBudget.ofRows(10).requireVectorCandidates(-1L) }
    }

    @Test
    fun `E6 a budget with no candidate cap refuses similarity search by name`() {
        val rowsOnly = ReadBudget.ofRows(10)
        val refusal = assertThrows(ReadBudgetExceededException::class.java) { rowsOnly.requireVectorSearch() }
        assertEquals(ReadBudgetResource.VECTOR_SEARCH, refusal.resource)
        assertEquals(0, refusal.allowed)
        assertEquals(0L, refusal.requested)
        assertEquals(
            "Similarity search is not enabled on this budget. " +
                "Set a vector candidate cap with withMaxVectorCandidates to enable it.",
            refusal.message,
        )
        for (count in listOf(0, 1, 500)) {
            val counted = assertThrows(ReadBudgetExceededException::class.java) { rowsOnly.requireVectorCandidates(count) }
            assertEquals(ReadBudgetResource.VECTOR_SEARCH, counted.resource)
        }
    }

    @Test
    fun `E7 a budget refusal is a read refusal and is not an argument error`() {
        val refusal = assertThrows(ReadBudgetExceededException::class.java) { ReadBudget.ofRows(10).requireRows(11) }
        assertTrue(ReadRefusedException::class.java.isInstance(refusal))
        assertFalse(IllegalArgumentException::class.java.isInstance(refusal))
    }

    @Test
    fun `E8 a count too large for an int is refused with the count the store passed`() {
        val budget = ReadBudget.ofRows(ReadBudget.MAX_CAP).withMaxVectorCandidates(ReadBudget.MAX_CAP)
        // 2^32 + 5 narrows to 5 as an int, and 2^31 narrows to a negative number.
        val wraps = 4_294_967_301L
        val rows = assertThrows(ReadBudgetExceededException::class.java) { budget.requireRows(wraps) }
        assertEquals(ReadBudgetResource.ROWS, rows.resource)
        assertEquals(wraps, rows.requested)
        assertThrows(ReadBudgetExceededException::class.java) { budget.requireRows(2_147_483_648L) }
        budget.requireRows(ReadBudget.MAX_CAP.toLong())
        val candidates = assertThrows(ReadBudgetExceededException::class.java) { budget.requireVectorCandidates(wraps) }
        assertEquals(ReadBudgetResource.VECTOR_CANDIDATES, candidates.resource)
        assertEquals(wraps, candidates.requested)
    }
}
