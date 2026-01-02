package com.embabel.dice.text2graph.support

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.Resolutions
import com.embabel.dice.text2graph.*

/**
 * Always adds new entities and ignores existing or vetoed entities.
 */
object AcceptRecommendedRelationshipMergePolicy : RelationshipMergePolicy {

    override fun mergeRelationships(
        suggestedRelationshipsResolution: Resolutions<SuggestedRelationshipResolution>,
        schema: DataDictionary,
    ): Merges<SuggestedRelationshipResolution, RelationshipInstance> {
        return Merges(
            merges = suggestedRelationshipsResolution.resolutions.map { suggestedRelationship ->
                Merge(suggestedRelationship, suggestedRelationship.recommended)
            }
        )
    }
}