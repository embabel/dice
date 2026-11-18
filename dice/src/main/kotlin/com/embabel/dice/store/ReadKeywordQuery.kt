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
import java.util.Collections
import java.util.Locale
import java.util.Objects

/**
 * The request of a scoped keyword match: the tokens to look for, the criteria, the order among
 * equal matches and the most rows it returns.
 *
 * ```java
 * ReadKeywordQuery mentions = ReadKeywordQuery.forTokens("peanut", "allergy")
 *     .withCriteria(ReadCriteria.empty().withStatus(PropositionStatus.ACTIVE))
 *     .withLimit(10);
 * ```
 *
 * ```kotlin
 * val mentions = ReadKeywordQuery(listOf("peanut", "allergy")) { limit = 10 }
 * ```
 *
 * A token is literal text. It carries no operator and no wildcard, and it is not trimmed. A
 * request holds its tokens lower-cased with `Locale.ROOT`, with duplicates dropped, so tokens
 * that differ only in case count once. A proposition matches when its text, lower-cased (with
 * `Locale.ROOT` in the JVM), contains at least one token.
 *
 * A request names no context. The [ScopedReadSpec] passed with it names the contexts.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property criteria Which propositions can match, among the ones the spec allows.
 * @property order The order among propositions that contain the same number of tokens.
 * @property limit The most rows to return, at least 1, or null for every match. The read's
 *   [ReadBudget] refuses a limit above its row cap.
 */
@ApiStatus.Experimental
class ReadKeywordQuery private constructor(
    tokens: Collection<String>,
    val criteria: ReadCriteria,
    val order: ReadOrder,
    val limit: Int?,
) {

    /** The tokens to look for, lower-cased, in the order given, with no duplicates. Never empty. Read-only. */
    val tokens: List<String> = requireTokens(tokens)

    init {
        require(limit == null || limit >= 1) { "limit must be at least 1, but was $limit" }
    }

    /**
     * @param criteria Which propositions can match.
     * @return A request with these criteria.
     */
    fun withCriteria(criteria: ReadCriteria): ReadKeywordQuery = ReadKeywordQuery(tokens, criteria, order, limit)

    /**
     * @param order The order among propositions that contain the same number of tokens.
     * @return A request with this order.
     */
    fun withOrder(order: ReadOrder): ReadKeywordQuery = ReadKeywordQuery(tokens, criteria, order, limit)

    /**
     * @param limit The most rows to return.
     * @return A request that returns at most [limit] rows.
     * @throws IllegalArgumentException when [limit] is less than 1.
     */
    fun withLimit(limit: Int): ReadKeywordQuery = ReadKeywordQuery(tokens, criteria, order, limit)

    /**
     * Checks that a store applies every criterion this request sets and its order. A store calls
     * it before it reads.
     *
     * @param supportedCriteria The criteria the store applies.
     * @param supportedOrders The orders the store applies. [ReadOrder.NONE] always passes.
     * @param capability The capability the read belongs to, carried by the exception.
     * @throws UnsupportedCapabilityException naming the first criterion that is not in
     *   [supportedCriteria], or the order when it is not in [supportedOrders].
     */
    fun requireOnly(supportedCriteria: Set<ReadCriterion>, supportedOrders: Set<ReadOrder>, capability: StoreCapability) {
        criteria.requireOnly(supportedCriteria, capability)
        requireOrder(order, supportedOrders, capability)
    }

    override fun equals(other: Any?): Boolean =
        other is ReadKeywordQuery && other.tokens == tokens && other.criteria == criteria &&
            other.order == order && other.limit == limit

    override fun hashCode(): Int = Objects.hash(tokens, criteria, order, limit)

    override fun toString(): String =
        "ReadKeywordQuery(tokens=<${tokens.size} tokens>, criteria=$criteria, order=$order, limit=$limit)"

    companion object {

        /**
         * @param tokens The words to look for.
         * @return A request with no limit, the store's own order among equal matches and criteria
         *   that keep every proposition the spec allows.
         * @throws IllegalArgumentException when [tokens] is empty or holds a null or blank
         *   token.
         */
        @JvmStatic
        fun forTokens(tokens: Collection<String>): ReadKeywordQuery =
            ReadKeywordQuery(tokens, ReadCriteria.empty(), ReadOrder.NONE, null)

        /**
         * @param first A word to look for.
         * @param more Further words to look for.
         * @return A request with no limit, the store's own order among equal matches and criteria
         *   that keep every proposition the spec allows.
         * @throws IllegalArgumentException when a token is blank, or a token in [more] is null.
         * @throws NullPointerException when [first] is null.
         */
        @JvmStatic
        fun forTokens(first: String, vararg more: String): ReadKeywordQuery = forTokens(listOf(first, *more))

        /**
         * Builds a request in a block. Java uses [forTokens] and the `with` chain.
         *
         * @param tokens The words to look for.
         * @param block Sets the criteria, the order and the limit.
         * @return The request.
         * @throws IllegalArgumentException for the inputs the chain rejects.
         */
        @JvmSynthetic
        operator fun invoke(
            tokens: Collection<String>,
            block: ReadKeywordQueryBuilder.() -> Unit = {},
        ): ReadKeywordQuery {
            val builder = ReadKeywordQueryBuilder().apply(block)
            return ReadKeywordQuery(tokens, builder.criteria, builder.order, builder.limit)
        }
    }
}

/**
 * Collects the options of a `ReadKeywordQuery(tokens) { }` block. It exists only inside the
 * block.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@StoreContractDsl
@ApiStatus.Experimental
class ReadKeywordQueryBuilder internal constructor() {

    /** Which propositions can match. */
    var criteria: ReadCriteria = ReadCriteria.empty()

    /** The order among propositions that contain the same number of tokens. */
    var order: ReadOrder = ReadOrder.NONE

    /** The most rows to return, or null for every match. */
    var limit: Int? = null
}

/**
 * Checks the tokens of a keyword request and copies them into a read-only list.
 *
 * @param tokens The tokens to check.
 * @return The tokens lower-cased with `Locale.ROOT`, in the order given, with duplicates dropped.
 * @throws IllegalArgumentException when [tokens] is empty or holds a null or blank token.
 */
private fun requireTokens(tokens: Collection<String>): List<String> {
    // A Java caller can pass a collection that holds null.
    val given: List<String?> = tokens.toList()
    require(given.isNotEmpty()) { "A keyword request names at least one token" }
    require(given.none { it.isNullOrBlank() }) { "A keyword token must not be null or blank" }
    return Collections.unmodifiableList(given.filterNotNull().map { it.lowercase(Locale.ROOT) }.distinct())
}
