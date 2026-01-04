package com.embabel.dice.common.store

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.EntityRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * In-memory implementation of EntityRepository with vector similarity search.
 * Thread-safe using ConcurrentHashMap, but not intended for user production use
 * Embeddings are computed and cached for cosine similarity search.
 *
 * @param embeddingService Service for computing text embeddings
 */
class InMemoryEntityRepository(
    private val embeddingService: EmbeddingService,
) : EntityRepository {

    private val entities = ConcurrentHashMap<String, NamedEntityData>()
    private val embeddings = ConcurrentHashMap<String, FloatArray>()

    override val luceneSyntaxNotes: String
        get() = "In-memory store with basic text matching. No Lucene syntax support."

    // ===========================================
    // Write Operations
    // ===========================================

    override fun save(entity: NamedEntityData): NamedEntityData {
        entities[entity.id] = entity
        // Embed name + description for similarity search
        val textToEmbed = buildEmbeddingText(entity)
        embeddings[entity.id] = embeddingService.embed(textToEmbed)
        return entity
    }

    override fun delete(id: String): Boolean {
        embeddings.remove(id)
        return entities.remove(id) != null
    }

    // ===========================================
    // Read Operations
    // ===========================================

    override fun findEntityById(id: String): NamedEntityData? = entities[id]

    override fun findByLabel(label: String): List<NamedEntityData> =
        entities.values.filter { entity ->
            entity.labels().any { it.equals(label, ignoreCase = true) }
        }

    // ===========================================
    // CoreSearchOperations Implementation
    // ===========================================

    @Suppress("UNCHECKED_CAST")
    override fun <T : com.embabel.agent.rag.model.Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (!NamedEntityData::class.java.isAssignableFrom(clazz)) {
            return emptyList()
        }
        if (entities.isEmpty()) return emptyList()

        val queryEmbedding = embeddingService.embed(request.query)

        val results = entities.values
            .mapNotNull { entity ->
                val entityEmbedding = embeddings[entity.id] ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryEmbedding, entityEmbedding)
                if (similarity >= request.similarityThreshold) {
                    SimilarityResult(match = entity, score = similarity)
                } else null
            }
            .sortedByDescending { it.score }
            .take(request.topK)

        return results as List<SimilarityResult<T>>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : com.embabel.agent.rag.model.Retrievable> textSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (!NamedEntityData::class.java.isAssignableFrom(clazz)) {
            return emptyList()
        }

        val query = request.query.lowercase()
        val results = entities.values
            .filter { entity ->
                entity.name.lowercase().contains(query) ||
                        entity.description?.lowercase()?.contains(query) == true
            }
            .map { entity ->
                // Score based on match quality
                val score = if (entity.name.equals(request.query, ignoreCase = true)) 1.0
                else if (entity.name.lowercase().contains(query)) 0.8
                else 0.5
                SimilarityResult(match = entity, score = score)
            }
            .filter { it.score >= request.similarityThreshold }
            .sortedByDescending { it.score }
            .take(request.topK)

        return results as List<SimilarityResult<T>>
    }

    // ===========================================
    // Utility Methods
    // ===========================================

    /**
     * Clear all entities and cached embeddings. Useful for testing.
     */
    fun clear() {
        entities.clear()
        embeddings.clear()
    }

    private fun buildEmbeddingText(entity: NamedEntityData): String {
        val parts = mutableListOf(entity.name)
        entity.description?.let { parts.add(it) }
        // Include labels for better semantic matching
        parts.addAll(entity.labels())
        return parts.joinToString(" ")
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

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0.0 else dotProduct / denominator
    }
}
