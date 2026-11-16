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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * Tests for scoped reads from Kotlin, the way the user guide shows them.
 *
 * Contract:
 * E1 find by type returns a store that lists the scoped capabilities, and the criteria and request blocks read through it.
 * E2 find by type returns null on a store that lists neither.
 */
class ScopedReadsKotlinTest {

    private val aliceSession = ContextId("alice-session")
    private val teamHandbook = ContextId("team-handbook")
    private val scope = ContextReadScope.of(listOf(aliceSession, teamHandbook))
    private val active = ScopedReadSpec.of(scope).narrow(
        ReadCriteria {
            statuses = setOf(PropositionStatus.ACTIVE)
            minConfidence = 0.6
        },
    )

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
        val reads = StoreCapabilities.find<ScopedReads>(store)
        val search = StoreCapabilities.find<ScopedVectorSearch>(store)

        val recent = reads?.query(active, ReadQuery(ReadCriteria { entityId = "alice" }) { limit = 20 }, budget)
        val total = reads?.count(active, ReadCriteria.empty(), budget)
        val mentions = reads?.matchKeywords(active, ReadKeywordQuery(listOf("peanut", "allergy")) { limit = 10 }, budget)
        val nearest = search?.findNearest(active, ReadVectorSearchRequest("What does Alice eat?") { topK = 5 }, budget)

        assertSame(store, reads)
        assertSame(store, search)
        assertEquals(scope, active.scope)
        assertEquals(emptyList<Proposition>(), recent)
        assertTrue(total == 0)
        assertEquals(emptyList<Proposition>(), mentions)
        assertEquals(emptyList<VectorMatch>(), nearest)
    }

    @Test
    fun `E2 find by type returns null on a store that lists neither`() {
        val store = Mockito.mock(PropositionStore::class.java)
        assertNull(StoreCapabilities.find<ScopedReads>(store))
        assertNull(StoreCapabilities.find<ScopedVectorSearch>(store))
    }
}
