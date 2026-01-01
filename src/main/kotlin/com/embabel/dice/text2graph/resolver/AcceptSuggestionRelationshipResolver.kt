package com.embabel.dice.text2graph.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.text2graph.SuggestedRelationships
import com.embabel.dice.text2graph.*

/**
 * Resolves all relationships unchanged
 */
object AcceptSuggestionRelationshipResolver : RelationshipResolver {

    override fun resolveRelationships(
        entityResolution: Resolutions<com.embabel.dice.common.SuggestedEntityResolution>,
        suggestedRelationships: SuggestedRelationships,
        schema: DataDictionary,
    ): Resolutions<SuggestedRelationshipResolution> {
        return Resolutions(
            chunkIds = entityResolution.chunkIds,
            resolutions = suggestedRelationships.suggestedRelationships.map {
                NewRelationship(it)
            },
        )
    }

}