package com.embabel.dice.common.resolver.matcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.EntityMatchingStrategy
import com.embabel.dice.common.resolver.MatchResult

/**
 * Exact case-insensitive name match.
 */
class ExactNameEntityMatchingStrategy : EntityMatchingStrategy {

    override fun evaluate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): MatchResult {
        return if (suggested.name.equals(candidate.name, ignoreCase = true)) {
            MatchResult.Match
        } else {
            MatchResult.Inconclusive
        }
    }
}