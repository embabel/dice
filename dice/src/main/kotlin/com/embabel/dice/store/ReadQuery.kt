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
 * The request of a scoped query: the criteria, the order of the result and the most rows it
 * returns.
 *
 * ```java
 * ReadQuery recent = ReadQuery.of(ReadCriteria.empty().withEntityId("alice"))
 *     .orderedByCreated()
 *     .withLimit(20);
 * ```
 *
 * ```kotlin
 * val recent = ReadQuery(ReadCriteria { entityId = "alice" }) {
 *     order = ReadOrder.CREATED_DESC
 *     limit = 20
 * }
 * ```
 *
 * A query names no context. The [ScopedReadSpec] passed with it names the contexts.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property criteria Which propositions the query keeps, among the ones the spec allows.
 * @property order The order of the result.
 * @property limit The most rows to return, at least 1, or null for every row. The read's
 *   [ReadBudget] refuses a limit above its row cap.
 */
@ApiStatus.Experimental
class ReadQuery private constructor(
    val criteria: ReadCriteria,
    val order: ReadOrder,
    val limit: Int?,
) {

    init {
        require(limit == null || limit >= 1) { "limit must be at least 1, but was $limit" }
    }

    /**
     * @param order The order of the result.
     * @return A query with this order.
     */
    fun withOrder(order: ReadOrder): ReadQuery = ReadQuery(criteria, order, limit)

    /** @return A query ordered by effective confidence, highest first. */
    fun orderedByEffectiveConfidence(): ReadQuery = withOrder(ReadOrder.EFFECTIVE_CONFIDENCE_DESC)

    /** @return A query ordered by creation time, newest first. */
    fun orderedByCreated(): ReadQuery = withOrder(ReadOrder.CREATED_DESC)

    /** @return A query ordered by revision time, newest first. */
    fun orderedByRevised(): ReadQuery = withOrder(ReadOrder.REVISED_DESC)

    /** @return A query ordered by last access, newest first. */
    fun orderedByLastAccessed(): ReadQuery = withOrder(ReadOrder.LAST_ACCESSED_DESC)

    /** @return A query ordered by reinforcement count, highest first. */
    fun orderedByReinforceCount(): ReadQuery = withOrder(ReadOrder.REINFORCE_COUNT_DESC)

    /** @return A query ordered by importance, highest first. */
    fun orderedByImportance(): ReadQuery = withOrder(ReadOrder.IMPORTANCE_DESC)

    /**
     * @param limit The most rows to return.
     * @return A query that returns at most [limit] rows.
     * @throws IllegalArgumentException when [limit] is less than 1.
     */
    fun withLimit(limit: Int): ReadQuery = ReadQuery(criteria, order, limit)

    /**
     * Checks that a store applies every criterion this query sets and its order. A store calls it
     * before it reads.
     *
     * ```java
     * query.requireOnly(APPLIED_CRITERIA, APPLIED_ORDERS, StoreCapability.SCOPED_READS);
     * ```
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
        other is ReadQuery && other.criteria == criteria && other.order == order && other.limit == limit

    override fun hashCode(): Int = Objects.hash(criteria, order, limit)

    override fun toString(): String = "ReadQuery(criteria=$criteria, order=$order, limit=$limit)"

    companion object {

        /**
         * @param criteria Which propositions the query keeps. [ReadCriteria.empty] keeps every
         *   proposition the spec allows.
         * @return A query with no order and no limit.
         */
        @JvmStatic
        fun of(criteria: ReadCriteria): ReadQuery = ReadQuery(criteria, ReadOrder.NONE, null)

        /**
         * Builds a query in a block. Java uses [of] and the `with` chain.
         *
         * @param criteria Which propositions the query keeps.
         * @param block Sets the order and the limit.
         * @return The query.
         * @throws IllegalArgumentException when the block sets a limit less than 1.
         */
        @JvmSynthetic
        operator fun invoke(criteria: ReadCriteria, block: ReadQueryBuilder.() -> Unit = {}): ReadQuery {
            val builder = ReadQueryBuilder().apply(block)
            return ReadQuery(criteria, builder.order, builder.limit)
        }
    }
}

/**
 * The order of a scoped read's result. Each entry names the stored value of a
 * [com.embabel.dice.proposition.Proposition] it sorts by. Results that rank equally come back in
 * the same order each time the same call runs on unchanged data.
 *
 * Entries are added over time. A Kotlin `when` and a Java `switch` expression over this enum
 * keep an `else` or `default` branch. A store lists the orders it applies by name and passes
 * them to `requireOnly` on the request, so an order added later throws and is never skipped.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
enum class ReadOrder {

    /** The store's own order. Every store applies it. */
    NONE,

    /**
     * Highest effective confidence first. Effective confidence is
     * [com.embabel.dice.proposition.Proposition.effectiveConfidenceAt] at the instant of the call,
     * with the `decayK` of the request's criteria.
     */
    EFFECTIVE_CONFIDENCE_DESC,

    /** Latest `created` first. */
    CREATED_DESC,

    /** Latest `lastTouched` first. */
    REVISED_DESC,

    /** Latest `lastAccessed` first. */
    LAST_ACCESSED_DESC,

    /** Highest `reinforceCount` first. */
    REINFORCE_COUNT_DESC,

    /** Highest `importance` first. */
    IMPORTANCE_DESC,
}

/**
 * Collects the options of a `ReadQuery(criteria) { }` block. It exists only inside the block.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@StoreContractDsl
@ApiStatus.Experimental
class ReadQueryBuilder internal constructor() {

    /** The order of the result. */
    var order: ReadOrder = ReadOrder.NONE

    /** The most rows to return, or null for every row. */
    var limit: Int? = null
}

/**
 * Checks that an order is one a store applies.
 *
 * @param order The order a request asks for.
 * @param supported The orders the store applies.
 * @param capability The capability the read belongs to, carried by the exception.
 * @throws UnsupportedCapabilityException when [order] is not [ReadOrder.NONE] and is not in
 *   [supported].
 */
internal fun requireOrder(order: ReadOrder, supported: Set<ReadOrder>, capability: StoreCapability) {
    if (order != ReadOrder.NONE && order !in supported) {
        throw UnsupportedCapabilityException(capability, "the store cannot apply the order $order")
    }
}
