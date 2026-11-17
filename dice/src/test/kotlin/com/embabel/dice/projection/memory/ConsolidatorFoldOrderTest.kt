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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * States what the memory consolidator returns when several session propositions match one stored proposition.
 */
class ConsolidatorFoldOrderTest {

    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private val pA = ProvenanceEntry(UriLocator("https://example.com/a"), chunkId = "chunk-a")
    private val pB = ProvenanceEntry(UriLocator("https://example.com/b"), chunkId = "chunk-b")
    private val pC = ProvenanceEntry(UriLocator("https://example.com/c"), chunkId = "chunk-c")

    private fun proposition(
        confidence: Double,
        grounding: List<String>,
        provenance: List<ProvenanceEntry>,
        reinforceCount: Int = 0,
    ) = Proposition(
        contextId = ContextId("test-context"),
        text = "Alice works at Acme",
        mentions = listOf(EntityMention("Alice", "Person", resolvedId = "alice")),
        confidence = confidence,
        grounding = grounding,
        created = old,
        contentRevised = old,
        metadataRevised = old,
        lastAccessed = old,
        reinforceCount = reinforceCount,
        provenanceEntries = provenance,
    )

    private val stored = proposition(0.6, listOf("g1"), listOf(pA), reinforceCount = 2)
    private val a = proposition(0.3, listOf("g2"), listOf(pB))
    private val b = proposition(0.3, listOf("g3"), listOf(pC))
    private val r = proposition(0.3, listOf("g1"), listOf(pA))

    private fun reinforced(session: List<Proposition>) =
        DefaultMemoryConsolidator().consolidate(session, listOf(stored)).reinforced

    @Test
    fun `two session propositions fold into one reinforced entry`() {
        val result = reinforced(listOf(a, b))
        assertEquals(1, result.size, "reinforced $result")
        val entry = result.single()
        assertEquals(listOf("g1", "g2", "g3"), entry.grounding)
        assertEquals(listOf(pA, pB, pC), entry.provenanceEntries)
        assertEquals(4, entry.reinforceCount)
        assertEquals(0.8, entry.confidence, 1e-9)
    }

    @Test
    fun `a replay after a new proposition keeps the reinforcement`() {
        val result = reinforced(listOf(a, r))
        assertEquals(1, result.size, "reinforced $result")
        assertEquals(listOf("g1", "g2"), result.single().grounding)
        assertEquals(3, result.single().reinforceCount)
    }

    @Test
    fun `a replay before a new proposition keeps the reinforcement`() {
        val result = reinforced(listOf(r, a))
        assertEquals(1, result.size, "reinforced $result")
        assertEquals(listOf("g1", "g2"), result.single().grounding)
        assertEquals(3, result.single().reinforceCount)
    }

    @Test
    fun `a replay alone returns the stored proposition`() {
        assertEquals(listOf(stored), reinforced(listOf(r)))
    }
}
