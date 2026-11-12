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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Tests for [ReadBudget].
 *
 * Contract:
 * E1 A budget with a row cap below 1, a negative candidate cap or a cap above MAX_CAP cannot be built.
 * E2 ofRows allows no similarity search, and each with step returns a new budget.
 * E3 requireRows passes at the cap and throws one above it, with the resource, the cap and the request.
 * E4 requireVectorCandidates passes at the cap, throws one above it, and throws for any count under a zero cap.
 * E5 A negative count is rejected as an argument error.
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
        assertEquals(500, both.maxVectorCandidates)
        assertEquals(10, both.maxRows)
        assertEquals(both, ReadBudget.ofRows(3).withMaxVectorCandidates(500).withMaxRows(10))
    }

    @Test
    fun `E3 requireRows passes at the cap and throws one above it`() {
        val budget = ReadBudget.ofRows(10)
        budget.requireRows(0)
        budget.requireRows(10)
        val refusal = assertThrows(ReadBudgetExceededException::class.java) { budget.requireRows(11) }
        assertEquals(ReadBudgetResource.ROWS, refusal.resource)
        assertEquals(10, refusal.allowed)
        assertEquals(11, refusal.requested)
        assertEquals("The read needs 11 rows and its budget allows 10", refusal.message)
    }

    @Test
    fun `E4 requireVectorCandidates throws above the cap and for any count under a zero cap`() {
        val budget = ReadBudget.ofRows(10).withMaxVectorCandidates(500)
        budget.requireVectorCandidates(0)
        budget.requireVectorCandidates(500)
        val refusal = assertThrows(ReadBudgetExceededException::class.java) { budget.requireVectorCandidates(501) }
        assertEquals(ReadBudgetResource.VECTOR_CANDIDATES, refusal.resource)
        assertEquals(500, refusal.allowed)
        val none = assertThrows(ReadBudgetExceededException::class.java) { ReadBudget.ofRows(10).requireVectorCandidates(0) }
        assertEquals(ReadBudgetResource.VECTOR_CANDIDATES, none.resource)
        assertEquals(0, none.allowed)
        assertThrows(ReadBudgetExceededException::class.java) { ReadBudget.ofRows(10).requireVectorCandidates(1) }
    }

    @Test
    fun `E5 a negative count is rejected as an argument error`() {
        val budget = ReadBudget.ofRows(10).withMaxVectorCandidates(500)
        assertThrows(IllegalArgumentException::class.java) { budget.requireRows(-1) }
        assertThrows(IllegalArgumentException::class.java) { budget.requireVectorCandidates(-1) }
    }
}
