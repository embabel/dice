package com.embabel.dice.projection.memory

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import java.time.Duration
import java.time.Instant

/**
 * Retrieves propositions with memory semantics.
 * Combines multiple retrieval strategies: similarity, entity overlap, recency.
 */
interface MemoryRetriever {

    /**
     * Recall propositions relevant to a query.
     * Combines: vector similarity + entity overlap + recency.
     *
     * @param query The search query
     * @param scope Memory scope for filtering
     * @param topK Maximum number of results
     * @return Relevant propositions ordered by relevance
     */
    fun recall(
        query: String,
        scope: MemoryScope,
        topK: Int = 10,
    ): List<Proposition>

    /**
     * Recall everything known about an entity.
     *
     * @param entityId The entity to retrieve information about
     * @param scope Memory scope for filtering
     * @return All propositions mentioning this entity
     */
    fun recallAbout(
        entityId: String,
        scope: MemoryScope,
    ): List<Proposition>

    /**
     * Recall propositions by memory type.
     *
     * @param memoryType The type of memory to retrieve
     * @param scope Memory scope for filtering
     * @param topK Maximum number of results
     * @return Propositions of the specified type
     */
    fun recallByType(
        memoryType: MemoryType,
        scope: MemoryScope,
        topK: Int = 20,
    ): List<Proposition>

    /**
     * Recall recent propositions.
     *
     * @param scope Memory scope for filtering
     * @param since Only include propositions after this time
     * @param limit Maximum number of results
     * @return Recent propositions ordered by time (most recent first)
     */
    fun recallRecent(
        scope: MemoryScope,
        since: Instant = Instant.now().minusSeconds(3600),
        limit: Int = 20,
    ): List<Proposition>
}

/**
 * Default implementation of MemoryRetriever backed by a PropositionStore.
 *
 * @param store The proposition store to query
 * @param recencyWeight Weight for recency in relevance scoring (0.0-1.0)
 * @param confidenceWeight Weight for confidence in relevance scoring (0.0-1.0)
 */
class DefaultMemoryRetriever(
    private val store: PropositionRepository,
    private val recencyWeight: Double = 0.3,
    private val confidenceWeight: Double = 0.3,
) : MemoryRetriever {

    override fun recall(
        query: String,
        scope: MemoryScope,
        topK: Int,
    ): List<Proposition> {
        // Get candidates from similarity search
        val similarPropositions = store.findSimilar(query, topK = topK * 2)

        // Also get propositions for the user
        val userPropositions = store.findByEntity(scope.userId)

        // Combine and deduplicate
        val candidates = (similarPropositions + userPropositions)
            .distinctBy { it.id }

        // Score and rank
        return candidates
            .map { prop -> prop to scoreRelevance(prop, query, scope) }
            .sortedByDescending { (_, score) -> score }
            .take(topK)
            .map { (prop, _) -> prop }
    }

    override fun recallAbout(
        entityId: String,
        scope: MemoryScope,
    ): List<Proposition> {
        return store.findByEntity(entityId)
            .sortedByDescending { it.confidence }
    }

    override fun recallByType(
        memoryType: MemoryType,
        scope: MemoryScope,
        topK: Int,
    ): List<Proposition> {
        // Get all propositions for the user and filter by inferred type
        val userPropositions = store.findByEntity(scope.userId)

        return userPropositions
            .filter { it.inferMemoryType() == memoryType }
            .sortedByDescending { it.confidence }
            .take(topK)
    }

    override fun recallRecent(
        scope: MemoryScope,
        since: Instant,
        limit: Int,
    ): List<Proposition> {
        val userPropositions = store.findByEntity(scope.userId)

        return userPropositions
            .filter { it.created.isAfter(since) }
            .sortedByDescending { it.created }
            .take(limit)
    }

    /**
     * Score relevance of a proposition to a query and scope.
     * Combines similarity, recency, and confidence.
     */
    private fun scoreRelevance(
        proposition: Proposition,
        query: String,
        scope: MemoryScope,
    ): Double {
        // Text similarity (simple word overlap)
        val queryWords = query.lowercase().split(Regex("\\s+")).toSet()
        val propWords = proposition.text.lowercase().split(Regex("\\s+")).toSet()
        val overlapRatio = if (queryWords.isEmpty()) 0.0
        else queryWords.intersect(propWords).size.toDouble() / queryWords.size
        val similarityScore = overlapRatio

        // Recency score (exponential decay over time)
        val ageSeconds = Duration.between(proposition.created, Instant.now()).seconds
        val recencyScore = kotlin.math.exp(-ageSeconds / 86400.0) // decay over 24 hours

        // Confidence score
        val confidenceScore = proposition.confidence

        // Weighted combination
        val textWeight = 1.0 - recencyWeight - confidenceWeight
        return (similarityScore * textWeight) +
                (recencyScore * recencyWeight) +
                (confidenceScore * confidenceWeight)
    }
}
