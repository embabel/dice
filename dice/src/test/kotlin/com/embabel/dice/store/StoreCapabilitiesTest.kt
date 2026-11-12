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

import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract for StoreCapabilities, the descriptor a store publishes.
 *
 * E1 The filtering mode follows from the capabilities.
 * E2 with and without return new descriptors that keep the mode in step.
 * E3 A filtering mode that disagrees with the capabilities is rejected.
 * E4 The block and the chain give equal descriptors.
 * E5 The supported set is read-only.
 * E6 describe returns a reporting store's descriptor, and UNDECLARED for any other store.
 * E7 find returns the store only when it lists the capability and implements the interface.
 * E8 Each capability names its interface.
 * E9 The refusal carries the capability, the reason and the cause.
 */
class StoreCapabilitiesTest {

    private open class BaseOnlyStore : PropositionStore {
        private val store = mutableMapOf<String, Proposition>()

        override fun save(proposition: Proposition): Proposition {
            store[proposition.id] = proposition
            return proposition
        }

        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store.values.toList()
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }

    /** Lists filtered similarity search and implements it. */
    private class VectorStore : BaseOnlyStore(), CapabilityReporting, FilteredVectorSearch {
        override val capabilities = StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH)
        override fun findNearest(request: VectorSearchRequest): List<VectorMatch> = emptyList()
    }

    /** Implements filtered similarity search and publishes no descriptor. */
    private class UnlistedVectorStore : BaseOnlyStore(), FilteredVectorSearch {
        override fun findNearest(request: VectorSearchRequest): List<VectorMatch> = emptyList()
    }

    /** Lists filtered similarity search and does not implement it. */
    private class OverclaimingStore : BaseOnlyStore(), CapabilityReporting {
        override val capabilities = StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH)
    }

    @Test
    fun `E1 the filtering mode follows from the capabilities`() {
        assertEquals(
            VectorFiltering.BEFORE_TOP_K,
            StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH).vectorFiltering
        )
        assertEquals(VectorFiltering.NONE, StoreCapabilities.of().vectorFiltering)
        assertTrue(StoreCapabilities.NONE.supported.isEmpty())
        assertEquals(VectorFiltering.UNDECLARED, StoreCapabilities.UNDECLARED.vectorFiltering)
        assertTrue(StoreCapabilities.UNDECLARED.supported.isEmpty())
    }

    @Test
    fun `E2 with and without return new descriptors that keep the mode in step`() {
        val a = StoreCapabilities.NONE
        val b = a.with(StoreCapability.FILTERED_VECTOR_SEARCH)
        assertEquals(VectorFiltering.BEFORE_TOP_K, b.vectorFiltering)
        assertFalse(a.supports(StoreCapability.FILTERED_VECTOR_SEARCH))
        assertEquals(StoreCapabilities.NONE, b.without(StoreCapability.FILTERED_VECTOR_SEARCH))
    }

    @Test
    fun `E3 a filtering mode that disagrees with the capabilities is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            StoreCapabilities.NONE.withVectorFiltering(VectorFiltering.BEFORE_TOP_K)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH).withVectorFiltering(VectorFiltering.AFTER_TOP_K)
        }
        assertEquals(
            VectorFiltering.AFTER_TOP_K,
            StoreCapabilities.NONE.withVectorFiltering(VectorFiltering.AFTER_TOP_K).vectorFiltering
        )
    }

    @Test
    fun `E4 the block and the chain give equal descriptors`() {
        val block = StoreCapabilities { vectorFiltering = VectorFiltering.AFTER_TOP_K }
        val chain = StoreCapabilities.NONE.withVectorFiltering(VectorFiltering.AFTER_TOP_K)
        assertEquals(chain, block)
        assertEquals(chain.hashCode(), block.hashCode())
        assertEquals(
            StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH),
            StoreCapabilities { add(StoreCapability.FILTERED_VECTOR_SEARCH) }
        )
    }

    @Test
    fun `E5 the supported set is read-only`() {
        val supported = StoreCapabilities.NONE.supported
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (supported as MutableSet<StoreCapability>).add(StoreCapability.FILTERED_VECTOR_SEARCH)
        }
    }

    @Test
    fun `E6 describe returns a reporting store's descriptor and UNDECLARED for any other store`() {
        assertEquals(
            StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH),
            StoreCapabilities.describe(VectorStore())
        )
        assertSame(StoreCapabilities.UNDECLARED, StoreCapabilities.describe(BaseOnlyStore()))
    }

    @Test
    fun `E7 find returns the store only when it lists the capability and implements the interface`() {
        val store = VectorStore()
        assertSame(store, StoreCapabilities.find(store, FilteredVectorSearch::class.java))
        assertSame(store, StoreCapabilities.find<FilteredVectorSearch>(store))
        assertNull(StoreCapabilities.find(UnlistedVectorStore(), FilteredVectorSearch::class.java))
        assertNull(StoreCapabilities.find(OverclaimingStore(), FilteredVectorSearch::class.java))
        assertThrows(IllegalArgumentException::class.java) {
            StoreCapabilities.find(store, Runnable::class.java)
        }
    }

    @Test
    fun `E8 each capability names its interface`() {
        assertEquals(FilteredVectorSearch::class.java, StoreCapability.FILTERED_VECTOR_SEARCH.contract)
        assertEquals(1, StoreCapability.values().size)
    }

    @Test
    fun `E9 the refusal carries the capability, the reason and the cause`() {
        val cause = IllegalStateException("index missing")
        val refusal = UnsupportedCapabilityException(StoreCapability.FILTERED_VECTOR_SEARCH, "no vector index", cause)
        assertEquals(StoreCapability.FILTERED_VECTOR_SEARCH, refusal.capability)
        assertTrue(refusal is UnsupportedOperationException)
        assertTrue(refusal.message!!.contains("FILTERED_VECTOR_SEARCH"))
        assertTrue(refusal.message!!.contains("no vector index"))
        assertSame(cause, refusal.cause)
        assertNull(UnsupportedCapabilityException(StoreCapability.FILTERED_VECTOR_SEARCH, "no vector index").cause)
    }
}
