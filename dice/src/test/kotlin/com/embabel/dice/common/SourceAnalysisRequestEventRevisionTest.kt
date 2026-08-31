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

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.SourceRevisionRef
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class SourceAnalysisRequestEventRevisionTest {

    @Test
    fun `legacy subclass and concrete constructor retain null provenance defaults`() {
        val user = mock(NamedEntity::class.java)

        val legacy = LegacyEvent(this, user)
        assertNull(legacy.sourceLocator())
        assertNull(legacy.sourceRevision())

        val conversation = ConversationAnalysisRequestEvent(
            source = this,
            user = user,
            conversation = mock(Conversation::class.java),
        )
        assertNull(conversation.sourceLocator())
        assertNull(conversation.sourceRevision())
    }

    @Test
    fun `provenance aware conversation event carries exact locator and revision`() {
        val locator = ContentAddressedLocator("source-content")
        val revision = SourceRevisionRef(locator.key(), "revision-3")
        val event = ConversationAnalysisRequestEvent(
            source = this,
            user = mock(NamedEntity::class.java),
            conversation = mock(Conversation::class.java),
            sourceLocator = locator,
            sourceRevision = revision,
        )

        assertSame(locator, event.sourceLocator())
        assertSame(revision, event.sourceRevision())
    }

    @Test
    fun `event provenance key mismatch is rejected through context creation`() {
        val locator = ContentAddressedLocator("source-content")
        val event = ConversationAnalysisRequestEvent(
            source = this,
            user = mock(NamedEntity::class.java),
            conversation = mock(Conversation::class.java),
            sourceLocator = locator,
            sourceRevision = SourceRevisionRef("different-key", "revision-3"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SourceAnalysisContext(
                schema = mock(DataDictionary::class.java),
                entityResolver = mock(EntityResolver::class.java),
                contextId = ContextId("event-revision-test"),
                sourceLocator = event.sourceLocator(),
                sourceRevision = event.sourceRevision(),
            )
        }
    }

    @Test
    fun `base constructor descriptor remains source and user only`() {
        val constructor = SourceAnalysisRequestEvent::class.java.declaredConstructors.single()

        assertEquals(2, constructor.parameterCount)
        assertArrayEquals(
            arrayOf(Any::class.java, NamedEntity::class.java),
            constructor.parameterTypes,
        )
    }

    private class LegacyEvent(
        source: Any,
        user: NamedEntity,
    ) : SourceAnalysisRequestEvent(source, user) {

        override fun incrementalSource(): IncrementalSource<Message> =
            throw UnsupportedOperationException("not needed by this compatibility test")
    }
}
