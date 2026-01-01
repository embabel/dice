package com.embabel.dice.proposition

/**
 * Storage interface for propositions.
 * Implementations may use different backends (in-memory, database, vector store).
 */
interface PropositionRepository {

    /**
     * Save a proposition. If a proposition with the same ID exists, it will be replaced.
     */
    fun save(proposition: Proposition)

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
    fun findByEntity(entityId: String): List<Proposition>

    /**
     * Find propositions similar to the given text using vector similarity.
     * @param text The query text
     * @param topK Maximum number of results
     * @return Similar propositions ordered by similarity (most similar first)
     */
    // TODO replace with TextSimilaritySearchRequest when we've unpicked it from common
    fun findSimilar(text: String, topK: Int = 10): List<Proposition>

    /**
     * Find propositions similar to the given text with similarity scores.
     * @param text The query text
     * @param topK Maximum number of results
     * @param minSimilarity Minimum similarity threshold (0.0-1.0)
     * @return Pairs of (proposition, similarity) ordered by similarity (most similar first)
     */
    fun findSimilarWithScores(
        text: String,
        topK: Int = 10,
        minSimilarity: Double = 0.0,
    ): List<Pair<Proposition, Double>> = findSimilar(text, topK).map { it to 0.0 } // Default impl

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
}
