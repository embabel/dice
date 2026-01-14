package com.embabel.dice.common.resolver.matcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.EntityMatchingStrategy
import com.embabel.dice.common.resolver.MatchResult

/**
 * Matches after normalizing names (removing titles, suffixes, extra whitespace).
 *
 * Examples:
 * - "Dr. Watson" matches "Watson"
 * - "John Smith Jr." matches "John Smith"
 * - "Prof. Einstein" matches "Einstein"
 */
class NormalizedNameEntityMatchingStrategy : EntityMatchingStrategy {

    override fun evaluate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): MatchResult {
        val normalized1 = normalizeName(suggested.name)
        val normalized2 = normalizeName(candidate.name)

        return if (normalized1.equals(normalized2, ignoreCase = true)) {
            MatchResult.Match
        } else {
            MatchResult.Inconclusive
        }
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
    }
}