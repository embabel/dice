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
import kotlin.math.min

/**
 * Searches for fuzzy name matches using Levenshtein distance.
 *
 * Returns a confident match only if exactly 1 candidate matches within the distance threshold.
 *
 * @param repository The repository for text search
 * @param maxDistanceRatio Maximum Levenshtein distance as a ratio of the shorter name length.
 *                         Default is 0.2 (20%), meaning 1 char difference allowed per 5 chars.
 * @param minLengthForFuzzy Minimum name length to apply fuzzy matching.
 * @param topK Maximum candidates to retrieve
 * @param similarityThreshold Minimum similarity score for text search
 */
class FuzzyNameCandidateSearcher(
    private val repository: NamedEntityDataRepository,
    private val maxDistanceRatio: Double = 0.2,
    private val minLengthForFuzzy: Int = 4,
    private val topK: Int = 10,
    private val similarityThreshold: Double = 0.5,
) : CandidateSearcher {

    private val logger = LoggerFactory.getLogger(FuzzyNameCandidateSearcher::class.java)

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

            // Find candidates with fuzzy name match
            val matches = results.filter { result ->
                isFuzzyMatch(suggested.name, result.match.name)
            }

            candidates.addAll(results.map { it.match })

            // Confident only if exactly 1 match
            if (matches.size == 1) {
                val match = matches.first().match
                logger.debug("FUZZY: '{}' -> '{}'", suggested.name, match.name)
                return SearchResult(confident = match, candidates = candidates)
            }
        } catch (e: Exception) {
            logger.debug("Fuzzy name search failed for '{}': {}", suggested.name, e.message)
        }

        return SearchResult.candidates(candidates)
    }

    private fun isFuzzyMatch(name1: String, name2: String): Boolean {
        val lower1 = name1.lowercase()
        val lower2 = name2.lowercase()

        val minLength = min(lower1.length, lower2.length)
        if (minLength < minLengthForFuzzy) {
            return false
        }

        val distance = levenshteinDistance(lower1, lower2)
        val maxAllowedDistance = (minLength * maxDistanceRatio).toInt()

        return distance <= maxAllowedDistance
    }

    companion object {
        fun levenshteinDistance(s1: String, s2: String): Int {
            val m = s1.length
            val n = s2.length

            if (m == 0) return n
            if (n == 0) return m

            val dp = Array(m + 1) { IntArray(n + 1) }

            for (i in 0..m) dp[i][0] = i
            for (j in 0..n) dp[0][j] = j

            for (i in 1..m) {
                for (j in 1..n) {
                    val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost
                    )
                }
            }

            return dp[m][n]
        }

        @JvmStatic
        fun create(repository: NamedEntityDataRepository): FuzzyNameCandidateSearcher =
            FuzzyNameCandidateSearcher(repository)
    }
}
