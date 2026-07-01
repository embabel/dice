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
package com.embabel.dice.eval

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.projection.memory.CollectorRunner
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end proof for issue #56: when the dedup sweep collapses a real cluster, the survivor must
 * ABSORB the loser's grounding and provenance before the loser is retired — otherwise that evidence
 * silently disappears from retrieval (STALE is excluded).
 *
 * This drives the whole path against a real [InMemoryPropositionRepository] with the deterministic
 * [FixedVectorEmbeddingService]: two propositions with identical text embed to the same vector and
 * so cluster as near-duplicates. The runner is assembled via [CollectorRunner.Builder.withDuplicateDetection],
 * the dedicated dedup wiring, so this also exercises that the dedup sweep selects the merging policy.
 */
class CollectorDedupMergesGroundingTest {

    private val contextId = ContextId("dedup-merge-context")

    private class RecordingListener : DiceEventListener {
        val events = mutableListOf<DiceEvent>()
        override fun onEvent(event: DiceEvent) {
            events.add(event)
        }
    }

    private fun provenance(uri: String) = ProvenanceEntry(UriLocator(uri))

    @Test
    fun `dedup collapse folds the loser's grounding and provenance onto the surviving proposition`() {
        val store = InMemoryPropositionRepository(embeddingService = FixedVectorEmbeddingService())

        // Identical text -> identical fixed vector -> cosine 1.0 -> the two cluster as duplicates.
        // The stronger (higher confidence) one survives; the weaker is the loser.
        val survivor = Proposition(
            contextId = contextId,
            text = "Alice manages the release",
            mentions = emptyList(),
            confidence = 0.9,
            decay = 0.0,
            grounding = listOf("chunk-survivor"),
            sourceIds = listOf("src-survivor"),
            provenanceEntries = listOf(provenance("uri://survivor")),
            reinforceCount = 1,
        )
        val loser = Proposition(
            contextId = contextId,
            text = "Alice manages the release",
            mentions = emptyList(),
            confidence = 0.3,
            decay = 0.0,
            grounding = listOf("chunk-loser"),
            sourceIds = listOf("src-loser"),
            provenanceEntries = listOf(provenance("uri://loser")),
        )
        store.saveAll(listOf(survivor, loser))

        val recording = RecordingListener()
        val runner = CollectorRunner
            .withRepository(store)
            .withDuplicateDetection()
            .withEventListener(recording)
            .build()

        val result = runner.run(contextId, dryRun = false)
        assertTrue(result.applied.isNotEmpty(), "the dedup sweep collapses at least one duplicate")

        // The loser is retired to STALE — but only AFTER its evidence was folded across.
        val retiredLoser = store.findById(loser.id)
        assertNotNull(retiredLoser)
        assertEquals(PropositionStatus.STALE, retiredLoser!!.status)

        // The survivor is still ACTIVE and now carries the union of both propositions' evidence.
        val mergedSurvivor = store.findById(survivor.id)
        assertNotNull(mergedSurvivor)
        assertEquals(PropositionStatus.ACTIVE, mergedSurvivor!!.status)
        assertEquals(setOf("chunk-survivor", "chunk-loser"), mergedSurvivor.grounding.toSet())
        assertEquals(setOf("src-survivor", "src-loser"), mergedSurvivor.sourceIds.toSet())
        assertEquals(
            setOf("uri:uri://survivor", "uri:uri://loser"),
            mergedSurvivor.provenanceEntries.map { it.locator.key() }.toSet(),
        )
        // One collapse == one reinforcement, on top of the survivor's starting count.
        assertEquals(2, mergedSurvivor.reinforceCount)

        // The runner emitted the same status-changed event the plain transition path emits.
        val statusEvent = recording.events.filterIsInstance<PropositionStatusChanged>().single()
        assertEquals(loser.id, statusEvent.proposition.id)
        assertEquals(PropositionStatus.STALE, statusEvent.newStatus)
    }
}
