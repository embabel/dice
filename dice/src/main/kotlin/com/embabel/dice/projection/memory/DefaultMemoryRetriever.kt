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
package com.embabel.dice.projection.memory

import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.projection.memory.support.HeuristicKnowledgeTypeClassifier
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/**
 * Default implementation of MemoryRetriever backed by a PropositionRepository.
 *
 * @param store The proposition store to query
 * @param recencyWeight Weight for recency in relevance scoring (0.0-1.0)
 * @param confidenceWeight Weight for confidence in relevance scoring (0.0-1.0)
 * @param knowledgeTypeClassifier Strategy for classifying propositions into knowledge types
 */
class DefaultMemoryRetriever(
    private val store: PropositionRepository,
    private val recencyWeight: Double = 0.3,
    private val confidenceWeight: Double = 0.3,
    private val knowledgeTypeClassifier: KnowledgeTypeClassifier = HeuristicKnowledgeTypeClassifier,
) : MemoryRetriever {

    override fun recall(
        query: String,
        forEntity: RetrievableIdentifier,
        topK: Int,
    ): List<Proposition> {
        // Get candidates from similarity search
        val similarPropositions = store.findSimilar(
            TextSimilaritySearchRequest(
                query = query,
                similarityThreshold = 0.0,
                topK = topK * 2
            )
        )

        // Also get propositions for the entity
        val entityPropositions = store.findByEntity(forEntity)

        // Combine and deduplicate
        val candidates = (similarPropositions + entityPropositions)
            .distinctBy { it.id }

        // Score and rank
        return candidates
            .map { prop -> prop to scoreRelevance(prop, query) }
            .sortedByDescending { (_, score) -> score }
            .take(topK)
            .map { (prop, _) -> prop }
    }

    override fun recallAbout(
        entityId: RetrievableIdentifier,
    ): List<Proposition> {
        return store.findByEntity(entityId)
            .sortedByDescending { it.confidence }
    }

    override fun recallByType(
        knowledgeType: KnowledgeType,
        forEntity: RetrievableIdentifier,
        topK: Int,
    ): List<Proposition> {
        // Get all propositions for the entity and filter by inferred type
        val entityPropositions = store.findByEntity(forEntity)

        return entityPropositions
            .filter { knowledgeTypeClassifier.classify(it) == knowledgeType }
            .sortedByDescending { it.confidence }
            .take(topK)
    }

    override fun recallRecent(
        forEntity: RetrievableIdentifier,
        since: Instant,
        limit: Int,
    ): List<Proposition> {
        val entityPropositions = store.findByEntity(forEntity)

        return entityPropositions
            .filter { it.created.isAfter(since) }
            .sortedByDescending { it.created }
            .take(limit)
    }

    /**
     * Score relevance of a proposition to a query.
     * Combines similarity, recency, and confidence.
     */
    private fun scoreRelevance(
        proposition: Proposition,
        query: String,
    ): Double {
        // Text similarity (simple word overlap)
        val queryWords = query.lowercase().split(Regex("\\s+")).toSet()
        val propWords = proposition.text.lowercase().split(Regex("\\s+")).toSet()
        val overlapRatio = if (queryWords.isEmpty()) 0.0
        else queryWords.intersect(propWords).size.toDouble() / queryWords.size
        val similarityScore = overlapRatio

        // Recency score (exponential decay over time)
        val ageSeconds = Duration.between(proposition.created, Instant.now()).seconds
        val recencyScore = exp(-ageSeconds / 86400.0) // decay over 24 hours

        // Confidence score
        val confidenceScore = proposition.confidence

        // Weighted combination
        val textWeight = 1.0 - recencyWeight - confidenceWeight
        return (similarityScore * textWeight) +
                (recencyScore * recencyWeight) +
                (confidenceScore * confidenceWeight)
    }
}
