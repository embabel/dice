package com.embabel.dice.common

import com.embabel.agent.rag.model.NamedEntity

/**
 * A known entity with additional context for this extraction.
 * Delegates to the wrapped [NamedEntity].
 *
 * @param entity The named entity
 * @param role The role this entity plays in the current analysis,
 *        e.g., "The user in the conversation", "A referenced entity".
 */
data class KnownEntity(
    val entity: NamedEntity,
    val role: String,
) : NamedEntity by entity {

    companion object {
        /**
         * Create a KnownEntity marking this as the current user.
         */
        @JvmStatic
        fun asCurrentUser(entity: NamedEntity): KnownEntity =
            KnownEntity(entity, role = "The user in the conversation")


        @JvmStatic
        fun of(entity: NamedEntity): RoleStep =
            RoleStep(entity)
    }

    /**
     * Builder step requiring the role.
     */
    class RoleStep(private val entity: NamedEntity) {
        /**
         * Specify the role for this known entity and build it.
         */
        fun withRole(role: String): KnownEntity =
            KnownEntity(entity, role)
    }
}
