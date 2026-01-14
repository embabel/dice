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
 * Searches for matches using normalized names (removing titles, suffixes, extra whitespace).
 *
 * Returns a confident match only if exactly 1 candidate matches after normalization.
 *
 * Examples:
 * - "Dr. Watson" matches "Watson"
 * - "John Smith Jr." matches "John Smith"
 * - "Prof. Einstein" matches "Einstein"
 *
 * @param repository The repository for text search
 * @param topK Maximum candidates to retrieve
 * @param similarityThreshold Minimum similarity score for text search
 */
class NormalizedNameCandidateSearcher(
    private val repository: NamedEntityDataRepository,
    private val topK: Int = 10,
    private val similarityThreshold: Double = 0.5,
) : CandidateSearcher {

    private val logger = LoggerFactory.getLogger(NormalizedNameCandidateSearcher::class.java)

    override fun search(
        suggested: SuggestedEntity,
        schema: DataDictionary,
    ): SearchResult {
        val normalizedSuggested = normalizeName(suggested.name)
        val candidates = mutableListOf<NamedEntityData>()

        try {
            val labelFilter = EntityFilter.hasAnyLabel(suggested.labels.toSet())
            val results = repository.textSearch(
                TextSimilaritySearchRequest(
                    query = normalizedSuggested,
                    similarityThreshold = similarityThreshold,
                    topK = topK,
                ),
                entityFilter = labelFilter,
            )

            // Find candidates whose normalized name matches
            val matches = results.filter { result ->
                val normalizedCandidate = normalizeName(result.match.name)
                normalizedCandidate.equals(normalizedSuggested, ignoreCase = true)
            }

            candidates.addAll(results.map { it.match })

            // Confident only if exactly 1 match
            if (matches.size == 1) {
                val match = matches.first().match
                logger.debug("NORMALIZED: '{}' -> '{}'", suggested.name, match.name)
                return SearchResult(confident = match, candidates = candidates)
            }
        } catch (e: Exception) {
            logger.debug("Normalized name search failed for '{}': {}", suggested.name, e.message)
        }

        return SearchResult.candidates(candidates)
    }

    companion object {
        fun normalizeName(name: String): String {
            return name
                .trim()
                // Remove common titles
                .replace(Regex("^(Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.?|Prof\\.?)\\s+", RegexOption.IGNORE_CASE), "")
                // Remove common suffixes
                .replace(Regex("\\s+(Jr\\.?|Sr\\.?|II|III|IV)$", RegexOption.IGNORE_CASE), "")
                // Normalize whitespace
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        @JvmStatic
        fun create(repository: NamedEntityDataRepository): NormalizedNameCandidateSearcher =
            NormalizedNameCandidateSearcher(repository)
    }
}
