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
 * Follows the links between a proposition and the propositions it was abstracted from, for more
 * than one step. It answers "what does this conclusion rest on" and "what rests on this fact".
 *
 * ```java
 * // Every proposition built on a retracted fact, up to three steps away.
 * List<Proposition> affected = traversal.traverse(
 *     TraversalRequest
 *         .abstractionsOf(retractedId, PropositionQuery.againstContext("alice-session"))
 *         .withMaxDepth(3));
 * ```
 *
 * ```kotlin
 * val affected = traversal.traverse(
 *     TraversalRequest.abstractionsOf(retractedId, PropositionQuery.againstContext("alice-session")) {
 *         maxDepth = 3
 *     },
 * )
 * ```
 *
 * An implementation keeps these rules:
 *
 * 1. The walk starts at [TraversalRequest.startId] and follows the abstraction links recorded in
 *    `Proposition.sourceIds` in the request's direction for at most [TraversalRequest.maxDepth]
 *    steps.
 * 2. The walk visits and returns only propositions that match [TraversalRequest.filter]. It does
 *    not continue through a proposition that fails the filter. When the start proposition is
 *    missing or fails the filter, the result is empty.
 * 3. Every field set on the filter is applied. Effective confidence is computed as of the
 *    filter's `effectiveConfidenceAsOf`, or the time of the call when that is null. A store that
 *    cannot apply a field throws [UnsupportedCapabilityException]. It never ignores one.
 * 4. The start proposition is not returned. Every other proposition reached is returned once,
 *    including when several routes or a cycle reach it.
 * 5. Results are ordered by the fewest steps from the start. Propositions the same number of
 *    steps away come back in the same order each time the same request runs on unchanged data.
 *    The cut to [TraversalRequest.limit] is applied after ordering.
 * 6. The walk, the filter, the ordering and the cut run in the database. The store does not load
 *    propositions into the JVM to follow links.
 *
 * A store implements this interface only when it follows links in its database, and then lists
 * [StoreCapability.ABSTRACTION_TRAVERSAL].
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
interface AbstractionTraversal {

    /**
     * Finds the propositions reachable from the request's start.
     *
     * @param request Where to start, which direction to follow and how far.
     * @return The propositions reached, nearest first, without the start. Empty when nothing is
     *   reachable.
     * @throws UnsupportedCapabilityException when the store cannot apply a field of the filter.
     */
    fun traverse(request: TraversalRequest): List<Proposition>
}

/**
 * The direction a traversal follows abstraction links.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
enum class TraversalDirection {

    /** From a proposition to the propositions it was abstracted from. */
    TO_SOURCES,

    /** From a proposition to the propositions abstracted from it. */
    TO_ABSTRACTIONS,
}

/**
 * A traversal: the start, the direction, the filter, the depth and the result size.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property startId The id of the proposition to start from.
 * @property direction The direction to follow abstraction links.
 * @property filter Which propositions the walk may visit.
 * @property maxDepth The most steps to take from the start, from 1 to [MAX_DEPTH].
 * @property limit The most propositions to return.
 */
@ApiStatus.Experimental
class TraversalRequest private constructor(
    val startId: String,
    val direction: TraversalDirection,
    filter: PropositionQuery,
    val maxDepth: Int,
    val limit: Int,
) {

    val filter: PropositionQuery = requireNarrowingFilter(filter)

    init {
        require(startId.isNotBlank()) { "The id to start from must not be blank" }
        require(maxDepth in 1..MAX_DEPTH) { "maxDepth must be from 1 to $MAX_DEPTH, but was $maxDepth" }
        require(limit >= 1) { "limit must be at least 1, but was $limit" }
    }

    /**
     * @param maxDepth The most steps to take from the start.
     * @return A request that walks at most [maxDepth] steps.
     * @throws IllegalArgumentException when [maxDepth] is outside 1 to [MAX_DEPTH].
     */
    fun withMaxDepth(maxDepth: Int): TraversalRequest = TraversalRequest(startId, direction, filter, maxDepth, limit)

    /**
     * @param limit The most propositions to return.
     * @return A request for at most [limit] propositions.
     * @throws IllegalArgumentException when [limit] is less than 1.
     */
    fun withLimit(limit: Int): TraversalRequest = TraversalRequest(startId, direction, filter, maxDepth, limit)

    override fun equals(other: Any?): Boolean =
        other is TraversalRequest && other.startId == startId && other.direction == direction &&
            other.filter == filter && other.maxDepth == maxDepth && other.limit == limit

    override fun hashCode(): Int = Objects.hash(startId, direction, filter, maxDepth, limit)

    override fun toString(): String =
        "TraversalRequest(startId=$startId, direction=$direction, filter=$filter, maxDepth=$maxDepth, limit=$limit)"

    companion object {

        /** The deepest walk a request can ask for. */
        const val MAX_DEPTH: Int = 16

        /** The result size of a request that does not set one. */
        const val DEFAULT_LIMIT: Int = 100

        /**
         * @param startId The id of the proposition to start from.
         * @param filter Which propositions the walk may visit. Start it from a context, as with
         *   any `PropositionQuery`.
         * @return A request for one step toward the propositions [startId] was abstracted from,
         *   with [DEFAULT_LIMIT] results.
         * @throws IllegalArgumentException when [startId] is blank, or [filter] carries an
         *   ordering or a limit.
         */
        @JvmStatic
        fun sourcesOf(startId: String, filter: PropositionQuery): TraversalRequest =
            TraversalRequest(startId, TraversalDirection.TO_SOURCES, filter, 1, DEFAULT_LIMIT)

        /**
         * @param startId The id of the proposition to start from.
         * @param filter Which propositions the walk may visit. Start it from a context, as with
         *   any `PropositionQuery`.
         * @return A request for one step toward the propositions abstracted from [startId], with
         *   [DEFAULT_LIMIT] results.
         * @throws IllegalArgumentException when [startId] is blank, or [filter] carries an
         *   ordering or a limit.
         */
        @JvmStatic
        fun abstractionsOf(startId: String, filter: PropositionQuery): TraversalRequest =
            TraversalRequest(startId, TraversalDirection.TO_ABSTRACTIONS, filter, 1, DEFAULT_LIMIT)

        /**
         * Builds a request toward sources in a block. Java uses [sourcesOf] and the `with` chain.
         *
         * @param startId The id of the proposition to start from.
         * @param filter Which propositions the walk may visit.
         * @param block Sets the depth and the result size.
         * @return The request.
         * @throws IllegalArgumentException for the inputs the chain rejects.
         */
        @JvmSynthetic
        fun sourcesOf(
            startId: String,
            filter: PropositionQuery,
            block: TraversalRequestBuilder.() -> Unit,
        ): TraversalRequest = build(startId, TraversalDirection.TO_SOURCES, filter, block)

        /**
         * Builds a request toward abstractions in a block. Java uses [abstractionsOf] and the
         * `with` chain.
         *
         * @param startId The id of the proposition to start from.
         * @param filter Which propositions the walk may visit.
         * @param block Sets the depth and the result size.
         * @return The request.
         * @throws IllegalArgumentException for the inputs the chain rejects.
         */
        @JvmSynthetic
        fun abstractionsOf(
            startId: String,
            filter: PropositionQuery,
            block: TraversalRequestBuilder.() -> Unit,
        ): TraversalRequest = build(startId, TraversalDirection.TO_ABSTRACTIONS, filter, block)

        /**
         * Runs a block against a new builder and builds the request it describes.
         *
         * @param startId The id of the proposition to start from.
         * @param direction The direction to follow abstraction links.
         * @param filter Which propositions the walk may visit.
         * @param block Sets the depth and the result size.
         * @return The request.
         */
        private fun build(
            startId: String,
            direction: TraversalDirection,
            filter: PropositionQuery,
            block: TraversalRequestBuilder.() -> Unit,
        ): TraversalRequest {
            val builder = TraversalRequestBuilder().apply(block)
            return TraversalRequest(startId, direction, filter, builder.maxDepth, builder.limit)
        }
    }
}

/**
 * Collects the options of a `TraversalRequest.sourcesOf(id, filter) { }` block. It exists only
 * inside the block.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@StoreContractDsl
@ApiStatus.Experimental
class TraversalRequestBuilder internal constructor() {

    /** The most steps to take from the start. */
    var maxDepth: Int = 1

    /** The most propositions to return. */
    var limit: Int = TraversalRequest.DEFAULT_LIMIT
}
