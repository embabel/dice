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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import java.time.Duration
import java.time.Instant

/**
 * Composable query specification for propositions.
 *
 * **Important**: Always start with a scoped factory method to avoid accidentally
 * querying all propositions. Use [againstContext] or [forContextId] as the entry point.
 *
 * Kotlin usage (direct construction with defaults):
 * ```kotlin
 * val query = PropositionQuery(
 *     contextId = sessionContext,
 *     entityId = "user-123",
 *     minLevel = 0,
 *     orderBy = OrderBy.EFFECTIVE_CONFIDENCE_DESC,
 * )
 * ```
 *
 * Java usage (builder pattern via withers):
 * ```java
 * PropositionQuery query = PropositionQuery.againstContext("session-123")
 *     .withEntityId("user-123")
 *     .withMinLevel(0)
 *     .withOrderBy(OrderBy.EFFECTIVE_CONFIDENCE_DESC);
 * ```
 */
data class PropositionQuery(
    // Scope filters
    val contextId: ContextId? = null,
    val entityId: String? = null,
    val anyEntityIds: List<String>? = null,
    val allEntityIds: List<String>? = null,

    // Status and level filters
    val status: PropositionStatus? = null,
    val minLevel: Int? = null,
    val maxLevel: Int? = null,

    // Temporal filters
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val revisedAfter: Instant? = null,
    val revisedBefore: Instant? = null,

    // Confidence filters (with decay)
    val minEffectiveConfidence: Double? = null,
    val effectiveConfidenceAsOf: Instant? = null,
    val decayK: Double = 2.0,

    // Reinforcement filter
    val minReinforceCount: Int? = null,

    // Ordering and limits
    val orderBy: OrderBy = OrderBy.NONE,
    val limit: Int? = null,
) {

    /**
     * Ordering options for query results.
     */
    enum class OrderBy {
        NONE,
        EFFECTIVE_CONFIDENCE_DESC,
        CREATED_DESC,
        REVISED_DESC,
        REINFORCE_COUNT_DESC,
    }

    // ========================================================================
    // Java-friendly accessors (ContextId is an inline class, so getter is mangled)
    // ========================================================================

    /**
     * Get the context ID value as a String (Java-friendly).
     */
    fun getContextIdValue(): String? = contextId?.value

    // ========================================================================
    // Wither methods for Java-friendly builder pattern
    // ========================================================================

    fun withContextId(contextId: ContextId): PropositionQuery = copy(contextId = contextId)

    fun withContextIdValue(contextIdValue: String): PropositionQuery = copy(contextId = ContextId(contextIdValue))

    fun withEntityId(entityId: String): PropositionQuery = copy(entityId = entityId)

    fun withAnyEntity(vararg entityIds: String) = copy(anyEntityIds = entityIds.toList())

    fun withAnyEntityIds(entityIds: List<String>) = copy(anyEntityIds = entityIds)

    fun withAllEntities(vararg entityIds: String) = copy(allEntityIds = entityIds.toList())

    fun withAllEntityIds(entityIds: List<String>) = copy(allEntityIds = entityIds)

    fun withStatus(status: PropositionStatus): PropositionQuery = copy(status = status)

    fun withMinLevel(minLevel: Int): PropositionQuery = copy(minLevel = minLevel)

    fun withMaxLevel(maxLevel: Int): PropositionQuery = copy(maxLevel = maxLevel)

    fun withCreatedAfter(createdAfter: Instant): PropositionQuery = copy(createdAfter = createdAfter)

    fun withCreatedBefore(createdBefore: Instant): PropositionQuery = copy(createdBefore = createdBefore)

    fun withCreatedBetween(start: Instant, end: Instant): PropositionQuery =
        copy(createdAfter = start, createdBefore = end)

    fun withRevisedAfter(revisedAfter: Instant): PropositionQuery = copy(revisedAfter = revisedAfter)

    fun withRevisedBefore(revisedBefore: Instant): PropositionQuery = copy(revisedBefore = revisedBefore)

    fun withRevisedBetween(start: Instant, end: Instant): PropositionQuery =
        copy(revisedAfter = start, revisedBefore = end)

    /**
     * Filter to propositions created within the given duration from now.
     *
     * Example:
     * ```kotlin
     * query.createdSince(Duration.ofHours(1))  // last hour
     * query.createdSince(Duration.ofDays(7))   // last week
     * ```
     *
     * @param duration How far back to look
     * @return Query with createdAfter set to now minus duration
     */
    fun createdSince(duration: Duration): PropositionQuery =
        copy(createdAfter = Instant.now().minus(duration))

    /**
     * Filter to propositions revised within the given duration from now.
     *
     * @param duration How far back to look
     * @return Query with revisedAfter set to now minus duration
     */
    fun revisedSince(duration: Duration): PropositionQuery =
        copy(revisedAfter = Instant.now().minus(duration))

    fun withMinEffectiveConfidence(threshold: Double): PropositionQuery =
        copy(minEffectiveConfidence = threshold)

    fun withEffectiveConfidenceAsOf(asOf: Instant): PropositionQuery =
        copy(effectiveConfidenceAsOf = asOf)

    fun withDecayK(k: Double): PropositionQuery = copy(decayK = k)

    fun withMinReinforceCount(count: Int): PropositionQuery = copy(minReinforceCount = count)

    fun withOrderBy(orderBy: OrderBy): PropositionQuery = copy(orderBy = orderBy)

    fun orderedByEffectiveConfidence(): PropositionQuery =
        copy(orderBy = OrderBy.EFFECTIVE_CONFIDENCE_DESC)

    fun orderedByCreated(): PropositionQuery =
        copy(orderBy = OrderBy.CREATED_DESC)

    fun orderedByRevised(): PropositionQuery =
        copy(orderBy = OrderBy.REVISED_DESC)

    fun orderedByReinforceCount(): PropositionQuery =
        copy(orderBy = OrderBy.REINFORCE_COUNT_DESC)

    fun withLimit(limit: Int): PropositionQuery = copy(limit = limit)

    companion object {

        /**
         * Create a query scoped to a context.
         */
        @JvmStatic
        infix fun forContextId(contextId: ContextId): PropositionQuery =
            PropositionQuery(contextId = contextId)

        /**
         * Create a query scoped to a context (Java-friendly).
         */
        @JvmStatic
        infix fun againstContext(contextIdValue: String): PropositionQuery =
            PropositionQuery(contextId = ContextId(contextIdValue))

        /**
         * Create a query for propositions mentioning a specific entity.
         */
        @JvmStatic
        infix fun mentioningEntity(entityId: String): PropositionQuery =
            PropositionQuery(entityId = entityId)

        /**
         * Create a query for propositions mentioning all of the given entities (AND).
         */
        @JvmStatic
        fun mentioningAllEntities(vararg entityIds: String): PropositionQuery =
            PropositionQuery(allEntityIds = entityIds.toList())

        /**
         * Create a query for propositions mentioning any of the given entities (OR).
         */
        @JvmStatic
        fun mentioningAnyEntity(vararg entityIds: String): PropositionQuery =
            PropositionQuery(anyEntityIds = entityIds.toList())
    }
}
