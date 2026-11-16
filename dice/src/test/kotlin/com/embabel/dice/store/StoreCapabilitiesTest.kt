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
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.EventEmittingPropositionRepository
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.VectorSearchCapable
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * Contract for StoreCapabilities, the descriptor a store publishes.
 *
 * E1 The filtering mode follows from the capabilities.
 * E2 with and without return new descriptors that keep the mode in step.
 * E3 A filtering mode that disagrees with the capabilities is rejected.
 * E4 The block and the chain give equal descriptors.
 * E5 The supported set is read-only.
 * E6 describe returns a reporting store's declaration.
 * E7 find returns a listed capability and fails on a false listing.
 * E8 Each capability names its interface.
 * E9 The refusal carries the capability, the reason and the cause.
 * E10 describe reads supportsVector only for a store with no declaration.
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

    /** Has similarity search and reports [vector] through supportsVector. */
    private open class SearchableStore(private val vector: Boolean) : BaseOnlyStore(), VectorSearchCapable {
        override val supportsVector: Boolean get() = vector
        override fun query(query: PropositionQuery): List<Proposition> = super<BaseOnlyStore>.query(query)
        override fun findSimilarWithScores(
            textSimilaritySearchRequest: TextSimilaritySearchRequest,
        ): List<SimilarityResult<Proposition>> = emptyList()
    }

    /** Has similarity search and declares [capabilities]. */
    private class DeclaringStore(
        vector: Boolean,
        override val capabilities: StoreCapabilities,
    ) : SearchableStore(vector), CapabilityReporting

    /** Reports no similarity search through supportsVector and declares filtered vector search. */
    private class FilterFirstSearchableStore : SearchableStore(false), CapabilityReporting, FilteredVectorSearch {
        override val capabilities = StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH)
        override fun findNearest(request: VectorSearchRequest): List<VectorMatch> = emptyList()
    }

    @Test
    fun `E1 the filtering mode follows from the capabilities`() {
        assertEquals(
            VectorFiltering.BEFORE_TOP_K,
            StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH).vectorFiltering
        )
        assertEquals(VectorFiltering.UNDECLARED, StoreCapabilities.of().vectorFiltering)
        assertEquals(StoreCapabilities.UNDECLARED, StoreCapabilities.of())
        assertThrows(IllegalArgumentException::class.java) {
            @Suppress("UNCHECKED_CAST")
            StoreCapabilities.of(*(arrayOfNulls<StoreCapability>(1) as Array<StoreCapability>))
        }
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
        val f = StoreCapability.FILTERED_VECTOR_SEARCH
        assertEquals(VectorFiltering.UNDECLARED, b.without(f).vectorFiltering)
        assertEquals(StoreCapabilities.of(), StoreCapabilities.of(f).without(f))
        assertEquals(
            VectorFiltering.AFTER_TOP_K,
            StoreCapabilities.NONE.withVectorFiltering(VectorFiltering.AFTER_TOP_K).without(f).vectorFiltering
        )
        assertEquals(StoreCapabilities.of(), StoreCapabilities.of().without(f))
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
    fun `E6 describe returns a reporting store's declaration`() {
        assertEquals(
            StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH),
            StoreCapabilities.describe(VectorStore())
        )
        assertSame(StoreCapabilities.NONE, StoreCapabilities.describe(BaseOnlyStore()))
    }

    @Test
    fun `E7 find returns a listed capability and fails on a false listing`() {
        val store = VectorStore()
        assertSame(store, StoreCapabilities.find(store, FilteredVectorSearch::class.java))
        assertSame(store, StoreCapabilities.find<FilteredVectorSearch>(store))
        assertNull(StoreCapabilities.find(UnlistedVectorStore(), FilteredVectorSearch::class.java))
        assertThrows(IllegalStateException::class.java) {
            StoreCapabilities.find(OverclaimingStore(), FilteredVectorSearch::class.java)
        }
        val failure = assertThrows(IllegalStateException::class.java) {
            StoreCapabilities.describe(OverclaimingStore())
        }
        val message = failure.message!!
        assertTrue(message.contains("OverclaimingStore"))
        assertTrue(message.contains("FILTERED_VECTOR_SEARCH"))
        assertTrue(message.contains("FilteredVectorSearch"))
        val unstubbed = Mockito.mock(
            PropositionStore::class.java,
            Mockito.withSettings().extraInterfaces(CapabilityReporting::class.java)
        )
        assertThrows(IllegalStateException::class.java) { StoreCapabilities.describe(unstubbed) }
        assertThrows(IllegalStateException::class.java) {
            StoreCapabilities.find(unstubbed, FilteredVectorSearch::class.java)
        }
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

    @Test
    fun `E10 describe reads supportsVector only for a store with no declaration`() {
        val afterCut = StoreCapabilities.of().withVectorFiltering(VectorFiltering.AFTER_TOP_K)
        assertSame(StoreCapabilities.NONE, StoreCapabilities.describe(SearchableStore(false)))
        assertSame(StoreCapabilities.UNDECLARED, StoreCapabilities.describe(SearchableStore(true)))
        assertSame(afterCut, StoreCapabilities.describe(DeclaringStore(false, afterCut)))
        assertSame(afterCut, StoreCapabilities.describe(DeclaringStore(true, afterCut)))
        assertSame(StoreCapabilities.NONE, StoreCapabilities.describe(DeclaringStore(true, StoreCapabilities.NONE)))
        assertSame(
            afterCut,
            StoreCapabilities.describe(object : BaseOnlyStore(), CapabilityReporting {
                override val capabilities = afterCut
            })
        )
        val store = FilterFirstSearchableStore()
        assertEquals(VectorFiltering.BEFORE_TOP_K, StoreCapabilities.describe(store).vectorFiltering)
        assertSame(store, StoreCapabilities.find<FilteredVectorSearch>(store))
        assertSame(
            StoreCapabilities.NONE,
            StoreCapabilities.describe(EventEmittingPropositionRepository(InMemoryPropositionRepository()))
        )
    }
}
