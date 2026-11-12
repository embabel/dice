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

import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.store.CapabilityReporting
import com.embabel.dice.store.StoreCapabilities
import com.embabel.dice.store.VectorFiltering

/**
 * An in-memory proposition store whose filtered similarity search picks the nearest K and then
 * filters them, for running the filtered vector search suite against a store that declares
 * [VectorFiltering.AFTER_TOP_K]. It embeds texts with [ConformanceEmbeddingService].
 *
 * With [ignoresFilter] set it returns the nearest K with no filter, so a test can show the suite
 * case for [VectorFiltering.AFTER_TOP_K] fail.
 *
 * @param ignoresFilter True to skip the filter after the cut.
 * @param delegate The store that holds the propositions, embeds them and runs the unfiltered search.
 */
internal class AfterTopKReferenceStore(
    private val ignoresFilter: Boolean = false,
    private val delegate: InMemoryPropositionRepository = InMemoryPropositionRepository(ConformanceEmbeddingService()),
) : PropositionRepository by delegate, CapabilityReporting {

    override val capabilities: StoreCapabilities = StoreCapabilities.NONE.withVectorFiltering(VectorFiltering.AFTER_TOP_K)

    /**
     * Picks the nearest K of every proposition and keeps the ones that match the query.
     *
     * @param textSimilaritySearchRequest The text, score floor and K of the search.
     * @param query The filter applied after the cut.
     * @return The matches among the nearest K, closest first, or all of the nearest K when
     *   [ignoresFilter] is set.
     */
    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> {
        val nearest = delegate.findSimilarWithScores(textSimilaritySearchRequest)
        return if (ignoresFilter) {
            nearest
        } else {
            val matchingIds = delegate.query(query).map { it.id }.toSet()
            nearest.filter { it.match.id in matchingIds }
        }
    }
}
