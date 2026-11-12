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
 * Ranked word search over proposition text. It returns the propositions that best match some
 * words among the propositions that match a filter.
 *
 * ```java
 * List<Proposition> matches = search.findMatching(
 *     FullTextSearchRequest
 *         .forText("peanut allergy", PropositionQuery.againstContext("alice-session"))
 *         .withLimit(20));
 * ```
 *
 * ```kotlin
 * val matches = search.findMatching(
 *     FullTextSearchRequest("peanut allergy", PropositionQuery.againstContext("alice-session")) {
 *         limit = 20
 *     },
 * )
 * ```
 *
 * An implementation keeps these rules:
 *
 * 1. The text is a list of words. A proposition matches when its text holds at least one of them,
 *    as the store's text index splits words. Characters and words that a search syntax treats as
 *    operators (quotes, `+`, `-`, `*`, `AND`, etc.) have no special meaning, so text typed by a
 *    user is passed in unchanged and cannot fail the search or change its meaning. Text with no
 *    searchable word returns no matches.
 * 2. The filter is applied first, and the best [FullTextSearchRequest.limit] of the matching
 *    propositions are returned.
 * 3. Every field set on the filter is applied. Effective confidence is computed as of the
 *    filter's `effectiveConfidenceAsOf`, or the time of the call when that is null. A store that
 *    cannot apply a field throws [UnsupportedCapabilityException]. It never ignores one.
 * 4. Matches are ordered best first. Equally good matches come back in the same order each time
 *    the same request runs on unchanged data. The cut to the limit is applied after ordering.
 * 5. Matching, filtering, ranking and the cut to the limit run in the database.
 *
 * The result carries no relevance number. A text index ranks against every proposition it holds,
 * so a number would describe propositions outside the filter. The position in the list is the
 * rank.
 *
 * A store implements this interface only when it ranks text in its database, and then lists
 * [StoreCapability.FULL_TEXT_SEARCH].
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
interface FullTextSearch {

    /**
     * Finds the propositions that best match the request's words among those that match its filter.
     *
     * @param request What to search for.
     * @return Matching propositions, best first. Empty when nothing matches.
     * @throws UnsupportedCapabilityException when the store cannot rank text as wired, or cannot
     *   apply a field of the filter.
     */
    fun findMatching(request: FullTextSearchRequest): List<Proposition>
}

/**
 * A full-text search: the words, the filter and the result size.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property text The words to look for.
 * @property filter Which propositions are candidates.
 * @property limit The most matches to return.
 */
@ApiStatus.Experimental
class FullTextSearchRequest private constructor(
    val text: String,
    filter: PropositionQuery,
    val limit: Int,
) {

    val filter: PropositionQuery = requireNarrowingFilter(filter)

    init {
        require(text.isNotBlank()) { "The text to search for must not be blank" }
        require(limit >= 1) { "limit must be at least 1, but was $limit" }
    }

    /**
     * @param limit The most matches to return.
     * @return A request for at most [limit] matches.
     * @throws IllegalArgumentException when [limit] is less than 1.
     */
    fun withLimit(limit: Int): FullTextSearchRequest = FullTextSearchRequest(text, filter, limit)

    override fun equals(other: Any?): Boolean =
        other is FullTextSearchRequest && other.text == text && other.filter == filter && other.limit == limit

    override fun hashCode(): Int = Objects.hash(text, filter, limit)

    override fun toString(): String = "FullTextSearchRequest(text=$text, filter=$filter, limit=$limit)"

    companion object {

        /** The result size of a request that does not set one. */
        const val DEFAULT_LIMIT: Int = 10

        /**
         * @param text The words to look for.
         * @param filter Which propositions are candidates. Start it from a context, as with any
         *   `PropositionQuery`.
         * @return A request for [DEFAULT_LIMIT] matches.
         * @throws IllegalArgumentException when [text] is blank, or [filter] carries an ordering
         *   or a limit.
         */
        @JvmStatic
        fun forText(text: String, filter: PropositionQuery): FullTextSearchRequest =
            FullTextSearchRequest(text, filter, DEFAULT_LIMIT)

        /**
         * Builds a request in a block. Java uses [forText] and the `with` chain.
         *
         * @param text The words to look for.
         * @param filter Which propositions are candidates.
         * @param block Sets the result size.
         * @return The request.
         * @throws IllegalArgumentException for the inputs the chain rejects.
         */
        @JvmSynthetic
        operator fun invoke(
            text: String,
            filter: PropositionQuery,
            block: FullTextSearchRequestBuilder.() -> Unit = {},
        ): FullTextSearchRequest = FullTextSearchRequest(text, filter, FullTextSearchRequestBuilder().apply(block).limit)
    }
}

/**
 * Collects the options of a `FullTextSearchRequest(text, filter) { }` block. It exists only
 * inside the block.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@StoreContractDsl
@ApiStatus.Experimental
class FullTextSearchRequestBuilder internal constructor() {

    /** The most matches to return. */
    var limit: Int = FullTextSearchRequest.DEFAULT_LIMIT
}
