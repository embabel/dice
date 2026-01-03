package com.embabel.dice.proposition

import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.agent.rag.service.TextSearch
import com.embabel.agent.rag.service.VectorSearch
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.util.loggerFor
import com.embabel.dice.common.EntityRequest

/**
 * Storage interface for propositions.
 * Implementations may use different backends (in-memory, database, vector store).
 *
 * Implements [VectorSearch] and [TextSearch] for compatibility with RAG operations,
 * but only supports [Proposition] as the retrievable type.
 */
interface PropositionRepository : CoreSearchOperations {

    override fun supportedRetrievableTypes(): Set<Class<out Retrievable>> {
        return setOf(Proposition::class.java)
    }

    override fun <T : Retrievable> findById(
        id: String,
        clazz: Class<T>
    ): T? {
        return findById(id) as T?
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
    fun findByEntity(entityRequest: EntityRequest): List<Proposition>

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
     * Find all propositions with the given status.
     */
    fun findByStatus(status: PropositionStatus): List<Proposition>

    /**
     * Find all propositions grounded by a specific chunk.
     */
    fun findByGrounding(chunkId: String): List<Proposition>

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
        return findSimilarWithScores(request) as List<SimilarityResult<T>>
    }

}
