package com.embabel.dice.text2graph.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.text2graph.*

/**
 * Always create a new entity.
 * Not useful in production
 */
object AlwaysCreateEntityResolver : EntityResolver {

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary,
    ): Resolutions<SuggestedEntityResolution> {
        val resolvedEntities = suggestedEntities.suggestedEntities.map {
            NewEntity(it)
        }
        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = resolvedEntities,
        )
    }

}

