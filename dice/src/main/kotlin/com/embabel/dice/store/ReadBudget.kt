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
 * throws [ReadBudgetExceededException]. The caller then narrows the spec, asks for fewer rows or
 * is given a larger budget.
 *
 * A budget is a plain value. Code that holds one can build another with larger caps.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property maxRows The most propositions one read returns. From 1 to [MAX_CAP].
 * @property maxVectorCandidates The most vector candidates one scoped similarity search may have.
 *   A vector candidate is a candidate of the search that has an embedding, and the search
 *   compares the request's text with each one. From 0 to [MAX_CAP]. Zero allows no similarity
 *   search.
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
     * @param maxRows The most propositions one read returns.
     * @return A budget with this row cap.
     * @throws IllegalArgumentException when [maxRows] is less than 1 or more than [MAX_CAP].
     */
    fun withMaxRows(maxRows: Int): ReadBudget = ReadBudget(maxRows, maxVectorCandidates)

    /**
     * @param maxVectorCandidates The most vector candidates one similarity search may have.
     * @return A budget with this candidate cap.
     * @throws IllegalArgumentException when [maxVectorCandidates] is negative or more than
     *   [MAX_CAP].
     */
    fun withMaxVectorCandidates(maxVectorCandidates: Int): ReadBudget = ReadBudget(maxRows, maxVectorCandidates)

    /**
     * Checks a row count against the budget. A store calls it with the limit a read asks for, and
     * with the number of rows the read would return when the read sets no limit.
     *
     * @param requested The rows the read needs.
     * @throws ReadBudgetExceededException when [requested] is more than [maxRows].
     * @throws IllegalArgumentException when [requested] is negative.
     */
    fun requireRows(requested: Int) {
        require(requested >= 0) { "A row count must not be negative, but was $requested" }
        if (requested > maxRows) throw ReadBudgetExceededException(ReadBudgetResource.ROWS, maxRows, requested)
    }

    /**
     * Checks a vector candidate count against the budget. A store calls it before a scoped
     * similarity search compares embeddings, with the number of candidates that have one.
     *
     * @param requested The vector candidates the search has.
     * @throws ReadBudgetExceededException when [maxVectorCandidates] is zero, whatever
     *   [requested] is, and when [requested] is more than [maxVectorCandidates].
     * @throws IllegalArgumentException when [requested] is negative.
     */
    fun requireVectorCandidates(requested: Int) {
        if (maxVectorCandidates == 0) {
            throw ReadBudgetExceededException(ReadBudgetResource.VECTOR_CANDIDATES, 0, requested)
        }
        require(requested >= 0) { "A vector candidate count must not be negative, but was $requested" }
        if (requested > maxVectorCandidates) {
            throw ReadBudgetExceededException(ReadBudgetResource.VECTOR_CANDIDATES, maxVectorCandidates, requested)
        }
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
         * @param maxRows The most propositions one read returns.
         * @return A budget with this row cap that allows no similarity search.
         * @throws IllegalArgumentException when [maxRows] is less than 1 or more than [MAX_CAP].
         */
        @JvmStatic
        fun ofRows(maxRows: Int): ReadBudget = ReadBudget(maxRows, 0)
    }
}

/**
 * What a [ReadBudget] caps.
 *
 * Entries are added over time. A Kotlin `when` and a Java `switch` expression over this enum
 * keep an `else` or `default` branch.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
enum class ReadBudgetResource {

    /** Propositions a read returns. */
    ROWS,

    /** Candidates of a scoped similarity search that have an embedding. */
    VECTOR_CANDIDATES,
}

/**
 * Thrown when a read needs more than its [ReadBudget] allows. The read returns nothing.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property resource Which cap the read went over.
 * @property allowed The cap.
 * @property requested What the read needed, as the store counted it. When a store stops counting
 *   at the cap, this is one more than [allowed]. Under a zero vector candidate cap it is the
 *   count the store passed, which can be 0.
 */
@ApiStatus.Experimental
class ReadBudgetExceededException(
    val resource: ReadBudgetResource,
    val allowed: Int,
    val requested: Int,
) : RuntimeException(
    when (resource) {
        ReadBudgetResource.ROWS -> "The read needs $requested rows and its budget allows $allowed"
        ReadBudgetResource.VECTOR_CANDIDATES ->
            "The similarity search has $requested vector candidates and its budget allows $allowed"
    },
) {

    private companion object {
        /** Fixed, so a serialized instance stays readable when the class changes. */
        private const val serialVersionUID: Long = 1L
    }
}
