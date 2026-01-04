package com.embabel.dice.common.store

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.EntityIdentifier
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * In-memory implementation of EntityRepository with vector similarity search.
 * Thread-safe using ConcurrentHashMap, but not intended for user production use
 * Embeddings are computed and cached for cosine similarity search.
 *
 * @param embeddingService Service for computing text embeddings
 */
class InMemoryNamedEntityDataRepository(
    private val embeddingService: EmbeddingService,
) : NamedEntityDataRepository {

    private val entities = ConcurrentHashMap<String, NamedEntityData>()

    private val embeddings = ConcurrentHashMap<String, FloatArray>()

    override fun vectorSearch(
        request: TextSimilaritySearchRequest
    ): List<SimilarityResult<NamedEntityData>> {
        val queryEmbedding = embeddingService.embed(request.query)
        val results = embeddings.mapNotNull { (id, embedding) ->
            val entity = entities[id] ?: return@mapNotNull null
            val similarity = cosineSimilarity(queryEmbedding, embedding)
            SimilarityResult(
                match = entity,
                score = similarity
            )
        }
        return results
            .sortedByDescending { it.score }
            .take(request.topK)
    }

    override fun textSearch(request: TextSimilaritySearchRequest): List<SimilarityResult<NamedEntityData>> {
        return entities.values
            .filter { entity ->
                // Simple case-insensitive substring match for demo purposes
                entity.name.contains(request.query, ignoreCase = true) ||
                        entity.description.contains(request.query, ignoreCase = true)
            }
            .map { entity ->
                SimilarityResult(
                    match = entity,
                    score = 1.0 // Placeholder score for text matches
                )
            }
    }

    override val luceneSyntaxNotes = "basic"

    override fun save(entity: NamedEntityData): NamedEntityData {
        entities[entity.id] = entity
        // Embed name + description for similarity search
        val textToEmbed = buildEmbeddingText(entity)
        embeddings[entity.id] = embeddingService.embed(textToEmbed)
        return entity
    }

    override fun createRelationship(
        a: EntityIdentifier,
        b: EntityIdentifier,
        relationship: RelationshipData
    ) {
        TODO("Not yet implemented")
    }

    override fun delete(id: String): Boolean {
        embeddings.remove(id)
        return entities.remove(id) != null
    }


    override fun findById(id: String): NamedEntityData? = entities[id]

    override fun findByLabel(label: String): List<NamedEntityData> =
        entities.values.filter { entity ->
            entity.labels().any { it.equals(label, ignoreCase = true) }
        }

    /**
     * Clear all entities and cached embeddings. Useful for testing.
     */
    fun clear() {
        entities.clear()
        embeddings.clear()
    }

    private fun buildEmbeddingText(entity: NamedEntityData): String {
        val parts = mutableListOf(entity.name)
        entity.description.let { parts.add(it) }
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
