package com.embabel.dice.proposition

/**
 * Common interface for anything that references entities via mentions.
 * Propositions, projected relationships, and other constructs can
 * implement this to provide consistent entity access.
 */
interface ReferencesEntities {

    /**
     * Entity mentions within this construct.
     * Typically, includes SUBJECT and OBJECT roles for relationship-like structures.
     */
    val mentions: List<EntityMention>

    /**
     * Whether all entity mentions have been resolved to known entities.
     */
    fun isFullyResolved(): Boolean = mentions.all { it.resolvedId != null }

    /**
     * Get all resolved entity IDs from mentions.
     */
    fun resolvedEntityIds(): List<String> = mentions.mapNotNull { it.resolvedId }

    /**
     * Find the subject mention (if any).
     */
    fun subjectMention(): EntityMention? = mentions.find { it.role == MentionRole.SUBJECT }

    /**
     * Find the object mention (if any).
     */
    fun objectMention(): EntityMention? = mentions.find { it.role == MentionRole.OBJECT }

    /**
     * Get the resolved subject entity ID (if resolved).
     */
    fun subjectId(): String? = subjectMention()?.resolvedId

    /**
     * Get the resolved object entity ID (if resolved).
     */
    fun objectId(): String? = objectMention()?.resolvedId
}