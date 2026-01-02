package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
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

