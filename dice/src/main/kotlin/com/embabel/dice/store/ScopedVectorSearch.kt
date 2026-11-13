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

import org.jetbrains.annotations.ApiStatus

/**
 * Similarity search held to a [ScopedReadSpec] and a [ReadBudget].
 *
 * ```java
 * ScopedVectorSearch search = StoreCapabilities.find(store, ScopedVectorSearch.class);
 * if (search != null) {
 *     List<VectorMatch> nearest = search.findNearest(
 *         spec, VectorSearchRequest.forText("What does Alice eat?", ScopedReadSpec.criteria()).withTopK(5), budget);
 * }
 * ```
 *
 * The candidates of a search are the propositions whose context is in the spec's scope and that
 * match every filter of the spec and the request's filter. The vector candidates are the
 * candidates that have an embedding. An implementation keeps rules 2, 5, 6 and 7 of
 * [ScopedReads], with the request's filter as the call's query and
 * [StoreCapability.SCOPED_VECTOR_SEARCH] as the capability a refusal carries, and these rules:
 *
 * 1. The store selects the candidates first and compares the request's text with every vector
 *    candidate. The search is exact over the candidates, so the matches are the nearest
 *    [VectorSearchRequest.topK] of them.
 * 2. A proposition that is not a candidate is never returned, and no value of it enters a
 *    returned score or the order of the matches.
 * 3. A `topK` above [ReadBudget.maxRows] throws [ReadBudgetExceededException]. So does a search
 *    with more vector candidates than [ReadBudget.maxVectorCandidates], and every search under a
 *    budget whose [ReadBudget.maxVectorCandidates] is zero. [ReadBudget.requireVectorCandidates]
 *    is that check, called with the number of vector candidates.
 * 4. No match scores below [VectorSearchRequest.minScore]. Matches are ordered closest first, and
 *    the cut to K is applied after ordering.
 * 5. A candidate with no embedding, or an all-zero embedding, is not returned and does not fail
 *    the search. A request whose text embeds to all zeros returns no matches. The scope and
 *    budget checks come first, so such a request can still be refused.
 * 6. A store that keeps its data in a database runs the comparison, the score floor, the ordering
 *    and the cut to K there, along with the selection. It does not load rows into the JVM to do
 *    any of them.
 *
 * A store that implements this interface keeps every rule and lists
 * [StoreCapability.SCOPED_VECTOR_SEARCH]. The entry is independent of
 * [StoreCapabilities.vectorFiltering], which describes similarity search that takes no spec. A
 * store lists the entry under any filtering mode, and listing it does not change the mode.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
interface ScopedVectorSearch {

    /**
     * Returns the candidates closest to the request's text.
     *
     * @param spec Which propositions the search may return.
     * @param request The text, the filter, the result size and the score floor.
     * @param budget The most the search may take.
     * @return Matches, closest first. Empty when no candidate matches.
     * @throws ReadScopeViolationException when the request's filter names a context [spec] does
     *   not allow.
     * @throws ReadBudgetExceededException when `topK` is more than the budget's row cap, when the
     *   search has more vector candidates than the budget's candidate cap, and when that cap is
     *   zero.
     * @throws UnsupportedCapabilityException when the store cannot apply a field of a filter.
     */
    fun findNearest(spec: ScopedReadSpec, request: VectorSearchRequest, budget: ReadBudget): List<VectorMatch>
}
