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
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * Tests for finding the scoped capabilities by type from Kotlin, the way the user guide shows it.
 *
 * Contract:
 * E1 find by type returns a store that lists the scoped capabilities, and the request block reads through it.
 * E2 find by type returns null on a store that lists neither.
 */
class ScopedReadsKotlinTest {

    private val active = ScopedReadSpec.of(ContextReadScope.ofValues("alice-session", "team-handbook"))
        .narrow(PropositionQuery(statuses = setOf(PropositionStatus.ACTIVE), minConfidence = 0.6))

    @Test
    fun `E1 find by type returns a store that lists the scoped capabilities`() {
        val store = Mockito.mock(
            PropositionStore::class.java,
            Mockito.withSettings().extraInterfaces(
                CapabilityReporting::class.java, ScopedReads::class.java, ScopedVectorSearch::class.java,
            ),
        )
        Mockito.`when`((store as CapabilityReporting).capabilities)
            .thenReturn(StoreCapabilities.of(StoreCapability.SCOPED_READS, StoreCapability.SCOPED_VECTOR_SEARCH))
        val budget = ReadBudget.ofRows(200).withMaxVectorCandidates(5_000)
        val search = StoreCapabilities.find<ScopedVectorSearch>(store)

        val nearest = search?.findNearest(
            active,
            VectorSearchRequest("What does Alice eat?", PropositionQuery()) { topK = 5 },
            budget,
        )

        assertSame(store, StoreCapabilities.find<ScopedReads>(store))
        assertSame(store, search)
        assertEquals(emptyList<VectorMatch>(), nearest)
    }

    @Test
    fun `E2 find by type returns null on a store that lists neither`() {
        val store = Mockito.mock(PropositionStore::class.java)
        assertNull(StoreCapabilities.find<ScopedReads>(store))
        assertNull(StoreCapabilities.find<ScopedVectorSearch>(store))
    }
}
