package com.embabel.dice.text2graph

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.Resolution
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntityResolution

sealed interface SuggestedRelationshipResolution :
    Resolution<SuggestedRelationship, RelationshipInstance>

data class NewRelationship(
    override val suggested: SuggestedRelationship,
) : SuggestedRelationshipResolution {

    override val existing = null

    override val recommended: RelationshipInstance = suggested

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return "${javaClass.simpleName}(type=${suggested.type}, sourceId=${suggested.sourceId}, targetId=${suggested.targetId})"
    }
}

data class ExistingRelationship(
    override val suggested: SuggestedRelationship,
    override val existing: RelationshipInstance,
) : SuggestedRelationshipResolution {

    override val recommended: RelationshipInstance = existing

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return "${javaClass.simpleName}(type=${suggested.type}, sourceId=${suggested.sourceId}, targetId=${suggested.targetId})"
    }
}


interface RelationshipResolver {

    /**
     * Analyze relationships between entities based on the provided schema.
     */
    fun resolveRelationships(
        entityResolution: Resolutions<SuggestedEntityResolution>,
        suggestedRelationships: SuggestedRelationships,
        schema: DataDictionary,
    ): Resolutions<SuggestedRelationshipResolution>

}