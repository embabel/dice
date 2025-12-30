package com.embabel.dice.text2graph

/**
 * Relationship between a source and target entity,
 * identified by ID
 */
interface RelationshipInstance {
    val sourceId: String
    val targetId: String

    /**
     * The type of the relationship, e.g. "FALLS_UNDER"
     */
    val type: String
    val description: String?

    companion object {

        operator fun invoke(
            sourceId: String,
            targetId: String,
            type: String,
            description: String?,
        ): RelationshipInstance {
            return RelationshipInstanceImpl(sourceId, targetId, type, description)
        }
    }
}

private data class RelationshipInstanceImpl(
    override val sourceId: String,
    override val targetId: String,
    override val type: String,
    override val description: String? = null
) : RelationshipInstance