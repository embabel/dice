package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.text2graph.Resolutions

/**
 * Entity resolver that delegates to multiple resolvers in order.
 *
 * For each suggested entity:
 * - Tries each resolver in order until one returns an [ExistingEntity]
 * - If all resolvers return [NewEntity], uses the first resolver's result
 *
 * This allows chaining different resolution strategies, such as:
 * - First checking an in-memory cache of recently seen entities
 * - Then checking a database of known entities
 * - Finally falling back to creating new entities
 */
class MultiEntityResolver(
    private val resolvers: List<EntityResolver>,
) : EntityResolver {

    init {
        require(resolvers.isNotEmpty()) { "At least one resolver is required" }
    }

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary
    ): Resolutions<SuggestedEntityResolution> {
        if (suggestedEntities.suggestedEntities.isEmpty()) {
            return Resolutions(
                chunkIds = suggestedEntities.chunkIds,
                resolutions = emptyList()
            )
        }

        // Track the best resolution for each entity (by index)
        val bestResolutions = arrayOfNulls<SuggestedEntityResolution>(suggestedEntities.suggestedEntities.size)
        // Track which entities still need resolution (haven't found ExistingEntity yet)
        val unresolvedIndices = suggestedEntities.suggestedEntities.indices.toMutableSet()

        for (resolver in resolvers) {
            if (unresolvedIndices.isEmpty()) break

            // Build a subset of entities that still need resolution
            val unresolvedEntities = unresolvedIndices.map { suggestedEntities.suggestedEntities[it] }
            val subset = SuggestedEntities(
                chunkIds = suggestedEntities.chunkIds,
                suggestedEntities = unresolvedEntities
            )

            val resolutions = resolver.resolve(subset, schema)

            // Map results back to original indices
            val unresolvedList = unresolvedIndices.toList()
            resolutions.resolutions.forEachIndexed { subsetIndex, resolution ->
                val originalIndex = unresolvedList[subsetIndex]

                when (resolution) {
                    is ExistingEntity -> {
                        bestResolutions[originalIndex] = resolution
                        unresolvedIndices.remove(originalIndex)
                    }
                    is NewEntity -> {
                        // Only use NewEntity if we don't have a resolution yet
                        if (bestResolutions[originalIndex] == null) {
                            bestResolutions[originalIndex] = resolution
                        }
                    }
                    else -> {
                        // Handle VetoedEntity or other types
                        if (bestResolutions[originalIndex] == null) {
                            bestResolutions[originalIndex] = resolution
                        }
                    }
                }
            }
        }

        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = bestResolutions.filterNotNull()
        )
    }
}