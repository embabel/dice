package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.resolver.matcher.ChainedEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.ExactNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.FuzzyNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.LabelCompatibilityStrategy
import com.embabel.dice.common.resolver.matcher.NormalizedNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.PartialNameEntityMatchingStrategy

/**
 * Entity resolver that remembers entities it's been asked to resolve
 * and tries to reuse them.
 * Useful for deduplicating entities within a single session.
 *
 * Uses configurable [EntityMatchingStrategy] implementations for matching, which by default include:
 * - Label compatibility checking (including type hierarchy)
 * - Case-insensitive exact matching
 * - Name normalization (removing titles/suffixes)
 * - Partial name matching (e.g., "Holmes" matches "Sherlock Holmes")
 * - Levenshtein distance for fuzzy matching
 *
 * @param config Configuration for fuzzy matching thresholds (used by default strategies)
 * @param matchStrategies Strategies for evaluating if an entity matches. Strategies are
 *                        tried in order; first definitive result wins.
 */
class InMemoryEntityResolver(
    private val config: Config = Config(),
    private val matchStrategies: ChainedEntityMatchingStrategy = defaultStrategies(config),
) : EntityResolver {

    data class Config(
        /**
         * Maximum Levenshtein distance (as a ratio of the shorter name length)
         * to consider two names as matching. Default is 0.2 (20%).
         */
        val maxDistanceRatio: Double = 0.2,
        /**
         * Minimum name length to apply fuzzy matching.
         * Short names are more prone to false positives.
         */
        val minLengthForFuzzy: Int = 4,
        /**
         * Minimum part length for partial name matching.
         */
        val minPartLength: Int = 4,
    )

    private val resolvedEntities = mutableMapOf<String, NamedEntityData>()

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary,
    ): Resolutions<SuggestedEntityResolution> {
        val resolutions = suggestedEntities.suggestedEntities.map { suggested ->
            val existingMatch = findMatch(suggested, schema)
            if (existingMatch != null) {
                ExistingEntity(suggested, existingMatch)
            } else {
                val newEntity = suggested.suggestedEntity
                resolvedEntities[newEntity.id] = newEntity
                NewEntity(suggested)
            }
        }
        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = resolutions,
        )
    }

    /**
     * Find a matching existing entity for the suggested entity.
     */
    private fun findMatch(suggested: SuggestedEntity, schema: DataDictionary): NamedEntityData? {
        for ((_, existing) in resolvedEntities) {
            if (matchStrategies.matches(suggested, existing, schema)) {
                return existing
            }
        }
        return null
    }

    /**
     * Clear all resolved entities from memory.
     */
    fun clear() {
        resolvedEntities.clear()
    }

    /**
     * Get the number of resolved entities in memory.
     */
    fun size(): Int = resolvedEntities.size

    companion object {
        /**
         * Create default strategies using the provided config.
         */
        fun defaultStrategies(config: Config = Config()): ChainedEntityMatchingStrategy =
            ChainedEntityMatchingStrategy.of(
                LabelCompatibilityStrategy(),
                ExactNameEntityMatchingStrategy(),
                NormalizedNameEntityMatchingStrategy(),
                PartialNameEntityMatchingStrategy(minPartLength = config.minPartLength),
                FuzzyNameEntityMatchingStrategy(
                    maxDistanceRatio = config.maxDistanceRatio,
                    minLengthForFuzzy = config.minLengthForFuzzy,
                ),
            )
    }
}
