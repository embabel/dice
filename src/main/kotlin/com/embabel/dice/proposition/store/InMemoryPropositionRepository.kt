package com.embabel.dice.proposition.store

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of PropositionRepository with vector similarity search.
 * Thread-safe using ConcurrentHashMap.
 * Embeddings are computed and cached for cosine similarity search.
 */
class InMemoryPropositionRepository(
    private val embeddingService: EmbeddingService,
) : PropositionRepository {

    private val propositions = ConcurrentHashMap<String, Proposition>()
    private val embeddings = ConcurrentHashMap<String, FloatArray>()

    override fun save(proposition: Proposition) {
        propositions[proposition.id] = proposition
        embeddings[proposition.id] = embeddingService.embed(proposition.text)
    }

    override fun findById(id: String): Proposition? = propositions[id]

    override fun findByEntity(entityId: String): List<Proposition> =
        propositions.values.filter { proposition ->
            proposition.mentions.any { it.resolvedId == entityId }
        }

    override fun findSimilar(text: String, topK: Int): List<Proposition> =
        findSimilarWithScores(text, topK).map { it.first }

    override fun findSimilarWithScores(
        text: String,
        topK: Int,
        minSimilarity: Double,
    ): List<Pair<Proposition, Double>> {
        if (propositions.isEmpty()) return emptyList()

        val queryEmbedding = embeddingService.embed(text)

        return propositions.values
            .mapNotNull { prop ->
                val propEmbedding = embeddings[prop.id] ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryEmbedding, propEmbedding)
                if (similarity >= minSimilarity) prop to similarity else null
            }
            .sortedByDescending { (_, similarity) -> similarity }
            .take(topK)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Vectors must have the same dimension" }

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0.0) 0.0 else dotProduct / denominator
    }

    override fun findByStatus(status: PropositionStatus): List<Proposition> =
        propositions.values.filter { it.status == status }

    override fun findByGrounding(chunkId: String): List<Proposition> =
        propositions.values.filter { chunkId in it.grounding }

    override fun findAll(): List<Proposition> = propositions.values.toList()

    override fun delete(id: String): Boolean {
        embeddings.remove(id)
        return propositions.remove(id) != null
    }

    override fun count(): Int = propositions.size

    /**
     * Clear all propositions and cached embeddings. Useful for testing.
     */
    fun clear() {
        propositions.clear()
        embeddings.clear()
    }
}
