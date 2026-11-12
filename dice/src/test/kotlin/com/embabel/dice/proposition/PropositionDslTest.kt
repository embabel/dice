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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

// The clauses these tests check:
// 1. L1: the block and the chain produce equal propositions, with every builder function
//    called once with a non-default value.
// 2. E1: an empty block produces a proposition with the draft defaults and four equal times.
// 3. E2: an out-of-range value fails when the block returns, with the constructor's message.
// 4. E3: a name in the caller's scope does not capture a builder call.
// 5. E4: the block entry point is synthetic, so Java does not see it.
class PropositionDslTest {

    private val contextId = ContextId("acme")
    private val text = "Alice lives in Paris"

    @Test
    fun `L1 the block and the chain produce equal propositions`() {
        val alice = EntityMention("Alice", "Person", "alice-1", MentionRole.SUBJECT, emptyMap())
        val paris = EntityMention("Paris", "City", "paris-1", MentionRole.OBJECT, emptyMap())
        val createdAt = Instant.parse("2026-01-01T00:00:00Z")
        val contentAt = Instant.parse("2026-01-02T00:00:00Z")
        val metadataAt = Instant.parse("2026-01-03T00:00:00Z")
        val accessedAt = Instant.parse("2026-01-04T00:00:00Z")
        val temporal = TemporalMetadata(createdAt, createdAt, null, null, emptyList(), emptyList())
        val provenance = listOf(ProvenanceEntry(UriLocator("https://example.com/doc")))

        val fromChain = Proposition.draft("acme", text, 0.9)
            .withId("p-1")
            .withMentions(listOf(alice, paris))
            .withDecay(0.2)
            .withImportance(0.7)
            .withReasoning("stated directly")
            .withGrounding(listOf("chunk-1"))
            .withCreated(createdAt)
            .withContentRevised(contentAt)
            .withMetadataRevised(metadataAt)
            .withLastAccessed(accessedAt)
            .withPinned(true)
            .withStatus(PropositionStatus.SUPERSEDED)
            .withLevel(1)
            .withSourceIds(listOf("p-0"))
            .withReinforceCount(2)
            .withMetadata(mapOf("source" to "wiki"))
            .withUri("doc:1")
            .withTemporal(temporal)
            .withProvenance(provenance)
            .toProposition()

        val fromVarargs = Proposition(contextId, text, 0.9) {
            id("p-1")
            mentions(alice, paris)
            decay(0.2)
            importance(0.7)
            reasoning("stated directly")
            grounding("chunk-1")
            created(createdAt)
            contentRevised(contentAt)
            metadataRevised(metadataAt)
            lastAccessed(accessedAt)
            pinned(true)
            status(PropositionStatus.SUPERSEDED)
            level(1)
            sourceIds("p-0")
            reinforceCount(2)
            metadata(mapOf("source" to "wiki"))
            uri("doc:1")
            temporal(temporal)
            provenance(provenance)
        }

        val fromLists = Proposition(contextId, text, 0.9) {
            id("p-1")
            mentions(listOf(alice, paris))
            decay(0.2)
            importance(0.7)
            reasoning("stated directly")
            grounding(listOf("chunk-1"))
            created(createdAt)
            contentRevised(contentAt)
            metadataRevised(metadataAt)
            lastAccessed(accessedAt)
            pinned(true)
            status(PropositionStatus.SUPERSEDED)
            level(1)
            sourceIds(listOf("p-0"))
            reinforceCount(2)
            metadata(mapOf("source" to "wiki"))
            uri("doc:1")
            temporal(temporal)
            provenance(provenance)
        }

        assertEquals(fromChain, fromVarargs)
        assertEquals(fromChain, fromLists)
    }

    @Test
    fun `E1 an empty block produces the draft defaults`() {
        val fact = Proposition(contextId, text, 0.9) { }

        assertTrue(fact.mentions.isEmpty())
        assertEquals(0.0, fact.decay)
        assertEquals(0.5, fact.importance)
        assertEquals(PropositionStatus.ACTIVE, fact.status)
        assertEquals(fact.created, fact.contentRevised)
        assertEquals(fact.created, fact.metadataRevised)
        assertEquals(fact.created, fact.lastAccessed)
    }

    @Test
    fun `E2 an out of range value fails when the block returns`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Proposition(contextId, text, 0.9) { decay(1.5) }
        }

        assertEquals("Decay must be between 0.0 and 1.0", error.message)
    }

    @Test
    fun `E3 a name in the caller scope does not capture a builder call`() {
        val decay = 0.4
        val uri = "doc:1"

        val fact = Proposition(contextId, text, 0.9) {
            decay(decay)
            uri(uri)
        }

        assertEquals(0.4, fact.decay)
        assertEquals("doc:1", fact.uri)
    }

    @Test
    fun `E4 the block entry point is synthetic so Java does not see it`() {
        val invokes = Proposition.Companion::class.java.declaredMethods
            .filter { it.name.startsWith("invoke") }

        assertFalse(invokes.isEmpty())
        assertTrue(invokes.all { it.isSynthetic })
    }
}
