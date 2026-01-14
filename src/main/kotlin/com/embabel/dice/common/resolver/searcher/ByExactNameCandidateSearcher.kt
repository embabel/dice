package com.embabel.dice.common.resolver.searcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.CandidateSearcher
import com.embabel.dice.common.resolver.SearchResult
import org.slf4j.LoggerFactory

/**
 * Searches for an exact name match.
 *
 * Returns a confident match only if exactly 1 entity is found
 * with a name that matches exactly (case-insensitive).
 *
 * @param repository The repository for text search
 */
class ByExactNameCandidateSearcher(
    private val repository: NamedEntityDataRepository,
) : CandidateSearcher {

    private val logger = LoggerFactory.getLogger(ByExactNameCandidateSearcher::class.java)

    override fun search(
        suggested: SuggestedEntity,
        schema: DataDictionary,
    ): SearchResult {
        val exactQuery = "\"${suggested.name}\""

        return try {
            val labelFilter = EntityFilter.hasAnyLabel(suggested.labels.toSet())
            val results = repository.textSearch(
                TextSimilaritySearchRequest(
                    query = exactQuery,
                    similarityThreshold = 0.99,
                    topK = 5, // Get a few to check for uniqueness
                ),
                entityFilter = labelFilter,
            )

            // Filter to exact name matches
            val exactMatches = results.filter { result ->
                result.match.name.equals(suggested.name, ignoreCase = true)
            }

            // Only return confident if exactly 1 match
            if (exactMatches.size == 1) {
                val match = exactMatches.first().match
                logger.debug("EXACT_NAME: '{}' -> '{}'", suggested.name, match.name)
                SearchResult.confident(match)
            } else {
                if (exactMatches.size > 1) {
                    logger.debug(
                        "EXACT_NAME: '{}' has {} matches, returning as candidates",
                        suggested.name, exactMatches.size
                    )
                    SearchResult.candidates(exactMatches.map { it.match })
                } else {
                    SearchResult.empty()
                }
            }
        } catch (e: Exception) {
            logger.debug("Exact name search failed for '{}': {}", suggested.name, e.message)
            SearchResult.empty()
        }
    }

    companion object {
        @JvmStatic
        fun create(repository: NamedEntityDataRepository): ByExactNameCandidateSearcher =
            ByExactNameCandidateSearcher(repository)
    }
}
