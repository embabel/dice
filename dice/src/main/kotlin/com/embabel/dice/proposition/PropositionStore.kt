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
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.common.DiceMetadataKeys
import java.time.Instant

/**
 * Returns true if this proposition clears the minimum trust threshold. Shared between the
 * composable query filter and the vector-search pre-filter so neither site can drift from the
 * other.
 *
 * The gate is deliberately fail-open: a null [threshold] disables it entirely; a proposition
 * with no cached score passes (unscored ≠ low-trust); a non-finite cached value (e.g. `NaN`)
 * also passes rather than being silently dropped.
 *
 * @param threshold minimum acceptable trust score, or `null` to skip the check
 * @return `true` if the proposition passes the trust gate
 */
internal fun Proposition.passesMinTrust(threshold: Double?): Boolean {
    if (threshold == null) return true
    val cached = (metadata[DiceMetadataKeys.TRUST_SCORE] as? Number)?.toDouble()
    if (cached == null || !cached.isFinite()) return true
    return cached >= threshold
}

/**
 * Returns true if [prop] passes every active filter in this query. Shared between the composable
 * query and the vector-search pre-filter so neither site can diverge from the other. A null field
 * means that filter is disabled. Ordering and limiting are not part of this predicate.
 *
 * @param prop the proposition to test
 * @param asOf the instant at which effective confidence is evaluated; pass one value for the whole
 *   query so filtering and ordering agree. Defaults to the query's `effectiveConfidenceAsOf`, or now.
 * @return `true` if the proposition satisfies every active filter
 */
internal fun PropositionQuery.matchesFilters(
    prop: Proposition,
    asOf: Instant = effectiveConfidenceAsOf ?: Instant.now(),
): Boolean {
    if (contextId != null && prop.contextId != contextId) return false
    if (entityId != null && prop.mentions.none { it.resolvedId == entityId }) return false
    val any = anyEntityIds
    val all = allEntityIds
    if (any != null || all != null) {
        val propEntityIds = prop.mentions.mapNotNull { it.resolvedId }.toSet()
        if (any != null && propEntityIds.none { it in any }) return false
        if (all != null && !all.all { it in propEntityIds }) return false
    }
    statuses?.let { if (it.isNotEmpty() && prop.status !in it) return false }
    if (minLevel != null && prop.level < minLevel) return false
    if (maxLevel != null && prop.level > maxLevel) return false
    if (createdAfter != null && prop.created < createdAfter) return false
    if (createdBefore != null && prop.created > createdBefore) return false
    if (revisedAfter != null && prop.lastTouched < revisedAfter) return false
    if (revisedBefore != null && prop.lastTouched > revisedBefore) return false
    if (accessedAfter != null && prop.lastAccessed < accessedAfter) return false
    if (accessedBefore != null && prop.lastAccessed > accessedBefore) return false
    minEffectiveConfidence?.let { threshold ->
        if (prop.effectiveConfidenceAt(asOf, decayK) < threshold) return false
    }
    if (minImportance != null && prop.importance < minImportance) return false
    if (minReinforceCount != null && prop.reinforceCount < minReinforceCount) return false
    return prop.passesMinTrust(minTrustScore)
}

/**
 * Base persistence port for propositions: CRUD, identity lookups, and the composable query.
 *
 * A consumer that only needs store-and-retrieve can depend on this contract alone, without
 * inheriting vector, graph, or temporal capabilities. Stores that back those richer capabilities
 * additionally implement the matching opt-in capability interfaces.
 *
 * Implementations may use different backends (in-memory, database, vector store).
 */
interface PropositionStore {

    /**
     * Save a proposition. If a proposition with the same ID exists, it will be replaced.
     */
    fun save(proposition: Proposition): Proposition

    /**
     * Save multiple propositions.
     */
    fun saveAll(propositions: Collection<Proposition>) {
        propositions.forEach { save(it) }
    }

    /**
     * Find a proposition by its ID.
     */
    fun findById(id: String): Proposition?

    /**
     * Find all propositions that mention a specific entity.
     */
    fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition>

    /**
     * Find all propositions with the given status.
     */
    fun findByStatus(status: PropositionStatus): List<Proposition>

    /**
     * Find all propositions grounded by a specific chunk.
     */
    fun findByGrounding(chunkId: String): List<Proposition>

    /**
     * Find propositions at or above the specified abstraction level.
     * Level 0 = raw observations, 1+ = abstractions.
     *
     * @param minLevel Minimum abstraction level (inclusive)
     * @return Propositions with level >= minLevel
     */
    fun findByMinLevel(minLevel: Int): List<Proposition>

    /**
     * Find propositions in the given context.
     *
     * DB-backed repositories should override this method (not [findByContextIdValue]) to push
     * the filter to the backend. The default forwards to [findByContextIdValue].
     */
    fun findByContextId(contextId: ContextId): List<Proposition> =
        findByContextIdValue(contextId.value)

    /**
     * Java-friendly variant of [findByContextId] that accepts a plain string.
     *
     * The default filters [findAll] directly — it does NOT delegate back to [findByContextId],
     * which previously caused a [StackOverflowError] when neither method was overridden and the
     * repository was wrapped in a `by delegate` decorator. Override [findByContextId] for
     * efficient backend-level filtering.
     */
    fun findByContextIdValue(contextIdValue: String): List<Proposition> =
        findAll().filter { it.contextId.value == contextIdValue }

    /**
     * Get all propositions.
     */
    fun findAll(): List<Proposition>

    /**
     * Delete a proposition by ID.
     * @return true if the proposition was deleted, false if it didn't exist
     */
    fun delete(id: String): Boolean

    /**
     * Get the total count of propositions.
     */
    fun count(): Int

    // ========================================================================
    // Composable query - consolidates filtering, ordering, limiting
    // ========================================================================

    /**
     * Query propositions using a [PropositionQuery] specification.
     *
     * The default filters in memory. Override for backend-level filtering.
     *
     * @param query The query specification
     * @return Matching propositions
     */
    fun query(query: PropositionQuery): List<Proposition> {
        // Delegates filtering to the shared per-proposition predicate so this query
        // and the vector-search pre-filter always agree. Ordering and limiting follow.
        // One evaluation instant for the whole query, so filtering and ordering agree.
        val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
        var resultList = findAll().filter { query.matchesFilters(it, asOf) }

        // Apply ordering
        resultList = when (query.orderBy) {
            PropositionQuery.OrderBy.NONE -> resultList
            PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC ->
                resultList.sortedByDescending { it.effectiveConfidenceAt(asOf, query.decayK) }

            PropositionQuery.OrderBy.CREATED_DESC ->
                resultList.sortedByDescending { it.created }

            PropositionQuery.OrderBy.REVISED_DESC ->
                resultList.sortedByDescending { it.lastTouched }

            PropositionQuery.OrderBy.LAST_ACCESSED_DESC ->
                resultList.sortedByDescending { it.lastAccessed }

            PropositionQuery.OrderBy.REINFORCE_COUNT_DESC ->
                resultList.sortedByDescending { it.reinforceCount }

            PropositionQuery.OrderBy.IMPORTANCE_DESC ->
                resultList.sortedByDescending { it.importance }
        }

        // Apply limit
        query.limit?.let { limit ->
            resultList = resultList.take(limit)
        }

        return resultList
    }
}
