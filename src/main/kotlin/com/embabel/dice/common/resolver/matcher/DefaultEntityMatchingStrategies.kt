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
     * Order matters: label compatibility is checked first (can veto), then various name matching strategies.
     */
    @JvmStatic
    fun create(): ChainedEntityMatchingStrategy = ChainedEntityMatchingStrategy.of(
        LabelCompatibilityStrategy(),
        ExactNameEntityMatchingStrategy(),
        NormalizedNameEntityMatchingStrategy(),
        PartialNameEntityMatchingStrategy(),
        FuzzyNameEntityMatchingStrategy(),
    )
}
