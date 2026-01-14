package com.embabel.dice.common.resolver.searcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.CandidateSearcher
import com.embabel.dice.common.resolver.MatchResult
import com.embabel.dice.common.resolver.SearchResult
import com.embabel.dice.common.resolver.defaultMatchStrategies
import com.embabel.dice.common.resolver.matcher.ChainedEntityMatchingStrategy
import org.slf4j.LoggerFactory

/**
 * Searches using vector/embedding similarity.
 *
 * Returns a confident match if the top result exceeds the auto-accept threshold
 * AND passes compatibility checks. Otherwise returns all candidates above
 * the minimum threshold for potential LLM arbitration.
 *
 * @param repository The repository for vector search
 * @param matchStrategies Strategies for compatibility checking
 * @param autoAcceptThreshold Similarity above this returns confident match
 * @param candidateThreshold Minimum similarity to be considered a candidate
 * @param topK Maximum candidates to retrieve
 */
class VectorCandidateSearcher(
    private val repository: NamedEntityDataRepository,
    private val matchStrategies: ChainedEntityMatchingStrategy = defaultMatchStrategies(),
    private val autoAcceptThreshold: Double = 0.95,
    private val candidateThreshold: Double = 0.7,
    private val topK: Int = 10,
) : CandidateSearcher {

    private val logger = LoggerFactory.getLogger(VectorCandidateSearcher::class.java)

    override fun search(
        suggested: SuggestedEntity,
        schema: DataDictionary,
    ): SearchResult {
        val candidates = mutableListOf<NamedEntityData>()

        try {
            val query = "${suggested.name} ${suggested.summary}"
            val labelFilter = EntityFilter.hasAnyLabel(suggested.labels.toSet())
            val results = repository.vectorSearch(
                TextSimilaritySearchRequest(
                    query = query,
                    similarityThreshold = candidateThreshold,
                    topK = topK,
                ),
                entityFilter = labelFilter,
            )

            for (result in results) {
                val candidate = result.match
                candidates.add(candidate)

                // Check if this is a high-confidence embedding match
                if (result.score >= autoAcceptThreshold && isCompatible(suggested, candidate, schema)) {
                    logger.debug(
                        "EMBEDDING: '{}' -> '{}' (score: {} >= {})",
                        suggested.name, candidate.name, result.score, autoAcceptThreshold
                    )
                    return SearchResult(confident = candidate, candidates = candidates)
                }
            }
        } catch (e: Exception) {
            logger.debug("Vector search failed for '{}': {}", suggested.name, e.message)
        }

        return SearchResult.candidates(candidates)
    }

    /**
     * Check if candidate is compatible with the suggested entity.
     * Uses the strategy chain - if any strategy returns NoMatch, the candidate is incompatible.
     */
    private fun isCompatible(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): Boolean {
        return matchStrategies.evaluate(suggested, candidate, schema) != MatchResult.NoMatch
    }

    companion object {
        @JvmStatic
        fun create(
            repository: NamedEntityDataRepository,
            matchStrategies: ChainedEntityMatchingStrategy = defaultMatchStrategies(),
        ): VectorCandidateSearcher = VectorCandidateSearcher(repository, matchStrategies)
    }
}
