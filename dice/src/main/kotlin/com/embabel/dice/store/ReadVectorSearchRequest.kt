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
import java.util.Objects

/**
 * The request of a scoped similarity search: the text, the criteria, the result size and the
 * score floor.
 *
 * ```java
 * ReadVectorSearchRequest request = ReadVectorSearchRequest.forText("What does Alice eat?")
 *     .withCriteria(ReadCriteria.empty().withStatus(PropositionStatus.ACTIVE))
 *     .withTopK(5)
 *     .withMinScore(SimilarityScore.ofCosine(0.6));
 * ```
 *
 * ```kotlin
 * val request = ReadVectorSearchRequest("What does Alice eat?") {
 *     topK = 5
 *     minScore = SimilarityScore.ofCosine(0.6)
 * }
 * ```
 *
 * A request names no context. The [ScopedReadSpec] passed with it names the contexts.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property text The text to find propositions close to. The store embeds it with the model it
 *   embeds propositions with.
 * @property criteria Which propositions are candidates, among the ones the spec allows.
 * @property topK The most matches to return, at least 1. The read's [ReadBudget] refuses a
 *   value above its row cap.
 * @property minScore The lowest score to return, or null for no floor. A match that scores equal
 *   to it is returned.
 */
@ApiStatus.Experimental
class ReadVectorSearchRequest private constructor(
    val text: String,
    val criteria: ReadCriteria,
    val topK: Int,
    val minScore: SimilarityScore?,
) {

    init {
        require(text.isNotBlank()) { "The text to search for must not be blank" }
        require(topK >= 1) { "topK must be at least 1, but was $topK" }
    }

    /**
     * @param criteria Which propositions are candidates.
     * @return A request with these criteria.
     */
    fun withCriteria(criteria: ReadCriteria): ReadVectorSearchRequest =
        ReadVectorSearchRequest(text, criteria, topK, minScore)

    /**
     * @param topK The most matches to return.
     * @return A request for at most [topK] matches.
     * @throws IllegalArgumentException when [topK] is less than 1.
     */
    fun withTopK(topK: Int): ReadVectorSearchRequest = ReadVectorSearchRequest(text, criteria, topK, minScore)

    /**
     * @param minScore The lowest score to return, or null for no floor.
     * @return A request that returns no match scoring below [minScore].
     */
    fun withMinScore(minScore: SimilarityScore?): ReadVectorSearchRequest =
        ReadVectorSearchRequest(text, criteria, topK, minScore)

    /**
     * Checks that a store applies every criterion this request sets. A store calls it before it
     * embeds the text or reads.
     *
     * @param supportedCriteria The criteria the store applies.
     * @param capability The capability the search belongs to, carried by the exception.
     * @throws UnsupportedCapabilityException naming the first criterion that is not in
     *   [supportedCriteria].
     */
    fun requireOnly(supportedCriteria: Set<ReadCriterion>, capability: StoreCapability) {
        criteria.requireOnly(supportedCriteria, capability)
    }

    override fun equals(other: Any?): Boolean =
        other is ReadVectorSearchRequest && other.text == text && other.criteria == criteria &&
            other.topK == topK && other.minScore == minScore

    override fun hashCode(): Int = Objects.hash(text, criteria, topK, minScore)

    override fun toString(): String =
        "ReadVectorSearchRequest(text=$text, criteria=$criteria, topK=$topK, minScore=$minScore)"

    companion object {

        /**
         * @param text The text to find propositions close to.
         * @return A request for [VectorSearchRequest.DEFAULT_TOP_K] matches with no score floor
         *   and criteria that keep every proposition the spec allows.
         * @throws IllegalArgumentException when [text] is blank.
         */
        @JvmStatic
        fun forText(text: String): ReadVectorSearchRequest =
            ReadVectorSearchRequest(text, ReadCriteria.empty(), VectorSearchRequest.DEFAULT_TOP_K, null)

        /**
         * Builds a request in a block. Java uses [forText] and the `with` chain.
         *
         * @param text The text to find propositions close to.
         * @param block Sets the criteria, the result size and the score floor.
         * @return The request.
         * @throws IllegalArgumentException for the inputs the chain rejects.
         */
        @JvmSynthetic
        operator fun invoke(
            text: String,
            block: ReadVectorSearchRequestBuilder.() -> Unit = {},
        ): ReadVectorSearchRequest {
            val builder = ReadVectorSearchRequestBuilder().apply(block)
            return ReadVectorSearchRequest(text, builder.criteria, builder.topK, builder.minScore)
        }
    }
}

/**
 * Collects the options of a `ReadVectorSearchRequest(text) { }` block. It exists only inside the
 * block.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@StoreContractDsl
@ApiStatus.Experimental
class ReadVectorSearchRequestBuilder internal constructor() {

    /** Which propositions are candidates. */
    var criteria: ReadCriteria = ReadCriteria.empty()

    /** The most matches to return. */
    var topK: Int = VectorSearchRequest.DEFAULT_TOP_K

    /** The lowest score to return, or null for no floor. */
    var minScore: SimilarityScore? = null
}
