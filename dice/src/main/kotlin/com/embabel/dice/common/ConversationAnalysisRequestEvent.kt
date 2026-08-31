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
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef

/**
 * Event published after a conversation exchange to trigger async proposition extraction.
 * Used by any application integrating the DICE memory pipeline.
 *
 * The three-argument constructor is the one that has always existed and carries no
 * provenance. A publisher that has a typed source for the conversation — a thread in a
 * chat system, a transcript file — uses the longer constructor to say so.
 */
class ConversationAnalysisRequestEvent(
    source: Any,
    user: NamedEntity,
    @JvmField val conversation: Conversation,
) : SourceAnalysisRequestEvent(source, user) {

    private var eventSourceLocator: SourceLocator? = null

    private var eventSourceRevision: SourceRevisionRef? = null

    constructor(
        source: Any,
        user: NamedEntity,
        conversation: Conversation,
        sourceLocator: SourceLocator,
        sourceRevision: SourceRevisionRef? = null,
    ) : this(source, user, conversation) {
        eventSourceLocator = sourceLocator
        eventSourceRevision = sourceRevision
    }

    override fun incrementalSource(): IncrementalSource<Message> =
        ConversationSource(conversation)

    override fun sourceLocator(): SourceLocator? = eventSourceLocator

    override fun sourceRevision(): SourceRevisionRef? = eventSourceRevision
}
