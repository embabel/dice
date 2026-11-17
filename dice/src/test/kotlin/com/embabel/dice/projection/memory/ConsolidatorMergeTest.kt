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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

// Contract for the merge branch of DefaultMemoryConsolidator.consolidate.
// R1: the merge branch runs when similarity is at least 0.7 and at most 0.9.
//     "Alice works at Acme" against "Alice works at Acme Corp" scores 0.86.
// E1: the session proposition yields one merge and no promotion, reinforcement or discard.
// E2: the merged proposition carries the grounding, provenance entries and source ids of both.
// E3: the merged proposition has a new id, the average confidence and the stored text.
// L1: the merged proposition's four timestamps are one instant, and that instant is after
//     the stored proposition's timestamps.

/**
 * States what the memory consolidator's merge returns. The two propositions are similar enough to merge and too different to reinforce.
 */
class ConsolidatorMergeTest {

    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private val pA = ProvenanceEntry(UriLocator("https://example.com/a"), chunkId = "chunk-a")
    private val pB = ProvenanceEntry(UriLocator("https://example.com/b"), chunkId = "chunk-b")

    private val stored = proposition(
        text = "Alice works at Acme",
        confidence = 0.6,
        grounding = listOf("g1"),
        provenance = listOf(pA),
        sourceIds = listOf("s1"),
    )

    private val session = proposition(
        text = "Alice works at Acme Corp",
        confidence = 0.3,
        grounding = listOf("g2"),
        provenance = listOf(pB),
        sourceIds = listOf("s2"),
    )

    private fun proposition(
        text: String,
        confidence: Double,
        grounding: List<String>,
        provenance: List<ProvenanceEntry>,
        sourceIds: List<String>,
    ) = Proposition(
        contextId = ContextId("test-context"),
        text = text,
        mentions = listOf(EntityMention("Alice", "Person", resolvedId = "alice")),
        confidence = confidence,
        grounding = grounding,
        created = old,
        contentRevised = old,
        metadataRevised = old,
        lastAccessed = old,
        provenanceEntries = provenance,
        sourceIds = sourceIds,
    )

    private fun merged() = DefaultMemoryConsolidator()
        .consolidate(listOf(session), listOf(stored))
        .merged
        .single()
        .result

    @Test
    fun `the session proposition merges and nothing else happens`() {
        val result = DefaultMemoryConsolidator().consolidate(listOf(session), listOf(stored))
        assertEquals(1, result.merged.size)
        assertEquals(listOf(stored, session), result.merged.single().sources)
        assertTrue(result.promoted.isEmpty(), "promoted ${result.promoted}")
        assertTrue(result.reinforced.isEmpty(), "reinforced ${result.reinforced}")
        assertTrue(result.discarded.isEmpty(), "discarded ${result.discarded}")
    }

    @Test
    fun `the merged proposition carries the provenance and source ids of both`() {
        val merged = merged()
        assertEquals(listOf(pA, pB), merged.provenanceEntries)
        assertEquals(listOf("s1", "s2"), merged.sourceIds)
        assertEquals(listOf("g1", "g2"), merged.grounding)
    }

    @Test
    fun `the merged proposition's four times are one instant`() {
        val merged = merged()
        assertEquals(merged.created, merged.contentRevised)
        assertEquals(merged.created, merged.metadataRevised)
        assertEquals(merged.created, merged.lastAccessed)
        assertTrue(merged.created.isAfter(old), "created ${merged.created}")
    }

    @Test
    fun `the merged proposition is new and takes the average confidence`() {
        val merged = merged()
        assertNotEquals(stored.id, merged.id)
        assertEquals(0.45, merged.confidence, 1e-9)
        assertEquals(stored.text, merged.text)
    }
}
