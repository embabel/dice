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
package com.embabel.dice.proposition.store

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of PropositionRepository with vector similarity search.
 * Thread-safe using ConcurrentHashMap, but not intended for production use.
 * Embeddings are computed and cached for cosine similarity search.
 */
class InMemoryPropositionRepository(
    private val embeddingService: EmbeddingService,
) : PropositionRepository {

    private val propositions = ConcurrentHashMap<String, Proposition>()
    private val embeddings = ConcurrentHashMap<String, FloatArray>()

    override val luceneSyntaxNotes: String
        get() = "no lucene support"

    override fun save(proposition: Proposition): Proposition {
        propositions[proposition.id] = proposition
        embeddings[proposition.id] = embeddingService.embed(proposition.text)
        return proposition
    }

    override fun findById(id: String): Proposition? = propositions[id]

    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> =
        propositions.values.filter { proposition ->
            proposition.mentions.any { it.resolvedId == entityIdentifier.id }
        }

    override fun findSimilar(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<Proposition> =
        findSimilarWithScores(textSimilaritySearchRequest).map { it.match }

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>> {
        if (propositions.isEmpty()) return emptyList()

        val queryEmbedding = embeddingService.embed(textSimilaritySearchRequest.query)
        val minSimilarity = textSimilaritySearchRequest.similarityThreshold

        return propositions.values
            .mapNotNull { prop ->
                val propEmbedding = embeddings[prop.id] ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryEmbedding, propEmbedding)
                if (similarity >= minSimilarity) SimilarityResult(match = prop, score = similarity) else null
            }
            .sortedByDescending { it.score }
            .take(textSimilaritySearchRequest.topK)
    }

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> {
        if (propositions.isEmpty()) return emptyList()

        val queryEmbedding = embeddingService.embed(textSimilaritySearchRequest.query)
        val minSimilarity = textSimilaritySearchRequest.similarityThreshold

        // Pre-filter propositions based on query before computing similarities
        val candidates = propositions.values.filter { prop ->
            val propEntityIds by lazy { prop.mentions.mapNotNull { it.resolvedId }.toSet() }
            (query.contextId == null || prop.contextId == query.contextId) &&
                (query.status == null || prop.status == query.status) &&
                (query.minLevel == null || prop.level >= query.minLevel) &&
                (query.maxLevel == null || prop.level <= query.maxLevel) &&
                (query.entityId == null || prop.mentions.any { it.resolvedId == query.entityId }) &&
                (query.anyEntityIds == null || propEntityIds.any { it in query.anyEntityIds!! }) &&
                (query.allEntityIds == null || query.allEntityIds!!.all { it in propEntityIds }) &&
                (query.minReinforceCount == null || prop.reinforceCount >= query.minReinforceCount)
        }

        return candidates
            .mapNotNull { prop ->
                val propEmbedding = embeddings[prop.id] ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryEmbedding, propEmbedding)
                if (similarity >= minSimilarity) SimilarityResult(match = prop, score = similarity) else null
            }
            .sortedByDescending { it.score }
            .take(textSimilaritySearchRequest.topK)
    }

    override fun findClusters(
        similarityThreshold: ZeroToOne,
        topK: Int,
        query: PropositionQuery,
    ): List<Cluster<Proposition>> {
        val candidates = query(query)
        val candidateIds = candidates.map { it.id }.toSet()

        return candidates.mapNotNull { anchor ->
            val anchorEmbedding = embeddings[anchor.id] ?: return@mapNotNull null

            val similar = candidates
                .filter { it.id != anchor.id && anchor.id < it.id }
                .mapNotNull { other ->
                    val otherEmbedding = embeddings[other.id] ?: return@mapNotNull null
                    val score = cosineSimilarity(anchorEmbedding, otherEmbedding)
                    if (score >= similarityThreshold) SimilarityResult(match = other, score = score) else null
                }
                .sortedByDescending { it.score }
                .take(topK)

            if (similar.isNotEmpty()) Cluster(anchor = anchor, similar = similar) else null
        }.sortedByDescending { it.similar.size }
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

    override fun findByMinLevel(minLevel: Int): List<Proposition> =
        propositions.values.filter { it.level >= minLevel }

    override fun findByContextId(contextId: ContextId): List<Proposition> =
        propositions.values.filter { it.contextId == contextId }

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
