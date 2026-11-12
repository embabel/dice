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

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import org.jetbrains.annotations.ApiStatus
import java.util.Objects

/**
 * Similarity search that applies its filter first. It returns the K propositions closest to a
 * piece of text among the propositions that match a filter.
 *
 * ```java
 * VectorSearchRequest request = VectorSearchRequest
 *     .forText("What does Alice like to eat?", PropositionQuery.againstContext("alice-session"))
 *     .withTopK(5)
 *     .withMinScore(SimilarityScore.ofCosine(0.6));
 * List<VectorMatch> matches = search.findNearest(request);
 * ```
 *
 * ```kotlin
 * val matches = search.findNearest(
 *     VectorSearchRequest("What does Alice like to eat?", PropositionQuery.againstContext("alice-session")) {
 *         topK = 5
 *         minScore = SimilarityScore.ofCosine(0.6)
 *     },
 * )
 * ```
 *
 * An implementation keeps these rules:
 *
 * 1. The filter is applied first. The store selects the propositions that match
 *    [VectorSearchRequest.filter] and returns the nearest [VectorSearchRequest.topK] of those. It
 *    does not pick the nearest K of all propositions and then drop the ones that fail the filter.
 *    An exact scan keeps this rule. An approximate index keeps it when it applies the filter while
 *    it searches, and the matches are then the nearest the index finds.
 * 2. Every field set on the filter is applied. Effective confidence is computed as of the
 *    filter's `effectiveConfidenceAsOf`, or the time of the call when that is null. A store that
 *    cannot apply a field throws [UnsupportedCapabilityException]. It never ignores one.
 * 3. No match scores below [VectorSearchRequest.minScore]. A match that scores equal to it is
 *    returned.
 * 4. Matches are ordered closest first. Matches with equal scores come back in the same order
 *    each time the same request runs on unchanged data. The cut to K is applied after ordering.
 * 5. A proposition with no embedding, or an all-zero embedding, is not returned and does not fail
 *    the search. A request whose text embeds to all zeros returns no matches.
 * 6. Candidate selection, filtering, the score floor, ordering and the cut to K run in the
 *    database. The store does not load rows into the JVM to do any of them.
 * 7. Each score is a [SimilarityScore] converted from the number the database returns.
 *
 * A store implements this interface only when it keeps rule 1, and then lists
 * [StoreCapability.FILTERED_VECTOR_SEARCH].
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
interface FilteredVectorSearch {

    /**
     * Finds the propositions closest to the request's text among those that match its filter.
     *
     * @param request What to search for.
     * @return Matches, closest first. Empty when nothing matches.
     * @throws UnsupportedCapabilityException when the store cannot search by similarity as wired,
     *   or cannot apply a field of the filter.
     */
    fun findNearest(request: VectorSearchRequest): List<VectorMatch>
}

/**
 * A similarity search: the text, the filter, the result size and the score floor.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property text The text to find propositions close to. The store embeds it with the model it
 *   embeds propositions with.
 * @property filter Which propositions are candidates.
 * @property topK The most matches to return.
 * @property minScore The lowest score to return, or null for no floor.
 */
@ApiStatus.Experimental
class VectorSearchRequest private constructor(
    val text: String,
    filter: PropositionQuery,
    val topK: Int,
    val minScore: SimilarityScore?,
) {

    val filter: PropositionQuery = requireNarrowingFilter(filter)

    init {
        require(text.isNotBlank()) { "The text to search for must not be blank" }
        require(topK >= 1) { "topK must be at least 1, but was $topK" }
    }

    /**
     * @param topK The most matches to return.
     * @return A request for at most [topK] matches.
     * @throws IllegalArgumentException when [topK] is less than 1.
     */
    fun withTopK(topK: Int): VectorSearchRequest = VectorSearchRequest(text, filter, topK, minScore)

    /**
     * @param minScore The lowest score to return, or null for no floor.
     * @return A request that returns no match scoring below [minScore].
     */
    fun withMinScore(minScore: SimilarityScore?): VectorSearchRequest = VectorSearchRequest(text, filter, topK, minScore)

    override fun equals(other: Any?): Boolean =
        other is VectorSearchRequest && other.text == text && other.filter == filter &&
            other.topK == topK && other.minScore == minScore

    override fun hashCode(): Int = Objects.hash(text, filter, topK, minScore)

    override fun toString(): String = "VectorSearchRequest(text=$text, filter=$filter, topK=$topK, minScore=$minScore)"

    companion object {

        /** The result size of a request that does not set one. */
        const val DEFAULT_TOP_K: Int = 10

        /**
         * @param text The text to find propositions close to.
         * @param filter Which propositions are candidates. Start it from a context, as with any
         *   `PropositionQuery`.
         * @return A request for [DEFAULT_TOP_K] matches with no score floor.
         * @throws IllegalArgumentException when [text] is blank, or [filter] carries an ordering
         *   or a limit.
         */
        @JvmStatic
        fun forText(text: String, filter: PropositionQuery): VectorSearchRequest =
            VectorSearchRequest(text, filter, DEFAULT_TOP_K, null)

        /**
         * Builds a request in a block. Java uses [forText] and the `with` chain.
         *
         * @param text The text to find propositions close to.
         * @param filter Which propositions are candidates.
         * @param block Sets the result size and the score floor.
         * @return The request.
         * @throws IllegalArgumentException for the inputs the chain rejects.
         */
        @JvmSynthetic
        operator fun invoke(
            text: String,
            filter: PropositionQuery,
            block: VectorSearchRequestBuilder.() -> Unit = {},
        ): VectorSearchRequest {
            val builder = VectorSearchRequestBuilder().apply(block)
            return VectorSearchRequest(text, filter, builder.topK, builder.minScore)
        }
    }
}

/**
 * Collects the options of a `VectorSearchRequest(text, filter) { }` block. It exists only inside
 * the block.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@StoreContractDsl
@ApiStatus.Experimental
class VectorSearchRequestBuilder internal constructor() {

    /** The most matches to return. */
    var topK: Int = VectorSearchRequest.DEFAULT_TOP_K

    /** The lowest score to return, or null for no floor. */
    var minScore: SimilarityScore? = null
}

/**
 * One result of a similarity search.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property proposition The proposition that matched.
 * @property score How close it is to the request's text.
 */
@ApiStatus.Experimental
class VectorMatch private constructor(
    val proposition: Proposition,
    val score: SimilarityScore,
) {

    override fun equals(other: Any?): Boolean =
        other is VectorMatch && other.proposition == proposition && other.score == score

    override fun hashCode(): Int = Objects.hash(proposition, score)

    override fun toString(): String = "VectorMatch(proposition=${proposition.id}, score=$score)"

    companion object {

        /**
         * @param proposition The proposition that matched.
         * @param score How close it is to the request's text.
         * @return The match.
         */
        @JvmStatic
        fun of(proposition: Proposition, score: SimilarityScore): VectorMatch = VectorMatch(proposition, score)
    }
}
