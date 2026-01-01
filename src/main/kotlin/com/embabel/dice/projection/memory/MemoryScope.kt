package com.embabel.dice.projection.memory

/**
 * Scopes memory queries to a specific context.
 *
 * @property userId The user this memory belongs to
 * @property conversationId Specific conversation, or null for cross-conversation
 * @property projectId Specific project context, or null for global
 * @property namespace Custom grouping for domain-specific scoping
 */
data class MemoryScope(
    val userId: String,
    val conversationId: String? = null,
    val projectId: String? = null,
    val namespace: String? = null,
) {

    companion object {

        /** Create a global scope for a user (no conversation/project filtering) */
        @JvmStatic
        fun global(userId: String) = MemoryScope(userId)

        /** Create a conversation-scoped memory */
        @JvmStatic
        fun conversation(userId: String, conversationId: String) =
            MemoryScope(userId, conversationId = conversationId)

        /** Create a project-scoped memory */
        @JvmStatic
        fun project(userId: String, projectId: String) =
            MemoryScope(userId, projectId = projectId)
    }
}