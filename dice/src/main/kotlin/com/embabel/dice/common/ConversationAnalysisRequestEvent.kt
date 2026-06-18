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
package com.embabel.dice.common

import com.embabel.agent.rag.model.NamedEntity
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import com.embabel.dice.incremental.ConversationSource
import com.embabel.dice.incremental.IncrementalSource

/**
 * Event published after a conversation exchange to trigger async proposition extraction.
 * Used by any application integrating the DICE memory pipeline.
 */
class ConversationAnalysisRequestEvent(
    source: Any,
    user: NamedEntity,
    @JvmField val conversation: Conversation,
) : SourceAnalysisRequestEvent(source, user) {

    override fun incrementalSource(): IncrementalSource<Message> =
        ConversationSource(conversation)
}
