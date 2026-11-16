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
 * The most one scoped read may take from a store. A host builds it and passes it with each read.
 *
 * ```java
 * ReadBudget budget = ReadBudget.ofRows(200).withMaxVectorCandidates(5_000);
 * ```
 *
 * A store never shortens a result to fit a budget. A read that needs more than the budget allows
 * throws [ReadBudgetExceededException]. Its [ReadBudgetResource] says what was refused and what
 * the caller can do about it.
 *
 * A budget is a plain value with public factories. Code that holds one can build another with
 * larger caps, so a budget binds only code that passes it on unchanged.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property maxRows The most propositions one read returns or counts. From 1 to [MAX_CAP].
 * @property maxVectorCandidates The most vector candidates one scoped similarity search may have.
 *   A vector candidate is a candidate of the search that has an embedding, and the search
 *   compares the request's text with each one. From 0 to [MAX_CAP]. Zero allows no similarity
 *   search, and [isVectorSearchAllowed] is then false.
 */
@ApiStatus.Experimental
class ReadBudget private constructor(
    val maxRows: Int,
    val maxVectorCandidates: Int,
) {

    init {
        require(maxRows in 1..MAX_CAP) { "maxRows must be from 1 to $MAX_CAP, but was $maxRows" }
        require(maxVectorCandidates in 0..MAX_CAP) {
            "maxVectorCandidates must be from 0 to $MAX_CAP, but was $maxVectorCandidates"
        }
    }

    /**
     * Whether this budget allows similarity search. True when [maxVectorCandidates] is above zero.
     * A caller reads it before a scoped similarity search to learn whether the search can run.
     */
    val isVectorSearchAllowed: Boolean get() = maxVectorCandidates > 0

    /**
     * @param maxRows The most propositions one read returns or counts.
     * @return A budget with this row cap.
     * @throws IllegalArgumentException when [maxRows] is less than 1 or more than [MAX_CAP].
     */
    fun withMaxRows(maxRows: Int): ReadBudget = ReadBudget(maxRows, maxVectorCandidates)

    /**
     * @param maxVectorCandidates The most vector candidates one similarity search may have. Zero
     *   allows no similarity search.
     * @return A budget with this candidate cap.
     * @throws IllegalArgumentException when [maxVectorCandidates] is negative or more than
     *   [MAX_CAP].
     */
    fun withMaxVectorCandidates(maxVectorCandidates: Int): ReadBudget = ReadBudget(maxRows, maxVectorCandidates)

    /**
     * Checks a row count against the budget. A store calls it with the limit a request asks for,
     * with the result size a similarity search asks for, with the number of rows a read with no
     * limit would return, and with the number a count reaches.
     *
     * A store passes the count its database returned as it is. A count narrowed to an `int`
     * first can wrap to a small number and pass the check.
     *
     * @param requested The rows the read needs.
     * @throws ReadBudgetExceededException carrying [ReadBudgetResource.ROWS] when [requested] is
     *   more than [maxRows].
     * @throws IllegalArgumentException when [requested] is negative.
     */
    fun requireRows(requested: Long) {
        require(requested >= 0) { "A row count must not be negative, but was $requested" }
        if (requested > maxRows) throw ReadBudgetExceededException(ReadBudgetResource.ROWS, maxRows, requested)
    }

    /**
     * Checks a row count that is already an `int`, such as a limit or a result size.
     *
     * @param requested The rows the read needs.
     * @throws ReadBudgetExceededException carrying [ReadBudgetResource.ROWS] when [requested] is
     *   more than [maxRows].
     * @throws IllegalArgumentException when [requested] is negative.
     */
    fun requireRows(requested: Int) {
        requireRows(requested.toLong())
    }

    /**
     * Checks that this budget allows similarity search. A store calls it in a scoped similarity
     * search before it embeds the text or reads.
     *
     * @throws ReadBudgetExceededException carrying [ReadBudgetResource.VECTOR_SEARCH] when
     *   [isVectorSearchAllowed] is false.
     */
    fun requireVectorSearch() {
        if (!isVectorSearchAllowed) throw ReadBudgetExceededException(ReadBudgetResource.VECTOR_SEARCH, 0, 0L)
    }

    /**
     * Checks a vector candidate count against the budget. A store calls it before a scoped
     * similarity search compares embeddings, with the number of candidates that have one, as its
     * database returned it. It makes the check of [requireVectorSearch] first.
     *
     * @param requested The vector candidates the search has.
     * @throws ReadBudgetExceededException carrying [ReadBudgetResource.VECTOR_SEARCH] when this
     *   budget allows no similarity search, and carrying [ReadBudgetResource.VECTOR_CANDIDATES]
     *   when [requested] is more than [maxVectorCandidates].
     * @throws IllegalArgumentException when [requested] is negative.
     */
    fun requireVectorCandidates(requested: Long) {
        require(requested >= 0) { "A vector candidate count must not be negative, but was $requested" }
        requireVectorSearch()
        if (requested > maxVectorCandidates) {
            throw ReadBudgetExceededException(ReadBudgetResource.VECTOR_CANDIDATES, maxVectorCandidates, requested)
        }
    }

    /**
     * Checks a vector candidate count that is already an `int`.
     *
     * @param requested The vector candidates the search has.
     * @throws ReadBudgetExceededException as the other form throws it.
     * @throws IllegalArgumentException when [requested] is negative.
     */
    fun requireVectorCandidates(requested: Int) {
        requireVectorCandidates(requested.toLong())
    }

    override fun equals(other: Any?): Boolean =
        other is ReadBudget && other.maxRows == maxRows && other.maxVectorCandidates == maxVectorCandidates

    override fun hashCode(): Int = 31 * maxRows + maxVectorCandidates

    override fun toString(): String = "ReadBudget(maxRows=$maxRows, maxVectorCandidates=$maxVectorCandidates)"

    companion object {

        /**
         * The largest cap. It is one below the largest `Int`, so a store can ask its database for
         * one row more than a cap to learn whether a read goes over it.
         */
        const val MAX_CAP: Int = Int.MAX_VALUE - 1

        /**
         * @param maxRows The most propositions one read returns or counts.
         * @return A budget with this row cap that allows no similarity search.
         * @throws IllegalArgumentException when [maxRows] is less than 1 or more than [MAX_CAP].
         */
        @JvmStatic
        fun ofRows(maxRows: Int): ReadBudget = ReadBudget(maxRows, 0)
    }
}

/**
 * What a [ReadBudget] did not allow. Each entry says what the caller can do.
 *
 * Entries are added over time. A Kotlin `when` and a Java `switch` expression over this enum
 * keep an `else` or `default` branch.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
enum class ReadBudgetResource {

    /**
     * Propositions a read returns or counts. The caller narrows the spec or the criteria, asks
     * for a smaller limit or result size, or is given a budget with a larger row cap.
     */
    ROWS,

    /**
     * Candidates of a scoped similarity search that have an embedding. The caller narrows the
     * spec or the criteria, or is given a budget with a larger candidate cap. A smaller result
     * size does not help, because the search compares the text with every candidate.
     */
    VECTOR_CANDIDATES,

    /**
     * Similarity search itself. The budget has no vector candidate cap, so it allows none.
     * Narrowing and retrying does not help. The host passes a budget built with
     * [ReadBudget.withMaxVectorCandidates], and a caller reads [ReadBudget.isVectorSearchAllowed]
     * before it searches.
     */
    VECTOR_SEARCH,
}
