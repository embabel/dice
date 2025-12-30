package com.embabel.dice.text2graph

import com.embabel.agent.core.DataDictionary


interface RelationshipMergePolicy {

    /**
     * Determine final relationships to write based on the suggested relationships resolution.
     */
    fun mergeRelationships(
        suggestedRelationshipsResolution: Resolutions<SuggestedRelationshipResolution>,
        schema: DataDictionary,
    ): Merges<SuggestedRelationshipResolution, RelationshipInstance>
}