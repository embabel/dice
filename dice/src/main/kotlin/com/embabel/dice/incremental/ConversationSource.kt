/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice.incremental

import com.embabel.chat.BaseMessage
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
            val name = (message as? BaseMessage)?.name
            val sender = if (name != null) "$name (${message.role.displayName})" else message.role.displayName
            "$sender: ${message.content}"
        }
    }

    companion object {
        @JvmField
        val INSTANCE = MessageFormatter()
    }
}
