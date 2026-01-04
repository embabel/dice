package com.embabel.dice.common.resolver.matcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.MatchResult
import com.embabel.dice.common.resolver.MatchStrategy

/**
 * Matches partial names (e.g., "Holmes" matches "Sherlock Holmes").
 * Names are normalized before comparison to handle titles like "Mr." or "Dr.".
 * The shorter name must be at least [minPartLength] characters to avoid false positives.
 *
 * Examples:
 * - "Holmes" matches "Sherlock Holmes"
 * - "Savage" matches "Victor Savage"
 * - "Mr. Holmes" matches "Sherlock Holmes" (after normalization)
 * - "Doe" does NOT match "John Doe" (too short with default minPartLength=4)
 */
class PartialNameMatchStrategy(
    private val minPartLength: Int = 4,
) : MatchStrategy {

    override fun evaluate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): MatchResult {
        // Normalize names first to handle "Mr. Holmes" -> "Holmes"
        val normalized1 = NormalizedNameMatchStrategy.normalizeName(suggested.name)
        val normalized2 = NormalizedNameMatchStrategy.normalizeName(candidate.name)

        val parts1 = normalized1.lowercase().split(Regex("\\s+"))
        val parts2 = normalized2.lowercase().split(Regex("\\s+"))

        // Single word matching multi-word name
        if (parts1.size == 1 && parts2.size > 1) {
            val singleName = parts1[0]
            if (singleName.length >= minPartLength) {
                if (parts2.any { it.equals(singleName, ignoreCase = true) && it.length >= minPartLength }) {
                    return MatchResult.Match
                }
            }
        }
        if (parts2.size == 1 && parts1.size > 1) {
            val singleName = parts2[0]
            if (singleName.length >= minPartLength) {
                if (parts1.any { it.equals(singleName, ignoreCase = true) && it.length >= minPartLength }) {
                    return MatchResult.Match
                }
            }
        }

        return MatchResult.Inconclusive
    }
}