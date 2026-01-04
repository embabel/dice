package com.embabel.dice.common.resolver.matcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.MatchResult
import com.embabel.dice.common.resolver.MatchStrategy

/**
 * Strategy that uses a custom predicate for matching.
 * Useful for one-off custom matching logic.
 */
class PredicateMatchStrategy(
    private val predicate: (SuggestedEntity, NamedEntityData, DataDictionary) -> MatchResult,
) : MatchStrategy {

    override fun evaluate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): MatchResult = predicate(suggested, candidate, schema)
}