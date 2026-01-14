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
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.agent.rag.service.TextSearch
import com.embabel.agent.rag.service.VectorSearch
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.util.loggerFor
import java.time.Instant

/**
 * Storage interface for propositions.
 * Implementations may use different backends (in-memory, database, vector store).
 *
 * Implements [VectorSearch] and [TextSearch] for compatibility with RAG operations,
 * but only supports [Proposition] as the retrievable type.
 */
interface PropositionRepository : CoreSearchOperations {

    override fun supportsType(type: String): Boolean {
        return type == Proposition::class.java.simpleName
    }

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
     * Find propositions similar to the given text using vector similarity.
     * @return Similar propositions ordered by similarity (most similar first)
     */
    fun findSimilar(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<Proposition> =
        findSimilarWithScores(textSimilaritySearchRequest).map { it.match }

    /**
     * Find propositions similar to the given text with similarity scores.
     * @return Pairs of (proposition, similarity) ordered by similarity (most similar first)
     */
    fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>>

    /**
     * Find propositions similar to the given text, filtered by a PropositionQuery.
     * Implementations should optimize this for their backend (e.g., filter in the database).
     *
     * Default implementation delegates to the composable query() method after vector search.
     * Override in implementations to push filtering to the database for better performance.
     *
     * @param textSimilaritySearchRequest The similarity search parameters
     * @param query The query to filter results
     * @return Similar propositions matching the query, ordered by similarity
     */
    fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> {
        // Default: get vector results then filter using query criteria
        val vectorResults = findSimilarWithScores(textSimilaritySearchRequest)
        val matchingIds = this.query(query).map { it.id }.toSet()
        return vectorResults.filter { it.match.id in matchingIds }
    }

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

    // ========================================================================
    // Temporal queries - default implementations work with any repository
    // ========================================================================

    /**
     * Find propositions created within a time range.
     *
     * @param start Start of range (inclusive)
     * @param end End of range (inclusive)
     * @return Propositions created within the range
     */
    fun findByCreatedBetween(start: Instant, end: Instant): List<Proposition> =
        findAll().filter { it.created in start..end }

    /**
     * Find propositions revised within a time range.
     *
     * @param start Start of range (inclusive)
     * @param end End of range (inclusive)
     * @return Propositions revised within the range
     */
    fun findByRevisedBetween(start: Instant, end: Instant): List<Proposition> =
        findAll().filter { it.revised in start..end }

    // ========================================================================
    // Effective confidence queries - apply decay for ranking
    // ========================================================================

    /**
     * Find all propositions ordered by effective confidence (highest first).
     * Applies time-based decay to confidence scores.
     *
     * @param k Decay rate multiplier (default 2.0)
     * @return Propositions ordered by decayed confidence
     */
    fun findAllOrderedByEffectiveConfidence(k: Double = 2.0): List<Proposition> =
        findAll().sortedByDescending { it.effectiveConfidence(k) }

    /**
     * Find propositions with effective confidence above a threshold.
     *
     * @param threshold Minimum effective confidence (after decay)
     * @param k Decay rate multiplier (default 2.0)
     * @return Propositions with effective confidence >= threshold, ordered by confidence
     */
    fun findByEffectiveConfidenceAbove(threshold: Double, k: Double = 2.0): List<Proposition> =
        findAll()
            .filter { it.effectiveConfidence(k) >= threshold }
            .sortedByDescending { it.effectiveConfidence(k) }

    /**
     * Find propositions from a time range, ordered by effective confidence as of a point in time.
     * Useful for temporal analysis: "What was most confidently true during Q1?"
     *
     * @param start Start of creation range
     * @param end End of creation range
     * @param asOf Calculate effective confidence as of this time (defaults to end of range)
     * @param k Decay rate multiplier (default 2.0)
     * @return Propositions from range, ordered by effective confidence at the given time
     */
    fun findByCreatedBetweenOrderedByEffectiveConfidence(
        start: Instant,
        end: Instant,
        asOf: Instant = end,
        k: Double = 2.0,
    ): List<Proposition> =
        findByCreatedBetween(start, end)
            .sortedByDescending { it.effectiveConfidenceAt(asOf, k) }

    /**
     * Find propositions associated with the given context ID.
     * TODO will eventually need more sophisticated querying
     */
    fun findByContextId(contextId: ContextId): List<Proposition> =
        findByContextIdValue(contextId.value)

    /**
     * Internal method for Java interop - finds by context ID string value.
     */
    fun findByContextIdValue(contextIdValue: String): List<Proposition> = findByContextId(ContextId(contextIdValue))

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
     * Query propositions using a composable query specification.
     *
     * Default implementation filters in memory. Implementations may override
     * for more efficient database-level filtering.
     *
     * @param query The query specification
     * @return Matching propositions
     */
    fun query(query: PropositionQuery): List<Proposition> {
        var results = findAll().asSequence()

        // Apply filters
        query.contextId?.let { ctx ->
            results = results.filter { it.contextId == ctx }
        }
        query.entityId?.let { eid ->
            results = results.filter { prop ->
                prop.mentions.any { it.resolvedId == eid }
            }
        }
        query.status?.let { s ->
            results = results.filter { it.status == s }
        }
        query.minLevel?.let { min ->
            results = results.filter { it.level >= min }
        }
        query.maxLevel?.let { max ->
            results = results.filter { it.level <= max }
        }
        query.createdAfter?.let { after ->
            results = results.filter { it.created >= after }
        }
        query.createdBefore?.let { before ->
            results = results.filter { it.created <= before }
        }
        query.revisedAfter?.let { after ->
            results = results.filter { it.revised >= after }
        }
        query.revisedBefore?.let { before ->
            results = results.filter { it.revised <= before }
        }
        query.minEffectiveConfidence?.let { threshold ->
            val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
            results = results.filter { it.effectiveConfidenceAt(asOf, query.decayK) >= threshold }
        }

        // Convert to list for sorting
        var resultList = results.toList()

        // Apply ordering
        val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
        resultList = when (query.orderBy) {
            PropositionQuery.OrderBy.NONE -> resultList
            PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC ->
                resultList.sortedByDescending { it.effectiveConfidenceAt(asOf, query.decayK) }

            PropositionQuery.OrderBy.CREATED_DESC ->
                resultList.sortedByDescending { it.created }

            PropositionQuery.OrderBy.REVISED_DESC ->
                resultList.sortedByDescending { it.revised }
        }

        // Apply limit
        query.limit?.let { limit ->
            resultList = resultList.take(limit)
        }

        return resultList
    }

    // VectorSearch implementation - only supports Proposition
    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (clazz != Proposition::class.java) {
            loggerFor<PropositionRepository>().warn(
                "PropositionRepository only supports Proposition, not {}",
                clazz.simpleName
            )
            return emptyList()
        }
        return findSimilarWithScores(request) as List<SimilarityResult<T>>
    }

    // TextSearch implementation - delegates to vector search (no separate full-text index)
    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> textSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (clazz != Proposition::class.java) {
            loggerFor<PropositionRepository>().warn(
                "PropositionRepository only supports Proposition, not {}",
                clazz.simpleName
            )
            return emptyList()
        }
        // Default implementation falls back to vector search
        // Implementations with full-text indexing can override this
        // Note: filter is ignored - PropositionRepository doesn't support metadata filtering
        return findSimilarWithScores(request) as List<SimilarityResult<T>>
    }

}
