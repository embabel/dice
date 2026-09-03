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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.ProvenanceEvidenceKey
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.storage.model.DerivedFrom
import com.embabel.dice.storage.model.SourceNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the graph mapper does with revisions: several revisions of one source share one `:Source`
 * node and get one `DERIVED_FROM` row each, the row's identity is the domain evidence key, and an
 * edge written before that identity existed still maps back to the entry it always meant.
 */
class PropositionGraphMapperRevisionTest {

    @Test
    fun `DerivedFrom retains its legacy Java constructor descriptor`() {
        DerivedFrom::class.java.getConstructor(
            String::class.java,
            Integer::class.java,
            Integer::class.java,
            String::class.java,
            SourceNode::class.java,
        )
    }

    @Test
    fun `revisionless and revisioned evidence round trips without duplicating source identity`() {
        val locator = UriLocator("https://example.com/source")
        val entries = listOf(
            evidence(sourceRevision = null),
            evidence(sourceRevision = "r1"),
            evidence(sourceRevision = "r2"),
        )

        val view = PropositionGraphMapper.toProvenanceView(proposition(entries))

        assertEquals(listOf(null, "r1", "r2"), view.provenance.map { it.sourceRevision })
        assertEquals(setOf(locator.key()), view.provenance.map { it.source.key }.toSet())
        assertFalse(
            DerivedFrom::class.java.declaredFields.any { it.name == "sourceKey" },
            "the source key belongs to the shared node, not to every edge that points at it",
        )
        assertEquals(entries, PropositionGraphMapper.toProposition(view).provenanceEntries)
    }

    /**
     * The edge identity is not a storage-local encoding: it is exactly what `ProvenanceEvidenceKey`
     * mints for the same entry, so a stored row and a recorded fold reference are the same string.
     */
    @Test
    fun `the stored edge key is the domain evidence key`() {
        val entry = evidence(sourceRevision = "r1")

        val stored = entryKey(entry)

        assertEquals(ProvenanceEvidenceKey.encode(entry), stored)
        assertTrue(
            ProvenanceEvidenceKey.matches(entry, stored),
            "an edge's own key must name the evidence it was written from",
        )
        assertFalse(
            ProvenanceEvidenceKey.matches(entry.copy(sourceRevision = "r2"), stored),
            "and must not name a different revision of it",
        )
    }

    @Test
    fun `entry key is stable and includes every evidence component`() {
        val base = evidence(sourceRevision = "r1")
        val stableKey = entryKey(base)

        assertEquals(stableKey, entryKey(base.copy()))
        listOf(
            base.copy(locator = UriLocator("https://example.com/other")),
            base.copy(sourceRevision = "r2"),
            base.copy(chunkId = "other-chunk"),
            base.copy(startOffset = 2),
            base.copy(endOffset = 4),
            base.copy(contentHash = "other-hash"),
        ).forEach { changed ->
            assertNotEquals(stableKey, entryKey(changed))
        }
    }

    @Test
    fun `an edge with no stored identity still maps back to its entry`() {
        val original = evidence(sourceRevision = null)
        val mapped = PropositionGraphMapper.toProvenanceView(proposition(listOf(original)))
        val legacy = mapped.copy(provenance = mapped.provenance.map { it.copy(entryKey = null) })

        assertNull(legacy.provenance.single().entryKey)
        assertEquals(original, PropositionGraphMapper.toProposition(legacy).provenanceEntries.single())
    }

    private fun entryKey(entry: ProvenanceEntry): String =
        requireNotNull(
            PropositionGraphMapper.toProvenanceView(proposition(listOf(entry))).provenance.single().entryKey,
        ) { "the mapper must give every edge an identity" }

    private fun proposition(entries: List<ProvenanceEntry>): Proposition =
        Proposition(
            contextId = ContextId("context-1"),
            text = "Mapped evidence",
            mentions = emptyList(),
            confidence = 1.0,
            provenanceEntries = entries,
        )

    private fun evidence(
        locator: UriLocator = UriLocator("https://example.com/source"),
        sourceRevision: String? = "r1",
    ): ProvenanceEntry =
        ProvenanceEntry(
            locator = locator,
            chunkId = "chunk-1",
            startOffset = 1,
            endOffset = 3,
            contentHash = "content-hash",
            sourceRevision = sourceRevision,
        )
}
