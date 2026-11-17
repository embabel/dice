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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// Reading JSON that the first draft format wrote, and reading back what the current format writes.
//
// Contract, read from the code:
// E1: a flat JSON object in the shape main wrote reads into a draft whose getters hold the same values.
// E2: a draft read from that JSON builds a proposition with the same values.
// E3: a draft written to JSON and read back has the same getter values as the draft it came from.
class PropositionDraftLegacyJsonTest {

    private val mapper = jacksonObjectMapper()

    // The shape main wrote: one flat key per value, the context id as a plain string.
    // Times are null because jacksonObjectMapper() has no JSR-310 module for Instant.
    private val legacyJson = """
        {
          "contextId": "acme",
          "text": "Alice lives in Paris",
          "confidence": 0.9,
          "id": "prop-legacy-1",
          "mentions": [],
          "decay": 0.1,
          "importance": 0.7,
          "reasoning": "Said in the intro",
          "grounding": ["chunk-1", "chunk-2"],
          "created": null,
          "contentRevised": null,
          "metadataRevised": null,
          "lastAccessed": null,
          "pinned": true,
          "status": "ACTIVE",
          "level": 1,
          "sourceIds": ["prop-0"],
          "reinforceCount": 2,
          "metadata": {"source": "legacy"},
          "uri": "urn:test:1",
          "temporal": null,
          "provenanceEntries": []
        }
    """.trimIndent()

    @Test
    fun `E1 a draft reads from the JSON main wrote and keeps every value`() {
        val draft: PropositionDraft = mapper.readValue(legacyJson)

        assertEquals("acme", draft.contextIdValue)
        assertEquals("Alice lives in Paris", draft.text)
        assertEquals(0.9, draft.confidence)
        assertEquals("prop-legacy-1", draft.id)
        assertEquals(emptyList<EntityMention>(), draft.mentions)
        assertEquals(0.1, draft.decay)
        assertEquals(0.7, draft.importance)
        assertEquals("Said in the intro", draft.reasoning)
        assertEquals(listOf("chunk-1", "chunk-2"), draft.grounding)
        assertNull(draft.created)
        assertNull(draft.contentRevised)
        assertNull(draft.metadataRevised)
        assertNull(draft.lastAccessed)
        assertEquals(true, draft.pinned)
        assertEquals(PropositionStatus.ACTIVE, draft.status)
        assertEquals(1, draft.level)
        assertEquals(listOf("prop-0"), draft.sourceIds)
        assertEquals(2, draft.reinforceCount)
        assertEquals(mapOf<String, Any>("source" to "legacy"), draft.metadata)
        assertEquals("urn:test:1", draft.uri)
        assertNull(draft.temporal)
        assertEquals(emptyList<Any>(), draft.provenanceEntries)
    }

    @Test
    fun `E2 a draft read from the legacy JSON builds a proposition with the same values`() {
        val draft: PropositionDraft = mapper.readValue(legacyJson)

        val proposition = draft.toProposition()

        assertEquals("prop-legacy-1", proposition.id)
        assertEquals("acme", proposition.contextId.value)
        assertEquals("Alice lives in Paris", proposition.text)
        assertEquals(0.9, proposition.confidence)
        assertEquals(0.1, proposition.decay)
        assertEquals(0.7, proposition.importance)
        assertEquals(listOf("chunk-1", "chunk-2"), proposition.grounding)
        assertEquals(true, proposition.pinned)
        assertEquals(2, proposition.reinforceCount)
        assertEquals(mapOf<String, Any>("source" to "legacy"), proposition.metadata)
    }

    @Test
    fun `E3 a draft written by this branch reads back with the same getter values`() {
        val original = Proposition.draft("acme", "Bob works in Rome", 0.8)
            .withId("prop-rt-1")
            .withDecay(0.2)
            .withImportance(0.6)
            .withReasoning("Mentioned twice")
            .withExactGrounding(listOf("chunk-a", "chunk-b"))
            .withPinned(true)
            .withLevel(2)
            .withSourceIds(listOf("prop-x"))
            .withReinforceCount(3)
            .withMetadata(mapOf("source" to "round-trip"))
            .withUri("urn:test:rt")

        val json = mapper.writeValueAsString(original)
        val readBack: PropositionDraft = mapper.readValue(json)

        assertEquals(original.contextIdValue, readBack.contextIdValue)
        assertEquals(original.text, readBack.text)
        assertEquals(original.confidence, readBack.confidence)
        assertEquals(original.id, readBack.id)
        assertEquals(original.mentions, readBack.mentions)
        assertEquals(original.decay, readBack.decay)
        assertEquals(original.importance, readBack.importance)
        assertEquals(original.reasoning, readBack.reasoning)
        assertEquals(original.grounding, readBack.grounding)
        assertEquals(original.created, readBack.created)
        assertEquals(original.contentRevised, readBack.contentRevised)
        assertEquals(original.metadataRevised, readBack.metadataRevised)
        assertEquals(original.lastAccessed, readBack.lastAccessed)
        assertEquals(original.pinned, readBack.pinned)
        assertEquals(original.status, readBack.status)
        assertEquals(original.level, readBack.level)
        assertEquals(original.sourceIds, readBack.sourceIds)
        assertEquals(original.reinforceCount, readBack.reinforceCount)
        assertEquals(original.metadata, readBack.metadata)
        assertEquals(original.uri, readBack.uri)
        assertEquals(original.temporal, readBack.temporal)
        assertEquals(original.provenanceEntries, readBack.provenanceEntries)
    }
}
