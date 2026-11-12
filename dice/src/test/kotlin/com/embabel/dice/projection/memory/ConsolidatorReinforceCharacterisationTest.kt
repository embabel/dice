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
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pins what the memory consolidator's reinforce path returns. The session and existing
 * propositions share text and resolved entity, so their similarity is 1.0 and they reinforce.
 */
class ConsolidatorReinforceCharacterisationTest {

    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private val pA = ProvenanceEntry(UriLocator("https://example.com/a"), chunkId = "chunk-a")
    private val pB = ProvenanceEntry(UriLocator("https://example.com/b"), chunkId = "chunk-b")

    private fun proposition(
        confidence: Double,
        decay: Double = 0.0,
        grounding: List<String> = emptyList(),
        provenance: List<ProvenanceEntry> = emptyList(),
        reinforceCount: Int = 0,
    ) = Proposition(
        contextId = ContextId("test-context"),
        text = "Alice works at Acme",
        mentions = listOf(EntityMention("Alice", "Person", resolvedId = "alice")),
        confidence = confidence,
        decay = decay,
        grounding = grounding,
        created = old,
        contentRevised = old,
        metadataRevised = old,
        lastAccessed = old,
        reinforceCount = reinforceCount,
        provenanceEntries = provenance,
    )

    private fun existing(confidence: Double = 0.6) = proposition(
        confidence = confidence,
        decay = 0.2,
        grounding = listOf("g1", "g2"),
        provenance = listOf(pA),
        reinforceCount = 2,
    )

    private fun session() = proposition(
        confidence = 0.3,
        grounding = listOf("g2", "g3"),
        provenance = listOf(pB),
    )

    private fun reinforcedFrom(
        existing: Proposition,
        consolidator: DefaultMemoryConsolidator = DefaultMemoryConsolidator(),
    ): Proposition = consolidator.consolidate(listOf(session()), listOf(existing)).reinforced.single()

    @Test
    fun `C-E1 the session proposition reinforces and nothing else happens`() {
        val result = DefaultMemoryConsolidator().consolidate(listOf(session()), listOf(existing()))
        assertEquals(1, result.reinforced.size)
        assertTrue(result.promoted.isEmpty(), "promoted ${result.promoted}")
        assertTrue(result.discarded.isEmpty(), "discarded ${result.discarded}")
        assertTrue(result.merged.isEmpty(), "merged ${result.merged}")
    }

    @Test
    fun `C-E2 confidence adds the fixed boost and ignores the session confidence`() {
        assertEquals(0.7, reinforcedFrom(existing()).confidence, 1e-9)
    }

    @Test
    fun `C-E3 confidence is capped at 1_0`() {
        assertEquals(1.0, reinforcedFrom(existing(confidence = 0.95)).confidence, 1e-9)
    }

    @Test
    fun `C-E4 a custom reinforcement boost is used`() {
        val r = reinforcedFrom(existing(), DefaultMemoryConsolidator(reinforcementBoost = 0.25))
        assertEquals(0.85, r.confidence, 1e-9)
    }

    @Test
    fun `C-E5 grounding is unioned without duplicates in order`() {
        assertEquals(listOf("g1", "g2", "g3"), reinforcedFrom(existing()).grounding)
    }

    @Test
    fun `C-E6 content revised is stamped now`() {
        val before = Instant.now()
        val r = reinforcedFrom(existing())
        val after = Instant.now()
        assertTrue(!r.contentRevised.isBefore(before) && !r.contentRevised.isAfter(after), "contentRevised ${r.contentRevised}")
    }

    @Test
    fun `C-I1 every other field is carried over from the existing proposition`() {
        val existing = existing()
        val r = reinforcedFrom(existing)
        assertEquals(
            existing.copy(confidence = r.confidence, grounding = r.grounding, contentRevised = r.contentRevised),
            r,
        )
    }
}
