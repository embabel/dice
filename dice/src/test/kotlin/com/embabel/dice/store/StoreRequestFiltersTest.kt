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
 * Tests for [requireNarrowingFilter], the check every store request runs on its filter.
 *
 * Contract:
 * E1 A filter with no ordering and no limit comes back equal to the input.
 * E2 A filter with an ordering or a limit throws IllegalArgumentException.
 * E3 A list the caller changes after the check does not change the checked filter.
 */
class StoreRequestFiltersTest {

    @Test
    fun `E1 a filter with no ordering or limit comes back equal`() {
        val f = PropositionQuery.againstContext("c")
        assertEquals(f, requireNarrowingFilter(f))
    }

    @Test
    fun `E2 a filter with an ordering or a limit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireNarrowingFilter(PropositionQuery.againstContext("c").orderedByCreated())
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireNarrowingFilter(PropositionQuery.againstContext("c").withLimit(5))
        }
    }

    @Test
    fun `E3 a list the caller changes afterwards does not change the checked filter`() {
        val ids = mutableListOf("a")
        val checked = requireNarrowingFilter(PropositionQuery.againstContext("c").withAnyEntityIds(ids))
        ids.add("b")
        assertEquals(listOf("a"), checked.anyEntityIds)
    }
}
