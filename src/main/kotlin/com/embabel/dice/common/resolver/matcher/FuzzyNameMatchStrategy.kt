package com.embabel.dice.common.resolver.matcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.MatchResult
import com.embabel.dice.common.resolver.MatchStrategy
import kotlin.math.min

/**
 * Fuzzy name matching using Levenshtein distance.
 *
 * @param maxDistanceRatio Maximum Levenshtein distance as a ratio of the shorter name length.
 *                         Default is 0.2 (20%), meaning 1 char difference allowed per 5 chars.
 * @param minLengthForFuzzy Minimum name length to apply fuzzy matching.
 *                          Short names are more prone to false positives.
 */
class FuzzyNameMatchStrategy(
    private val maxDistanceRatio: Double = 0.2,
    private val minLengthForFuzzy: Int = 4,
) : MatchStrategy {

    override fun evaluate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): MatchResult {
        val name1 = suggested.name.lowercase()
        val name2 = candidate.name.lowercase()

        val minLength = min(name1.length, name2.length)
        if (minLength < minLengthForFuzzy) {
            return MatchResult.Inconclusive
        }

        val distance = levenshteinDistance(name1, name2)
        val maxAllowedDistance = (minLength * maxDistanceRatio).toInt()

        return if (distance <= maxAllowedDistance) {
            MatchResult.Match
        } else {
            MatchResult.Inconclusive
        }
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
    }
}