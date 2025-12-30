package com.embabel.dice.proposition

import com.embabel.common.core.types.HasInfoString

/**
 * The role an entity mention plays in a proposition.
 */
enum class MentionRole {
    /** The subject of the statement (e.g., "Jim" in "Jim knows Neo4j") */
    SUBJECT,

    /** The object of the statement (e.g., "Neo4j" in "Jim knows Neo4j") */
    OBJECT,

    /** Other mention that doesn't fit subject/object pattern */
    OTHER
}

/**
 * A reference to an entity within a proposition.
 *
 * @property span The text as it appears in the proposition (e.g., "Jim")
 * @property type The entity type label from schema (e.g., "Person", "Technology")
 * @property resolvedId Entity ID if resolved, null if unresolved
 * @property role The role this entity plays in the proposition
 * @property hints Additional context for future resolution (e.g., aliases, titles)
 */
data class EntityMention(
    val span: String,
    val type: String,
    val resolvedId: String? = null,
    val role: MentionRole = MentionRole.OTHER,
    val hints: Map<String, Any> = emptyMap(),
) : HasInfoString {

    override fun infoString(verbose: Boolean?, indent: Int): String {
        val resolved = resolvedId?.let { "→$it" } ?: "?"
        return "$span:$type$resolved"
    }

    fun withResolvedId(id: String): EntityMention = copy(resolvedId = id)
}