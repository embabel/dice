package com.embabel.dice.text2graph

import com.embabel.agent.core.DataDictionary
import com.embabel.common.core.Sourced
import com.embabel.common.core.types.HasInfoString
import com.embabel.dice.common.Resolution

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

data class Resolutions<R : HasInfoString>(
    override val chunkIds: Set<String>,
    val resolutions: List<R>,
) : HasInfoString, Sourced {

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return "${javaClass.simpleName}(resolutions:\n\t${
            resolutions.joinToString("\n\t") { it.infoString(verbose) }
        })"

    }
}


interface RelationshipResolver {

    /**
     * Analyze relationships between entities based on the provided schema.
     */
    fun resolveRelationships(
        entityResolution: Resolutions<com.embabel.dice.common.SuggestedEntityResolution>,
        suggestedRelationships: SuggestedRelationships,
        schema: DataDictionary,
    ): Resolutions<SuggestedRelationshipResolution>

}