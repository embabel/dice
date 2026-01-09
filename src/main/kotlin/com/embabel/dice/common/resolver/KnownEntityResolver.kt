package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.dice.common.*
import org.slf4j.LoggerFactory

/**
 * Entity resolver that checks a list of known entities before delegating to another resolver.
 *
 * Useful for ensuring that specific entities (like the current user) are always matched
 * correctly without requiring a search.
 *
 * @param knownEntities Entities to check before searching
 * @param delegate The resolver to delegate to if no known entity matches
 * @param matchStrategies Strategies for matching suggested entities to known entities
 */
class KnownEntityResolver(
    private val knownEntities: List<NamedEntity>,
    private val delegate: EntityResolver,
    private val matchStrategies: List<MatchStrategy> = defaultMatchStrategies(),
) : EntityResolver {

    private val logger = LoggerFactory.getLogger(KnownEntityResolver::class.java)

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary
    ): Resolutions<SuggestedEntityResolution> {
        if (knownEntities.isEmpty()) {
            return delegate.resolve(suggestedEntities, schema)
        }

        val resolutions = suggestedEntities.suggestedEntities.map { suggested ->
            val knownMatch = findKnownMatch(suggested, schema)
            if (knownMatch != null) {
                logger.info("Matched '{}' to known entity '{}'", suggested.name, knownMatch.name)
                ExistingEntity(suggested, knownMatch.toNamedEntityData())
            } else {
                null // Will be resolved by delegate
            }
        }

        // If all matched, return early
        if (resolutions.none { it == null }) {
            @Suppress("UNCHECKED_CAST")
            return Resolutions(
                chunkIds = suggestedEntities.chunkIds,
                resolutions = resolutions as List<SuggestedEntityResolution>,
            )
        }

        // Delegate unmatched entities
        val unmatchedSuggested = suggestedEntities.suggestedEntities
            .filterIndexed { index, _ -> resolutions[index] == null }

        val delegateResult = delegate.resolve(
            SuggestedEntities(
                suggestedEntities = unmatchedSuggested,
                sourceText = suggestedEntities.sourceText,
            ),
            schema
        )

        // Merge results
        var delegateIndex = 0
        val mergedResolutions = resolutions.map { known ->
            known ?: delegateResult.resolutions[delegateIndex++]
        }

        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = mergedResolutions,
        )
    }

    private fun findKnownMatch(suggested: SuggestedEntity, schema: DataDictionary): NamedEntity? {
        for (known in knownEntities) {
            val knownData = known.toNamedEntityData()
            if (matchStrategies.evaluate(suggested, knownData, schema)) {
                return known
            }
        }
        return null
    }

    private fun NamedEntity.toNamedEntityData() =
        if (this is com.embabel.agent.rag.model.NamedEntityData) this
        else SimpleNamedEntityData(
            id = id,
            name = name,
            description = description,
            labels = labels(),
            properties = emptyMap(),
        )

    companion object {
        /**
         * Create a resolver that includes known entities from a context.
         */
        @JvmStatic
        fun withKnownEntities(
            knownEntities: List<KnownEntity>,
            delegate: EntityResolver
        ): EntityResolver {
            if (knownEntities.isEmpty()) {
                return delegate
            }
            return KnownEntityResolver(
                knownEntities = knownEntities.map { it.entity },
                delegate = delegate,
            )
        }
    }
}
