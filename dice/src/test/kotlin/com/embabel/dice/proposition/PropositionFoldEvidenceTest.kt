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
import com.embabel.dice.provenance.ProvenanceEvidenceKey
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PropositionFoldEvidenceTest {

    private val locator = UriLocator("obsidian://vault/note")

    @Test
    fun `undo removes only folded revision while preserving survivor evidence and collisions`() {
        val survivorR1 = evidence(
            revision = "r1",
            chunkId = "chunk|shared",
            contentHash = "hash",
        )
        val foldedR2 = evidence(
            revision = "r2",
            chunkId = "chunk|shared",
            contentHash = "hash",
        )
        val collision = evidence(
            revision = "r2",
            chunkId = "chunk",
            contentHash = "shared|hash",
        )
        val survivor = proposition(
            grounding = listOf("survivor-grounding"),
            sourceIds = listOf("survivor-source"),
            provenanceEntries = listOf(survivorR1, collision),
        )
        val loser = proposition(
            grounding = listOf("folded-grounding"),
            sourceIds = listOf("folded-source"),
            provenanceEntries = listOf(foldedR2),
        )

        val restored = survivor.absorbEvidence(loser).withoutFoldedEvidence(
            groundingToRemove = loser.grounding,
            provenanceRefsToRemove = listOf(ProvenanceEvidenceKey.encode(foldedR2)),
            sourceIdsToRemove = loser.sourceIds,
        )

        assertEquals(listOf(survivorR1, collision), restored.provenanceEntries)
        assertEquals(listOf("survivor-grounding"), restored.grounding)
        assertEquals(listOf("survivor-source"), restored.sourceIds)
    }

    @Test
    fun `legacy locator trace removes only revisionless evidence`() {
        val revisionless = evidence(revision = null)
        val revisioned = evidence(revision = "r1")
        val proposition = proposition(provenanceEntries = listOf(revisionless, revisioned))

        val restored = proposition.withoutFoldedEvidence(
            groundingToRemove = emptyList(),
            provenanceRefsToRemove = listOf(locator.key()),
            sourceIdsToRemove = emptyList(),
        )

        assertEquals(listOf(revisioned), restored.provenanceEntries)
    }

    @Test
    fun `malformed versioned trace fails closed without deleting evidence`() {
        val evidence = evidence(revision = "r2")
        val malformedVersionedKey = ProvenanceEvidenceKey.encode(evidence).dropLast(1)
        val proposition = proposition(provenanceEntries = listOf(evidence))

        val restored = proposition.withoutFoldedEvidence(
            groundingToRemove = emptyList(),
            provenanceRefsToRemove = listOf(malformedVersionedKey),
            sourceIdsToRemove = emptyList(),
        )

        assertEquals(listOf(evidence), restored.provenanceEntries)
    }

    private fun evidence(
        revision: String?,
        chunkId: String? = "chunk",
        contentHash: String? = "hash",
    ) = ProvenanceEntry(
        locator = locator,
        sourceRevision = revision,
        chunkId = chunkId,
        startOffset = 1,
        endOffset = 2,
        contentHash = contentHash,
    )

    private fun proposition(
        grounding: List<String> = emptyList(),
        sourceIds: List<String> = emptyList(),
        provenanceEntries: List<ProvenanceEntry> = emptyList(),
    ) = Proposition(
        id = "proposition",
        contextId = ContextId("test"),
        text = "Test proposition",
        mentions = emptyList(),
        confidence = 0.8,
        grounding = grounding,
        sourceIds = sourceIds,
        provenanceEntries = provenanceEntries,
    )
}
