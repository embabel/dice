package com.embabel.dice.text2graph

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData

typealias EntityMerge = Merge<SuggestedEntityResolution, NamedEntityData>


/**
 * Determine how to handle existing entities.
 * The EntityResolver will have determined where we have an existing entity.
 * The role of the EntityDeterminer is to decide how to handle merges.
 */
interface EntityMergePolicy {

    /**
     * Determine final entities to write based on the suggested entities resolution.
     */
    fun determineEntities(
        suggestedEntitiesResolution: Resolutions<SuggestedEntityResolution>,
        schema: DataDictionary,
    ): Merges<SuggestedEntityResolution, NamedEntityData>
}