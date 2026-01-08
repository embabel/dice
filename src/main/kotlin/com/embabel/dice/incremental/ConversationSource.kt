package com.embabel.dice.incremental

import com.embabel.chat.Conversation
import com.embabel.chat.Message
import kotlin.math.max
import kotlin.math.min

/**
 * Adapts a Conversation to the IncrementalSource interface for incremental analysis.
 */
class ConversationSource(
    private val conversation: Conversation
) : IncrementalSource<Message> {

    override val id: String
        get() = conversation.id

    override val size: Int
        get() = conversation.messages.size

    override fun getItems(start: Int, end: Int): List<Message> {
        val messages = conversation.messages
        val safeEnd = min(end, messages.size)
        val safeStart = max(0, min(start, safeEnd))
        return messages.subList(safeStart, safeEnd)
    }
}

/**
 * Formats conversation messages for proposition extraction.
 */
class MessageFormatter : IncrementalSourceFormatter<Message> {

    override fun format(items: List<Message>): String {
        return items.joinToString("\n\n") { message ->
            val sender = message.sender ?: message.role.name
            "$sender: ${message.content}"
        }
    }

    companion object {
        @JvmField
        val INSTANCE = MessageFormatter()
    }
}
