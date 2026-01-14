package com.embabel.dice.common.resolver.matcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.EntityMatchingStrategy
import com.embabel.dice.common.resolver.MatchResult

/**
 * A match strategy that chains multiple strategies together.
 * Evaluates strategies in order, returning the first definitive result.
 *
 * - If any strategy returns [com.embabel.dice.common.resolver.MatchResult.Match], the entities match
 * - If any strategy returns [com.embabel.dice.common.resolver.MatchResult.NoMatch], the entities do not match
 * - If a strategy returns [com.embabel.dice.common.resolver.MatchResult.Inconclusive], the next strategy is tried
 * - If all strategies return Inconclusive, the default is no match
 *
 * Java usage:
 * ```java
 * ChainingMatchStrategy strategy = ChainingMatchStrategy.of(
 *     new LabelCompatibilityStrategy(),
 *     new ExactNameMatchStrategy()
 * );
 * boolean matches = strategy.matches(suggested, candidate, schema);
 * ```
 */
class ChainedEntityMatchingStrategy(
    val strategies: List<EntityMatchingStrategy>,
) : EntityMatchingStrategy {

    companion object {
        /**
         * Create a chaining strategy from vararg strategies.
         */
        @JvmStatic
        fun of(vararg strategies: EntityMatchingStrategy): ChainedEntityMatchingStrategy =
            ChainedEntityMatchingStrategy(strategies.toList())
    }

    override fun evaluate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): MatchResult {
        for (strategy in strategies) {
            when (strategy.evaluate(suggested, candidate, schema)) {
                MatchResult.Match -> return MatchResult.Match
                MatchResult.NoMatch -> return MatchResult.NoMatch
                MatchResult.Inconclusive -> continue
            }
        }
        return MatchResult.Inconclusive
    }

    /**
     * Convenience method that returns true if the chain evaluates to Match.
     * Returns false for NoMatch or Inconclusive.
     */
    fun matches(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): Boolean = evaluate(suggested, candidate, schema) == MatchResult.Match

    /**
     * Find a strategy of the specified type within this chain.
     * Returns null if no strategy of that type is present.
     */
    inline fun <reified T : EntityMatchingStrategy> findStrategy(): T? =
        strategies.filterIsInstance<T>().firstOrNull()

    /**
     * Add a strategy to the beginning of this chain, returning a new chain.
     */
    fun with(strategy: EntityMatchingStrategy): ChainedEntityMatchingStrategy =
        ChainedEntityMatchingStrategy(listOf(strategy) + strategies)

    /**
     * Append another chain's strategies to this chain.
     */
    operator fun plus(other: ChainedEntityMatchingStrategy): ChainedEntityMatchingStrategy =
        ChainedEntityMatchingStrategy(strategies + other.strategies)
}