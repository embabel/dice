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
 * E2b withoutSimilaritySearch gives mode NONE from either filtering mode.
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

    /** Lists full-text search and implements it. */
    private class TextStore : BaseOnlyStore(), CapabilityReporting, FullTextSearch {
        override val capabilities = StoreCapabilities.of(StoreCapability.FULL_TEXT_SEARCH)
        override fun findMatching(request: FullTextSearchRequest): List<Proposition> = emptyList()
    }

    /** Implements full-text search and publishes no descriptor. */
    private class UnlistedTextStore : BaseOnlyStore(), FullTextSearch {
        override fun findMatching(request: FullTextSearchRequest): List<Proposition> = emptyList()
    }

    /** Lists full-text search and does not implement it. */
    private class OverclaimingStore : BaseOnlyStore(), CapabilityReporting {
        override val capabilities = StoreCapabilities.of(StoreCapability.FULL_TEXT_SEARCH)
    }

    @Test
    fun `E1 the filtering mode follows from the capabilities`() {
        assertEquals(
            VectorFiltering.BEFORE_TOP_K,
            StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH, StoreCapability.ATOMIC_WRITES).vectorFiltering
        )
        assertEquals(VectorFiltering.NONE, StoreCapabilities.of(StoreCapability.ATOMIC_WRITES).vectorFiltering)
        assertTrue(StoreCapabilities.NONE.supported.isEmpty())
        assertEquals(VectorFiltering.UNDECLARED, StoreCapabilities.UNDECLARED.vectorFiltering)
        assertTrue(StoreCapabilities.UNDECLARED.supported.isEmpty())
    }

    @Test
    fun `E2 with and without return new descriptors that keep the mode in step`() {
        val a = StoreCapabilities.of(StoreCapability.ATOMIC_WRITES)
        val b = a.with(StoreCapability.FILTERED_VECTOR_SEARCH)
        assertEquals(VectorFiltering.BEFORE_TOP_K, b.vectorFiltering)
        assertFalse(a.supports(StoreCapability.FILTERED_VECTOR_SEARCH))
        assertEquals(StoreCapabilities.of(StoreCapability.ATOMIC_WRITES), b.without(StoreCapability.FILTERED_VECTOR_SEARCH))
    }

    @Test
    fun `E2b withoutSimilaritySearch gives mode NONE from either filtering mode`() {
        val writesOnly = StoreCapabilities.of(StoreCapability.ATOMIC_WRITES)
        val filtersAfter = writesOnly.withVectorFiltering(VectorFiltering.AFTER_TOP_K)
        val filtersFirst = writesOnly.with(StoreCapability.FILTERED_VECTOR_SEARCH)
        assertEquals(writesOnly, filtersAfter.withoutSimilaritySearch())
        assertEquals(writesOnly, filtersFirst.withoutSimilaritySearch())
    }

    @Test
    fun `E3 a filtering mode that disagrees with the capabilities is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            StoreCapabilities.of(StoreCapability.ATOMIC_WRITES).withVectorFiltering(VectorFiltering.BEFORE_TOP_K)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH).withVectorFiltering(VectorFiltering.AFTER_TOP_K)
        }
        assertEquals(
            VectorFiltering.AFTER_TOP_K,
            StoreCapabilities.of(StoreCapability.ATOMIC_WRITES).withVectorFiltering(VectorFiltering.AFTER_TOP_K).vectorFiltering
        )
    }

    @Test
    fun `E4 the block and the chain give equal descriptors`() {
        val block = StoreCapabilities {
            add(StoreCapability.FULL_TEXT_SEARCH, StoreCapability.ATOMIC_WRITES)
            vectorFiltering = VectorFiltering.AFTER_TOP_K
        }
        val chain = StoreCapabilities.of(StoreCapability.FULL_TEXT_SEARCH, StoreCapability.ATOMIC_WRITES)
            .withVectorFiltering(VectorFiltering.AFTER_TOP_K)
        assertEquals(chain, block)
        assertEquals(chain.hashCode(), block.hashCode())
        assertEquals(
            StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH),
            StoreCapabilities { add(StoreCapability.FILTERED_VECTOR_SEARCH) }
        )
    }

    @Test
    fun `E5 the supported set is read-only`() {
        val supported = StoreCapabilities.of(StoreCapability.ATOMIC_WRITES).supported
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (supported as MutableSet<StoreCapability>).add(StoreCapability.FULL_TEXT_SEARCH)
        }
    }

    @Test
    fun `E6 describe returns a reporting store's descriptor and UNDECLARED for any other store`() {
        assertEquals(StoreCapabilities.of(StoreCapability.FULL_TEXT_SEARCH), StoreCapabilities.describe(TextStore()))
        assertSame(StoreCapabilities.UNDECLARED, StoreCapabilities.describe(BaseOnlyStore()))
    }

    @Test
    fun `E7 find returns the store only when it lists the capability and implements the interface`() {
        val store = TextStore()
        assertSame(store, StoreCapabilities.find(store, FullTextSearch::class.java))
        assertSame(store, StoreCapabilities.find<FullTextSearch>(store))
        assertNull(StoreCapabilities.find(store, AtomicWrites::class.java))
        assertNull(StoreCapabilities.find(UnlistedTextStore(), FullTextSearch::class.java))
        assertNull(StoreCapabilities.find(OverclaimingStore(), FullTextSearch::class.java))
        assertThrows(IllegalArgumentException::class.java) {
            StoreCapabilities.find(store, Runnable::class.java)
        }
    }

    @Test
    fun `E8 each capability names its interface`() {
        assertEquals(FilteredVectorSearch::class.java, StoreCapability.FILTERED_VECTOR_SEARCH.contract)
        assertEquals(FullTextSearch::class.java, StoreCapability.FULL_TEXT_SEARCH.contract)
        assertEquals(AbstractionTraversal::class.java, StoreCapability.ABSTRACTION_TRAVERSAL.contract)
        assertEquals(AtomicWrites::class.java, StoreCapability.ATOMIC_WRITES.contract)
        assertEquals(4, StoreCapability.values().size)
    }

    @Test
    fun `E9 the refusal carries the capability, the reason and the cause`() {
        val cause = IllegalStateException("index missing")
        val refusal = UnsupportedCapabilityException(StoreCapability.FULL_TEXT_SEARCH, "no text index", cause)
        assertEquals(StoreCapability.FULL_TEXT_SEARCH, refusal.capability)
        assertTrue(refusal is UnsupportedOperationException)
        assertTrue(refusal.message!!.contains("FULL_TEXT_SEARCH"))
        assertTrue(refusal.message!!.contains("no text index"))
        assertSame(cause, refusal.cause)
        assertNull(UnsupportedCapabilityException(StoreCapability.FULL_TEXT_SEARCH, "no text index").cause)
    }
}
