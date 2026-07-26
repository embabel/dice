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

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.storage.model.DerivedFrom
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PropositionGraphMapperRevisionTest {

    @Test
    fun `revisionless and revisioned evidence round trips without duplicating source identity`() {
        val locator = UriLocator("https://example.com/source")
        val entries = listOf(
            evidence(locator = locator, sourceRevision = null),
            evidence(locator = locator, sourceRevision = "r1"),
            evidence(locator = locator, sourceRevision = "r2"),
        )

        val view = PropositionGraphMapper.toProvenanceView(proposition(entries))

        assertEquals(listOf(null, "r1", "r2"), view.provenance.map { it.sourceRevision })
        assertEquals(setOf(locator.key()), view.provenance.map { it.source.key }.toSet())
        assertFalse(DerivedFrom::class.java.declaredFields.any { it.name == "sourceKey" })
        assertEquals(entries, PropositionGraphMapper.toProposition(view).provenanceEntries)
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
    fun `legacy null relationship identity maps losslessly and storage key stays out of domain json`() {
        val original = evidence(sourceRevision = null)
        val mapped = PropositionGraphMapper.toProvenanceView(proposition(listOf(original)))
        val legacy = mapped.copy(
            provenance = mapped.provenance.map { it.copy(entryKey = null) },
        )

        assertNull(legacy.provenance.single().entryKey)
        val roundTripped = PropositionGraphMapper.toProposition(legacy).provenanceEntries.single()
        assertEquals(original, roundTripped)
        assertFalse(jacksonObjectMapper().writeValueAsString(roundTripped).contains("entryKey"))
    }

    private fun entryKey(entry: ProvenanceEntry): String =
        assertNotNull(
            PropositionGraphMapper.toProvenanceView(proposition(listOf(entry)))
                .provenance.single().entryKey,
        )

    private fun proposition(entries: List<ProvenanceEntry>): Proposition =
        Proposition.create(
            id = "proposition-1",
            contextIdValue = "context-1",
            text = "Mapped evidence",
            mentions = emptyList(),
            confidence = 1.0,
            decay = 0.0,
            reasoning = null,
            grounding = emptyList(),
            created = Instant.EPOCH,
            revised = Instant.EPOCH,
            status = PropositionStatus.entries.first(),
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
