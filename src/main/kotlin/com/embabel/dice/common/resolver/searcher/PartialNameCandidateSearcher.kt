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
 * Searches for partial name matches (e.g., "Holmes" matches "Sherlock Holmes").
 *
 * Returns a confident match only if exactly 1 candidate has a partial name match.
 * The shorter name must be at least [minPartLength] characters to avoid false positives.
 *
 * Examples:
 * - "Holmes" matches "Sherlock Holmes"
 * - "Brahms" matches "Johannes Brahms"
 * - "Doe" does NOT match "John Doe" (too short with default minPartLength=4)
 *
 * @param repository The repository for text search
 * @param minPartLength Minimum length for partial match
 * @param topK Maximum candidates to retrieve
 * @param similarityThreshold Minimum similarity score for text search
 */
class PartialNameCandidateSearcher(
    private val repository: NamedEntityDataRepository,
    private val minPartLength: Int = 4,
    private val topK: Int = 10,
    private val similarityThreshold: Double = 0.5,
) : CandidateSearcher {

    private val logger = LoggerFactory.getLogger(PartialNameCandidateSearcher::class.java)

    override fun search(
        suggested: SuggestedEntity,
        schema: DataDictionary,
    ): SearchResult {
        val candidates = mutableListOf<NamedEntityData>()

        try {
            val labelFilter = EntityFilter.hasAnyLabel(suggested.labels.toSet())
            val results = repository.textSearch(
                TextSimilaritySearchRequest(
                    query = suggested.name,
                    similarityThreshold = similarityThreshold,
                    topK = topK,
                ),
                entityFilter = labelFilter,
            )

            // Find candidates with partial name match
            val matches = results.filter { result ->
                isPartialMatch(suggested.name, result.match.name)
            }

            candidates.addAll(results.map { it.match })

            // Confident only if exactly 1 match
            if (matches.size == 1) {
                val match = matches.first().match
                logger.debug("PARTIAL: '{}' -> '{}'", suggested.name, match.name)
                return SearchResult(confident = match, candidates = candidates)
            }
        } catch (e: Exception) {
            logger.debug("Partial name search failed for '{}': {}", suggested.name, e.message)
        }

        return SearchResult.candidates(candidates)
    }

    private fun isPartialMatch(name1: String, name2: String): Boolean {
        val normalized1 = NormalizedNameCandidateSearcher.normalizeName(name1)
        val normalized2 = NormalizedNameCandidateSearcher.normalizeName(name2)

        val parts1 = normalized1.lowercase().split(Regex("\\s+"))
        val parts2 = normalized2.lowercase().split(Regex("\\s+"))

        // Single word matching multi-word name
        if (parts1.size == 1 && parts2.size > 1) {
            val singleName = parts1[0]
            if (singleName.length >= minPartLength) {
                return parts2.any { it.equals(singleName, ignoreCase = true) && it.length >= minPartLength }
            }
        }
        if (parts2.size == 1 && parts1.size > 1) {
            val singleName = parts2[0]
            if (singleName.length >= minPartLength) {
                return parts1.any { it.equals(singleName, ignoreCase = true) && it.length >= minPartLength }
            }
        }

        return false
    }

    companion object {
        @JvmStatic
        fun create(repository: NamedEntityDataRepository): PartialNameCandidateSearcher =
            PartialNameCandidateSearcher(repository)
    }
}
