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
import com.embabel.dice.proposition.store.JsonFilePropositionRepository
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Verifies that a [Proposition] round-trips cleanly through JSON. The class exposes computed
 * read-only accessors (`contextIdValue`, `revised`, `lastTouched`) with no matching constructor
 * parameters, so the mapper must tolerate unknown properties on read — if it doesn't,
 * deserialization fails. This pins that the canonical knowledge unit survives a default
 * Kotlin mapper round-trip.
 */
class PropositionJacksonRoundTripTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `JSON file written by the Jackson 2 repository loads after the upgrade`(@TempDir tempDir: Path) {
        // Captured from the old repository's jacksonObjectMapper().findAndRegisterModules() writer.
        val savedFile = tempDir.resolve("propositions.json")
        requireNotNull(javaClass.getResourceAsStream("/compat/propositions-jackson2.json")).use {
            Files.copy(it, savedFile)
        }

        val restored = requireNotNull(JsonFilePropositionRepository(savedFile).findById("legacy-proposition"))
        assertEquals(ContextId("legacy-context"), restored.contextId)
        assertEquals("A saved fact survives the upgrade", restored.text)
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), restored.created)
        assertEquals(UriLocator("https://example.com/source"), restored.provenanceEntries.single().locator)
        assertEquals("chunk-1", restored.provenanceEntries.single().chunkId)
    }

    @Test
    fun `a Proposition round-trips through JSON preserving its core fields`() {
        val original = Proposition(
            contextId = ContextId("ctx-round-trip"),
            text = "The substrate stays canonical",
            mentions = emptyList(),
            confidence = 0.8,
            decay = 0.1,
        ).withMetadataValue("dice.trust.score", 0.9)

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, Proposition::class.java)

        assertEquals(original.id, restored.id)
        assertEquals(original.contextId, restored.contextId)
        assertEquals(original.text, restored.text)
        assertEquals(original.confidence, restored.confidence)
        assertEquals(original.decay, restored.decay)
        assertEquals(original.status, restored.status)
        assertEquals(original.contentRevised, restored.contentRevised)
        assertEquals(original.metadata["dice.trust.score"], restored.metadata["dice.trust.score"])
    }
}
