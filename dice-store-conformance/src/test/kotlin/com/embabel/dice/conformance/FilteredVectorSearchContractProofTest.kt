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
package com.embabel.dice.conformance

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.store.CapabilityReporting
import com.embabel.dice.store.StoreCapabilities
import com.embabel.dice.store.StoreCapability
import com.embabel.dice.store.VectorFiltering
import com.embabel.dice.store.VectorSearchRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opentest4j.TestAbortedException

/**
 * Builds the filtered vector search suite over one store, so a test can run a single case.
 *
 * @param store The store every case runs against.
 * @param expected The vector filtering mode the suite expects, or null to leave it unchecked.
 * @return The suite, ready to run.
 */
private fun suiteOver(store: PropositionStore, expected: VectorFiltering? = null) =
    object : AbstractFilteredVectorSearchContractTest() {
        override fun store(): PropositionStore = store
        override fun expectedVectorFiltering(): VectorFiltering? = expected
    }

/**
 * A store that lists filtered vector search and does not implement it.
 */
private class ListedOnlyStore :
    PropositionStore by InMemoryPropositionRepository(),
    CapabilityReporting {
    override val capabilities: StoreCapabilities = StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH)
}

/**
 * A store that declares no capabilities and no vector filtering.
 */
private class DeclaresNoneStore :
    PropositionStore by InMemoryPropositionRepository(),
    CapabilityReporting {
    override val capabilities: StoreCapabilities = StoreCapabilities.NONE
}

/**
 * A store that declares it filters after the cut to K and has no similarity search at all.
 */
private class AfterTopKWithNoSearchStore :
    PropositionStore by InMemoryPropositionRepository(),
    CapabilityReporting {
    override val capabilities: StoreCapabilities =
        StoreCapabilities.NONE.withVectorFiltering(VectorFiltering.AFTER_TOP_K)
}

/**
 * Runs single cases of the filtered vector search suite against stores that break a rule or
 * declare nothing. Each wrong store fails the case that states its rule. A store that declares
 * nothing is skipped with the capability named.
 */
class FilteredVectorSearchContractProofTest {

    private val queryText = ConformanceEmbeddings.text("query", 0.0)

    /**
     * Builds a proposition whose text embeds at an angle from the query.
     *
     * @param label The name of the text.
     * @param degrees The angle from the query in degrees.
     * @param context The context the proposition belongs to.
     * @return The proposition.
     */
    private fun proposition(label: String, degrees: Double, context: String): Proposition =
        Proposition(
            contextId = ContextId(context),
            text = ConformanceEmbeddings.text(label, degrees),
            mentions = emptyList(),
            confidence = 0.9,
            status = PropositionStatus.ACTIVE,
        )

    /**
     * Saves 121 propositions at 1.0 to 13.0 degrees in `near` and five at 30 to 70 degrees in `far`.
     *
     * @param store The store to seed.
     */
    private fun seedNearerContext(store: PropositionStore) {
        (0 until 121).forEach { i -> store.save(proposition("near-$i", (10 + i) / 10.0, "near")) }
        listOf(30.0, 40.0, 50.0, 60.0, 70.0).forEachIndexed { i, degrees ->
            store.save(proposition("far-$i", degrees, "far"))
        }
    }

    /**
     * Runs a filtered search for the `far` context.
     *
     * @param store The store to search.
     * @param topK How many matches to ask for.
     * @return The matches the store returns.
     */
    private fun nearestInFar(store: FilterFirstReferenceStore, topK: Int) =
        store.findNearest(VectorSearchRequest.forText(queryText, PropositionQuery.againstContext("far")).withTopK(topK))

    @Test
    fun `a store that filters after the cut fails the K matches case`() {
        assertThrows<AssertionError> {
            suiteOver(FilterFirstReferenceStore(ReferenceFault.FILTERS_AFTER_CUT)).kMatchesComeBackWhenANearerContextIsExcluded()
        }
    }

    @Test
    fun `a store that filters after the cut fails the status filter case`() {
        assertThrows<AssertionError> {
            suiteOver(FilterFirstReferenceStore(ReferenceFault.FILTERS_AFTER_CUT)).aStatusFilterIsAppliedBeforeTheCutOrRefused()
        }
    }

    @Test
    fun `a store that over-fetches thirty times K fails the K matches case at K of one`() {
        assertThrows<AssertionError> {
            suiteOver(FilterFirstReferenceStore(ReferenceFault.FILTERS_AFTER_OVER_FETCH))
                .kMatchesComeBackWhenANearerContextIsExcluded()
        }
    }

    @Test
    fun `a store that ignores pinned fails the filter field case`() {
        val failure = assertThrows<AssertionError> {
            suiteOver(FilterFirstReferenceStore(ReferenceFault.IGNORES_PINNED)).eachFilterFieldIsAppliedOrRefused()
        }
        assertTrue(failure.message.orEmpty().contains("pinned")) { "The failure names pinned: ${failure.message}" }
    }

    @Test
    fun `a store with an exclusive floor fails the floor case`() {
        assertThrows<AssertionError> {
            suiteOver(FilterFirstReferenceStore(ReferenceFault.EXCLUSIVE_FLOOR)).aMatchThatScoresExactlyTheFloorIsReturned()
        }
    }

    @Test
    fun `a store on another score scale fails the scale case`() {
        assertThrows<AssertionError> {
            suiteOver(FilterFirstReferenceStore(ReferenceFault.HALVED_SCALE)).scoresAreCosineSimilarity()
        }
    }

    @Test
    fun `a store that returns zero vectors fails the zero embedding case`() {
        assertThrows<AssertionError> {
            suiteOver(FilterFirstReferenceStore(ReferenceFault.KEEPS_ZERO_VECTORS))
                .aPropositionWithAnAllZeroEmbeddingIsNotReturned()
        }
    }

    @Test
    fun `a store that lists the capability and does not implement it fails`() {
        assertThrows<AssertionError> {
            suiteOver(ListedOnlyStore()).aStoreThatListsFilteredVectorSearchImplementsIt()
        }
    }

    @Test
    fun `a store that declares nothing is skipped with the capability named`() {
        val filtered = assertThrows<TestAbortedException> {
            suiteOver(InMemoryPropositionRepository()).kMatchesComeBackWhenANearerContextIsExcluded()
        }
        val afterCut = assertThrows<TestAbortedException> {
            suiteOver(InMemoryPropositionRepository()).aStoreThatFiltersAfterTheCutKeepsThatModesPromise()
        }
        assertTrue(filtered.message.orEmpty().contains("FILTERED_VECTOR_SEARCH")) {
            "The skip names FILTERED_VECTOR_SEARCH: ${filtered.message}"
        }
        assertTrue(afterCut.message.orEmpty().contains("AFTER_TOP_K")) { "The skip names AFTER_TOP_K: ${afterCut.message}" }
    }

    @Test
    fun `a store that declares NONE is skipped with the capability named`() {
        val skipped = assertThrows<TestAbortedException> {
            suiteOver(DeclaresNoneStore()).kMatchesComeBackWhenANearerContextIsExcluded()
        }
        assertTrue(skipped.message.orEmpty().contains("FILTERED_VECTOR_SEARCH")) {
            "The skip names FILTERED_VECTOR_SEARCH: ${skipped.message}"
        }
    }

    @Test
    fun `an after-cut store that ignores the filter fails the after-cut case`() {
        assertThrows<AssertionError> {
            suiteOver(AfterTopKReferenceStore(ignoresFilter = true)).aStoreThatFiltersAfterTheCutKeepsThatModesPromise()
        }
    }

    @Test
    fun `an after-cut declaration with no search fails the after-cut case`() {
        assertThrows<AssertionError> {
            suiteOver(AfterTopKWithNoSearchStore()).aStoreThatFiltersAfterTheCutKeepsThatModesPromise()
        }
    }

    @Test
    fun `a store that declares a mode its test does not expect fails`() {
        assertThrows<AssertionError> {
            suiteOver(InMemoryPropositionRepository(), VectorFiltering.BEFORE_TOP_K).theStoreDeclaresTheExpectedVectorFiltering()
        }
    }

    @Test
    fun `the store that filters after the cut returns fewer than K for the context fixture`() {
        val afterCut = FilterFirstReferenceStore(ReferenceFault.FILTERS_AFTER_CUT)
        seedNearerContext(afterCut)
        val overFetch = FilterFirstReferenceStore(ReferenceFault.FILTERS_AFTER_OVER_FETCH)
        seedNearerContext(overFetch)

        val afterCutMatches = nearestInFar(afterCut, 5)
        assertTrue(afterCutMatches.size < 5) { "The store returns ${afterCutMatches.size} matches with K of 5" }
        assertEquals(5, nearestInFar(overFetch, 5).size) { "A window of 150 holds the 121 nearer and the 5 matches" }
        assertEquals(0, nearestInFar(overFetch, 1).size) { "A window of 30 holds only nearer propositions" }
    }

    @Test
    fun `the after-cut reference store returns fewer than K when nearer propositions are filtered out`() {
        val store = AfterTopKReferenceStore()
        listOf(30.0, 40.0, 50.0, 60.0, 70.0).forEachIndexed { i, degrees ->
            store.save(proposition("match-$i", degrees, "tenant"))
        }
        (1..5).forEach { i -> store.save(proposition("other-$i", i.toDouble(), "neighbour")) }

        val results = store.findSimilarWithScores(
            TextSimilaritySearchRequest(query = queryText, similarityThreshold = 0.0, topK = 3),
            PropositionQuery.againstContext("tenant"),
        )

        assertTrue(results.size < 3) { "The store returns ${results.size} results with K of 3" }
    }
}
