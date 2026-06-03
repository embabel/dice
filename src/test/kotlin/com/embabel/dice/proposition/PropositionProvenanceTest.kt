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
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class PropositionProvenanceTest {

    private val ctx = ContextId("test-context")

    @Test
    fun `new fields default to null and empty`() {
        val prop = Proposition(contextId = ctx, text = "Alice knows Bob", mentions = emptyList(), confidence = 0.9)
        assertNull(prop.temporal)
        assertTrue(prop.provenanceEntries.isEmpty())
    }

    @Test
    fun `withTemporal attaches metadata and bumps revised`() {
        val original = Proposition(contextId = ctx, text = "Alice prefers tea", mentions = emptyList(), confidence = 0.8)
        val tm = TemporalMetadata(
            observedAt = Instant.parse("2026-01-01T00:00:00Z"),
            validFrom = Instant.parse("2026-01-01T00:00:00Z"),
        )

        val updated = original.withTemporal(tm)

        assertNull(original.temporal)
        assertEquals(tm, updated.temporal)
        assertEquals(original.id, updated.id)
        assertTrue(updated.revised >= original.revised)
    }

    @Test
    fun `withProvenanceEntries appends and dedups`() {
        val e1 = ProvenanceEntry(locator = UriLocator("https://example.com/doc"), chunkId = "c1")
        val e2 = ProvenanceEntry(locator = ContentAddressedLocator("abc123"), startOffset = 0, endOffset = 10)

        val original = Proposition(
            contextId = ctx,
            text = "Test",
            mentions = emptyList(),
            confidence = 0.7,
            provenanceEntries = listOf(e1),
        )

        val updated = original.withProvenanceEntries(listOf(e1, e2))

        assertEquals(1, original.provenanceEntries.size)
        assertEquals(2, updated.provenanceEntries.size)
        assertTrue(updated.provenanceEntries.containsAll(listOf(e1, e2)))
    }

    @Test
    fun `grounding dedup ignores display label per SourceLocator identity contract`() {
        // Same underlying source, different presentation-only display labels.
        val labeled = ProvenanceEntry(locator = UriLocator("https://example.com/doc", display = "Doc A"))
        val relabeled = ProvenanceEntry(locator = UriLocator("https://example.com/doc", display = "Doc B"))

        // Locators (and so grounding entries) are equal: identity is key(), not display.
        assertEquals(labeled.locator, relabeled.locator)
        assertEquals(labeled, relabeled)

        val prop = Proposition(
            contextId = ctx,
            text = "Test",
            mentions = emptyList(),
            confidence = 0.7,
            provenanceEntries = listOf(labeled),
        )

        // Re-adding the same source with a different label must NOT create a duplicate.
        val updated = prop.withProvenanceEntries(listOf(relabeled))
        assertEquals(1, updated.provenanceEntries.size)
    }

    @Test
    fun `proposition roundtrips through copy preserving provenance and existing invariants`() {
        val tm = TemporalMetadata(
            observedAt = Instant.parse("2026-01-01T00:00:00Z"),
            validFrom = Instant.parse("2026-01-01T00:00:00Z"),
            validTo = Instant.parse("2026-06-01T00:00:00Z"),
            supersedes = listOf("old-prop"),
        )
        val entry = ProvenanceEntry(
            locator = UriLocator("https://example.com/doc", display = "Doc"),
            chunkId = "c1",
            startOffset = 5,
            endOffset = 25,
            contentHash = "deadbeef",
        )

        val prop = Proposition(
            contextId = ctx,
            text = "Alice works at Acme",
            mentions = emptyList(),
            confidence = 0.9,
            temporal = tm,
            provenanceEntries = listOf(entry),
        )

        // Existing mutation helper still works and keeps provenance.
        val promoted = prop.withStatus(PropositionStatus.PROMOTED)
        assertEquals(tm, promoted.temporal)
        assertEquals(listOf(entry), promoted.provenanceEntries)
        assertEquals(PropositionStatus.PROMOTED, promoted.status)

        // Chunk-id grounding list is independent of provenanceEntries and unaffected.
        assertTrue(promoted.grounding.isEmpty())
    }

    @Test
    fun `factory wires temporal and grounding entries`() {
        val now = Instant.now()
        val tm = TemporalMetadata(observedAt = now, validFrom = now)
        val entry = ProvenanceEntry(locator = ContentAddressedLocator("hash"))

        val prop = Proposition.create(
            id = "p1",
            contextIdValue = "ctx",
            text = "Test",
            mentions = emptyList(),
            confidence = 0.5,
            decay = 0.0,
            reasoning = null,
            grounding = emptyList(),
            created = now,
            revised = now,
            status = PropositionStatus.ACTIVE,
            temporal = tm,
            provenanceEntries = listOf(entry),
        )

        assertEquals(tm, prop.temporal)
        assertEquals(listOf(entry), prop.provenanceEntries)
    }
}
