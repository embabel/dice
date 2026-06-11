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

import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.util.loggerFor
import com.embabel.dice.provenance.ProvenanceEntry

/**
 * The full proposition repository: combines the base persistence port with every opt-in
 * capability (vector similarity, abstraction-hierarchy traversal, temporal queries) and bridges
 * to the RAG search contract via [CoreSearchOperations].
 *
 * Each capability lives in its own fragment interface, so a consumer that only needs basic
 * store-and-retrieve can depend on [PropositionStore] directly. This interface is for callers
 * that need the complete surface.
 *
 * Only [Proposition] is supported as a retrievable type for vector and text search.
 */
interface PropositionRepository :
    PropositionStore,
    VectorSearchCapable,
    GraphTraversalCapable,
    TemporalQueryCapable,
    CoreSearchOperations {

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
     * Resolves the diamond: both [PropositionStore] and [VectorSearchCapable] declare [query].
     * This explicitly routes to the base store's implementation; concrete stores can still
     * override for backend-level filtering.
     */
    override fun query(query: PropositionQuery): List<Proposition> =
        super<PropositionStore>.query(query)

    /**
     * Query propositions, optionally guaranteeing loaded provenance. With `withProvenance = true`
     * every result has its [Proposition.provenanceEntries] populated (at extra read cost); the default
     * delegates to the lean [query]. See the provenance read contract on [PropositionStore.findAll].
     */
    fun query(query: PropositionQuery, withProvenance: Boolean): List<Proposition> = query(query)

    /**
     * Get all propositions, optionally guaranteeing loaded provenance. With `withProvenance = true`
     * every result has its [Proposition.provenanceEntries] populated (at extra read cost); the default
     * delegates to the lean [findAll] — fine for backends that always carry provenance (e.g. in-memory).
     */
    fun findAll(withProvenance: Boolean): List<Proposition> = findAll()

    // ========================================================================
    // Administrative operations - bulk re-embed and coarse deletion
    //
    // Default implementations are expressed over the read/write primitives
    // on the store fragment, so every backend (including in-memory) honours
    // them. Persistent backends should override for a single round-trip.
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
    // Provenance management - explicit control over a proposition's evidence
    //
    // [PropositionStore.save] is append-only for provenance: it never drops entries it didn't
    // load (the safe default for simple paths and bulk re-saves). Use these methods when you
    // need to read, add to, or authoritatively replace a proposition's provenance. Defaults are
    // expressed over the read/write primitives; persistent backends override where save differs.
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

    // RAG VectorSearch bridge — only Proposition is supported
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

    // RAG TextSearch bridge — falls back to vector search; no separate full-text index by default
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
        // Delegates to vector search by default; override in stores with a real full-text index.
        // Metadata filtering is not supported here.
        return findSimilarWithScores(request) as List<SimilarityResult<T>>
    }
}
