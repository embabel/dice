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
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.proposition.extraction.ExtractionContentProfileRef
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.SourceRevisionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * The async publisher's half of the profile contract: the accessor defaults to null, an
 * existing subclass is unaffected, and the shipped conversation event carries a profile when
 * its longer constructor is used.
 */
class SourceAnalysisRequestEventProfileTest {

    private val profile = ExtractionContentProfileRef("house-style", "v1")

    @Test
    fun `a subclass written before profiles carries none`() {
        val legacy = LegacyEvent(this, mock(NamedEntity::class.java))

        assertNull(legacy.profile())
        assertNull(legacy.sourceLocator())
        assertNull(legacy.sourceRevision())
    }

    @Test
    fun `the shipped conversation event defaults to no profile`() {
        val event = ConversationAnalysisRequestEvent(
            source = this,
            user = mock(NamedEntity::class.java),
            conversation = mock(Conversation::class.java),
        )

        assertNull(event.profile())
    }

    @Test
    fun `the conversation event carries the exact profile it was given`() {
        val locator = ContentAddressedLocator("event-source")
        val revision = SourceRevisionRef(locator.key(), "r1")

        val event = ConversationAnalysisRequestEvent(
            source = this,
            user = mock(NamedEntity::class.java),
            conversation = mock(Conversation::class.java),
            sourceLocator = locator,
            sourceRevision = revision,
            profile = profile,
        )

        assertSame(locator, event.sourceLocator())
        assertSame(revision, event.sourceRevision())
        assertSame(profile, event.profile())
    }

    @Test
    fun `a publisher can name a profile for material it has no typed source for`() {
        val event = ConversationAnalysisRequestEvent(
            source = this,
            user = mock(NamedEntity::class.java),
            conversation = mock(Conversation::class.java),
            sourceLocator = null,
            profile = profile,
        )

        assertNull(event.sourceLocator())
        assertNull(event.sourceRevision())
        assertSame(profile, event.profile())
    }

    @Test
    fun `overriding one accessor leaves the others at their defaults`() {
        val profileOnly = object : SourceAnalysisRequestEvent(this, mock(NamedEntity::class.java)) {
            override fun incrementalSource(): IncrementalSource<Message> =
                throw UnsupportedOperationException("not needed by this compatibility test")

            override fun profile(): ExtractionContentProfileRef = profile
        }

        assertSame(profile, profileOnly.profile())
        assertNull(profileOnly.sourceLocator())
    }

    @Test
    fun `the base constructor descriptor is still source and user only`() {
        val constructor = SourceAnalysisRequestEvent::class.java.declaredConstructors.single()

        assertEquals(2, constructor.parameterCount)
        assertEquals(
            listOf(Any::class.java, NamedEntity::class.java),
            constructor.parameterTypes.toList(),
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
