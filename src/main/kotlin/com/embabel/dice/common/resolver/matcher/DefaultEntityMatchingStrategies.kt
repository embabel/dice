package com.embabel.dice.common.resolver.matcher

/**
 * Factory for creating default entity matching strategies.
 *
 * Java usage:
 * ```java
 * ChainedEntityMatchingStrategy strategy = DefaultEntityMatchingStrategies.create();
 * ```
 */
object DefaultEntityMatchingStrategies {

    /**
     * Create the default chain of entity matching strategies.
     *
     * Note: Label compatibility and exact name matching are handled by the searcher chain
     * (EntityFilter and ByExactNameCandidateSearcher), so are not included here.
     */
    @JvmStatic
    fun create(): ChainedEntityMatchingStrategy = ChainedEntityMatchingStrategy.of(
        NormalizedNameEntityMatchingStrategy(),
        PartialNameEntityMatchingStrategy(),
        FuzzyNameEntityMatchingStrategy(),
    )
}
