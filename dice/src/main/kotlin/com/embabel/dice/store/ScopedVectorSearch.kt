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
 * if (search != null && budget.isVectorSearchAllowed()) {
 *     List<VectorMatch> nearest = search.findNearest(
 *         spec, ReadVectorSearchRequest.forText("What does Alice eat?").withTopK(5), budget);
 * }
 * ```
 *
 * The candidates of a search are the propositions whose context is one that
 * [ScopedReadSpec.scope] names and that meet every filter of the spec and the request's
 * criteria. The vector candidates are the candidates that have an embedding. An implementation
 * keeps these rules:
 *
 * 1. Selection first. The store selects the candidates and compares the request's text with
 *    every vector candidate. The search is exact over the candidates, so the matches are the
 *    nearest [ReadVectorSearchRequest.topK] of them.
 * 2. Contexts. The store reads no context outside [ScopedReadSpec.scope]. It compares context
 *    ids exactly, whatever the collation of its database. It never runs a request's criteria
 *    through a search that takes no spec.
 * 3. Non-candidates. A proposition that is not a candidate is never returned, and no value of it
 *    enters a returned score or the order of the matches.
 * 4. Check order. The store makes its checks in this order and stops at the first that fails.
 *    First the spec, with [ScopedReadSpec.requireOnly]. Then the request, with
 *    [ReadVectorSearchRequest.requireOnly]. Then the budget, with
 *    [ReadBudget.requireVectorSearch] and after it [ReadBudget.requireRows] on `topK`. Only then
 *    does the store embed the text and read. It passes the number of vector candidates to
 *    [ReadBudget.requireVectorCandidates] as its database returned it, before it compares
 *    embeddings.
 * 5. Score floor and order. No match scores below [ReadVectorSearchRequest.minScore]. A match
 *    that scores equal to it is returned, and a null floor returns every score. Matches are
 *    ordered closest first, and the cut to K is applied after ordering. Matches with equal
 *    scores come back in the same order each time the same search runs on unchanged data.
 * 6. Embeddings. A candidate with no embedding, or an all-zero embedding, is not returned and
 *    does not fail the search. A request whose text embeds to all zeros returns no matches. The
 *    checks of rule 4 come first, so such a request can still be refused.
 * 7. Criteria. The store applies every criterion set on a filter or on the request's criteria,
 *    as [ReadCriterion] defines them. It passes the criteria it applies to the `requireOnly`
 *    checks of rule 4. For any other criterion those checks throw
 *    [UnsupportedCapabilityException] carrying [StoreCapability.SCOPED_VECTOR_SEARCH]. The store
 *    never ignores a filter or a criterion. Every store lists
 *    [ReadCriterion.EFFECTIVE_CONFIDENCE_AS_OF] and [ReadCriterion.DECAY_K], which change nothing
 *    in a store that applies no effective-confidence criterion.
 * 8. One instant. Effective confidence is
 *    [com.embabel.dice.proposition.Proposition.effectiveConfidenceAt]. One search computes it at
 *    one instant: the `effectiveConfidenceAsOf` of the request's criteria, or, when that is
 *    null, one reading of the store's clock taken at the start of the call. The store binds that
 *    instant in every statement of the search. Each filter uses its own `decayK`.
 * 9. Push-down. A store that keeps its data in a database runs the selection, the comparison,
 *    the score floor, the ordering and the cut to K there. It counts the vector candidates
 *    there, in full or stopping one past the cap. It does not load rows into the JVM to do any
 *    of them.
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
     * @param request The text, the criteria, the result size and the score floor.
     * @param budget The most the search may take.
     * @return Matches, closest first. Empty when no candidate matches.
     * @throws ReadBudgetExceededException when the budget allows no similarity search, when
     *   `topK` is more than the budget's row cap, and when the search has more vector candidates
     *   than the budget's candidate cap.
     * @throws UnsupportedCapabilityException when the store does not apply a criterion.
     */
    fun findNearest(spec: ScopedReadSpec, request: ReadVectorSearchRequest, budget: ReadBudget): List<VectorMatch>
}
