package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.matcher.ChainedEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.ExactNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.FuzzyNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.LabelCompatibilityStrategy
import com.embabel.dice.common.resolver.matcher.NormalizedNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.PartialNameEntityMatchingStrategy

/**
 * Result of a match evaluation.
 */
enum class MatchResult {
    /** The entities definitely match */
    Match,

    /** The entities definitely do not match */
    NoMatch,

    /** Cannot determine - try next strategy */
    Inconclusive,
}

/**
 * Strategy for evaluating if a candidate entity matches a suggested entity.
 * Strategies are composable and can be combined in a chain.
 *
 * The chain evaluates strategies in order:
 * - If any strategy returns [MatchResult.Match], the entities match
 * - If any strategy returns [MatchResult.NoMatch], the entities do not match
 * - If a strategy returns [MatchResult.Inconclusive], the next strategy is tried
 * - If all strategies return Inconclusive, the default is no match
 */
interface EntityMatchingStrategy {
    /**
     * Evaluate whether the candidate matches the suggested entity.
     *
     * @param suggested The suggested entity from extraction
     * @param candidate The candidate entity from search or memory
     * @param schema The data dictionary for type hierarchy information
     * @return The match result
     */
    fun evaluate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): MatchResult
}

/**
 * Default match strategies that provide reasonable entity matching behavior.
 * Order matters: label compatibility is checked first (can veto), then various name matching strategies.
 */
fun defaultMatchStrategies(): ChainedEntityMatchingStrategy = ChainedEntityMatchingStrategy.of(
    LabelCompatibilityStrategy(),
    ExactNameEntityMatchingStrategy(),
    NormalizedNameEntityMatchingStrategy(),
    PartialNameEntityMatchingStrategy(),
    FuzzyNameEntityMatchingStrategy(),
)

