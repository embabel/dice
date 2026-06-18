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
import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.agent.rag.service.TextSearch
import com.embabel.agent.rag.service.VectorSearch
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.loggerFor
import com.embabel.dice.provenance.ProvenanceEntry
import java.time.Instant

/**
 * Storage interface for propositions.
 * Implementations may use different backends (in-memory, database, vector store).
 *
 * Implements [VectorSearch] and [TextSearch] for compatibility with RAG operations,
 * but only supports [Proposition] as the retrievable type.
 */
interface PropositionRepository : CoreSearchOperations {

    /**
     * Which backend provides this repository. Implementations override to advertise their kind;
     * used to select/flip between in-memory and persistent backends.
     */
    val storeType: PropositionStoreType
        get() = PropositionStoreType.IN_MEMORY

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
     *
     * The default scans [findAll]; it must NOT delegate to [findByContextId], which itself defaults
     * to calling this method — a backend overriding neither would recurse forever. Backends override
     * [findByContextId] to push the filter into the store.
     */
    fun findByContextIdValue(contextIdValue: String): List<Proposition> =
        findAll().filter { it.contextIdValue == contextIdValue }

    /**
     * Get all propositions.
     *
     * **Provenance read contract.** Bulk reads ([findAll], [query], the vector searches) MAY return
     * propositions with empty [Proposition.provenanceEntries] for performance — a backend is free to
     * skip projecting provenance. Provenance is only *guaranteed* on [findById] and on the
     * `withProvenance = true` overloads. (The in-memory backend always carries provenance; the graph
     * backend uses a leaner projection by default — see its multi-view docs.)
     */
    fun findAll(): List<Proposition>

    /**
     * Get all propositions, optionally guaranteeing loaded provenance. With `withProvenance = true`
     * every result has its [Proposition.provenanceEntries] populated (at extra read cost); the default
     * delegates to the lean [findAll] — fine for backends that always carry provenance (e.g. in-memory).
     */
    fun findAll(withProvenance: Boolean): List<Proposition> = findAll()

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
    // Administrative operations - bulk re-embed and coarse deletion
    //
    // Default implementations are expressed over the read/write primitives
    // above, so every backend (including in-memory) honours them. Persistent
    // backends should override for a single round-trip to the database.
    // ========================================================================

    /**
     * Re-embed every stored proposition with the currently-configured embedding service.
     *
     * The default re-saves each proposition, which re-embeds it for any backend that embeds on
     * save; for backends that do not own embeddings (e.g. in-memory) it is a harmless rewrite.
     *
     * Caveat: a model swap that changes the embedding *dimension* also needs the backend's vector
     * index recreated at the new dimension — a backend/schema concern this does not perform. For
     * same-dimension re-embeds (the common case) it is sufficient on its own.
     *
     * @return number of propositions re-embedded
     */
    fun reembedAll(): Int {
        val all = findAll()
        all.forEach { save(it) }
        return all.size
    }

    /**
     * Delete every proposition.
     * @return number deleted
     */
    fun clearAll(): Int {
        val all = findAll()
        all.forEach { delete(it.id) }
        return all.size
    }

    /**
     * Delete all propositions in the given context.
     * @return number deleted
     */
    fun clearByContext(contextId: String): Int {
        val matches = findByContextIdValue(contextId)
        matches.forEach { delete(it.id) }
        return matches.size
    }

    /**
     * Delete all propositions whose context id starts with the given prefix.
     * @return number deleted
     */
    fun clearByContextPrefix(contextIdPrefix: String): Int {
        val matches = findAll().filter { it.contextIdValue.startsWith(contextIdPrefix) }
        matches.forEach { delete(it.id) }
        return matches.size
    }

    // ========================================================================
    // Source resolution - navigate the abstraction hierarchy
    // ========================================================================

    /**
     * Find the source propositions that a given proposition was abstracted from.
     * Resolves the proposition's [Proposition.sourceIds] to actual propositions.
     *
     * @param proposition The abstraction whose sources to find
     * @return Source propositions (partial list if some IDs are missing)
     */
    fun findSources(proposition: Proposition): List<Proposition> =
        proposition.sourceIds.mapNotNull { findById(it) }

    /**
     * Find propositions that were abstracted from the given proposition.
     * Searches for propositions that cite the given ID in their [Proposition.sourceIds].
     *
     * @param propositionId The ID of the source proposition
     * @return Propositions that list this ID as a source
     */
    fun findAbstractionsOf(propositionId: String): List<Proposition> =
        findAll().filter { propositionId in it.sourceIds }

    // ========================================================================
    // Provenance management - explicit control over a proposition's evidence
    //
    // [save] is append-only for provenance: it never drops entries it didn't
    // load (the safe default for simple paths and bulk re-saves). Use these
    // methods when you need to read, add to, or authoritatively replace a
    // proposition's provenance. Defaults are expressed over the read/write
    // primitives; persistent backends override where save semantics differ.
    // ========================================================================

    /**
     * The provenance entries of a proposition, or an empty list if it has none or does not exist.
     */
    fun provenanceOf(propositionId: String): List<ProvenanceEntry> =
        findById(propositionId)?.provenanceEntries ?: emptyList()

    /**
     * Append provenance to a proposition (deduplicated); never removes existing entries.
     *
     * @return the updated proposition, or null if no proposition with that id exists.
     */
    fun addProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? =
        findById(propositionId)?.let { save(it.withProvenanceEntries(entries)) }

    /**
     * Authoritatively set a proposition's provenance to exactly [entries], removing any not listed.
     *
     * @return the updated proposition, or null if no proposition with that id exists.
     */
    fun setProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? =
        findById(propositionId)?.let { save(it.withProvenance(entries)) }

    /**
     * Remove all provenance from a proposition.
     *
     * @return the updated proposition, or null if no proposition with that id exists.
     */
    fun clearProvenance(propositionId: String): Proposition? =
        setProvenance(propositionId, emptyList())

    // ========================================================================
    // Clustering - discover natural groupings of similar propositions
    // ========================================================================

    /**
     * Find clusters of similar propositions.
     *
     * Each cluster has an anchor proposition and a list of similar propositions
     * above the similarity threshold. Clusters are deduplicated so that each
     * pair appears only once (the proposition with the lower ID is the anchor).
     *
     * @param similarityThreshold Minimum cosine similarity to include in a cluster
     * @param topK Maximum number of similar items per cluster
     * @param query Optional query to pre-filter which propositions participate
     * @return Clusters ordered by size (largest first), excluding empty clusters
     */
    fun findClusters(
        similarityThreshold: ZeroToOne = 0.7,
        topK: Int = 10,
        query: PropositionQuery = PropositionQuery(),
    ): List<Cluster<Proposition>> {
        val candidates = query(query)
        val candidateIds = candidates.map { it.id }.toSet()

        return candidates.mapNotNull { anchor ->
            val similar = findSimilarWithScores(
                TextSimilaritySearchRequest(
                    query = anchor.text,
                    similarityThreshold = similarityThreshold,
                    topK = topK + 1, // +1 because the anchor itself may appear
                ),
            )
                .filter { it.match.id != anchor.id && it.match.id in candidateIds }
                .filter { anchor.id < it.match.id }
                .take(topK)

            if (similar.isNotEmpty()) Cluster(anchor = anchor, similar = similar) else null
        }.sortedByDescending { it.similar.size }
    }

    // ========================================================================
    // Composable query - consolidates filtering, ordering, limiting
    // ========================================================================

    /**
     * Query propositions, optionally guaranteeing loaded provenance. With `withProvenance = true`
     * every result has its [Proposition.provenanceEntries] populated (at extra read cost); the default
     * delegates to the lean [query]. See the provenance read contract on [findAll].
     */
    fun query(query: PropositionQuery, withProvenance: Boolean): List<Proposition> = query(query)

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
        query.anyEntityIds?.let { ids ->
            results = results.filter { prop ->
                val propEntityIds = prop.mentions.mapNotNull { it.resolvedId }.toSet()
                propEntityIds.any { it in ids }
            }
        }
        query.allEntityIds?.let { ids ->
            results = results.filter { prop ->
                val propEntityIds = prop.mentions.mapNotNull { it.resolvedId }.toSet()
                ids.all { it in propEntityIds }
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
        query.accessedAfter?.let { after ->
            results = results.filter { it.lastAccessed >= after }
        }
        query.accessedBefore?.let { before ->
            results = results.filter { it.lastAccessed <= before }
        }
        query.minEffectiveConfidence?.let { threshold ->
            val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
            results = results.filter { it.effectiveConfidenceAt(asOf, query.decayK) >= threshold }
        }
        query.minImportance?.let { min ->
            results = results.filter { it.importance >= min }
        }
        query.minReinforceCount?.let { min ->
            results = results.filter { it.reinforceCount >= min }
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
