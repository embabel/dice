package com.embabel.dice.common.resolver.searcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.CandidateSearcher
import com.embabel.dice.common.resolver.SearchResult
import org.slf4j.LoggerFactory

/**
 * Searches using vector/embedding similarity.
 *
 * Returns a confident match if exactly 1 result exceeds the auto-accept threshold.
 * Otherwise returns all candidates above the minimum threshold for potential LLM arbitration.
 *
 * @param repository The repository for vector search
 * @param autoAcceptThreshold Similarity above this returns confident match (if exactly 1)
 * @param candidateThreshold Minimum similarity to be considered a candidate
 * @param topK Maximum candidates to retrieve
 */
class VectorCandidateSearcher(
    private val repository: NamedEntityDataRepository,
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

            candidates.addAll(results.map { it.match })

            // Find results above auto-accept threshold
            val highConfidenceMatches = results.filter { it.score >= autoAcceptThreshold }

            // Confident only if exactly 1 high-confidence match
            if (highConfidenceMatches.size == 1) {
                val match = highConfidenceMatches.first()
                logger.debug(
                    "EMBEDDING: '{}' -> '{}' (score: {} >= {})",
                    suggested.name, match.match.name, match.score, autoAcceptThreshold
                )
                return SearchResult(confident = match.match, candidates = candidates)
            }
        } catch (e: Exception) {
            logger.debug("Vector search failed for '{}': {}", suggested.name, e.message)
        }

        return SearchResult.candidates(candidates)
    }

    companion object {
        @JvmStatic
        fun create(repository: NamedEntityDataRepository): VectorCandidateSearcher =
            VectorCandidateSearcher(repository)
    }
}
