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

import com.embabel.dice.common.CompositeDiceEventListener
import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.common.SafeDiceEventListener
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionLineageStaleCascade
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.projection.memory.DecayCollectorStrategy
import com.embabel.dice.projection.memory.DefaultCollectorRunner
import com.embabel.dice.projection.memory.StatusTransitionSweepPolicy
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end proof of the lifecycle→projection STALE cascade through the real
 * producer→listener wiring.
 *
 * A single test installs [ProjectionLineageStaleCascade] as the collector runner's own
 * listener (wrapped in [SafeDiceEventListener] for fault isolation, and composed with a
 * recording listener so the same test can also observe the emitted event). It then runs a
 * live, offline decay sweep that transitions an ACTIVE proposition to a terminal status and
 * asserts — in this one test — BOTH that the runner emitted a [PropositionStatusChanged] AND
 * that the seeded [ProjectionRecord] derived from that proposition is now
 * [ProjectionLifecycle.STALE]. No LLM, embedding model, network, or container.
 *
 * This deliberately drives the real event producer (the runner emits to its injected
 * listener after each applied transition) rather than two isolated unit tests or the
 * persistence-boundary repository decorator.
 */
class CollectorSweepStalesProjectionRecordTest {

    /** Captures every lifecycle event the runner emits, in order. */
    private class RecordingListener : DiceEventListener {
        val events = mutableListOf<DiceEvent>()
        override fun onEvent(event: DiceEvent) {
            events.add(event)
        }
    }

    @Test
    fun `collector sweep to a terminal status cascades the projection record to stale`() {
        val fixtures = CanonicalFlowFixtures

        // A real store seeded with the ACTIVE fixture propositions; the low-utility decay
        // candidate is the one the sweep will retire.
        val store = InMemoryPropositionRepository(embeddingService = FixedVectorEmbeddingService())
        store.saveAll(fixtures.propositions())

        // A PROJECTED lineage record for the decay candidate — what the cascade must flip to STALE.
        val recordStore = InMemoryProjectionRecordStore()
        recordStore.record(
            ProjectionRecord(
                propositionId = fixtures.decayCandidateId,
                target = "neo4j",
                targetRef = "node-${fixtures.decayCandidateId}",
                lifecycle = ProjectionLifecycle.PROJECTED,
                runId = "seed-run",
            ),
        )

        // Install the real cascade as the runner's listener. SafeDiceEventListener isolates a
        // misbehaving listener from the sweep; the recording listener lets this same test observe
        // the event. CompositeDiceEventListener fans the emitted event out to both.
        val recording = RecordingListener()
        val cascade = ProjectionLineageStaleCascade(recordStore)
        val listener = CompositeDiceEventListener(
            listOf(SafeDiceEventListener(cascade), recording),
        )

        val runner = DefaultCollectorRunner(
            repository = store,
            strategies = listOf(DecayCollectorStrategy(retireBelow = 0.5)),
            policy = StatusTransitionSweepPolicy(),
            recordStore = null,
            listener = listener,
        )

        // Live sweep (not a dry run): applies the transition, then emits to the installed listener.
        val runResult = runner.run(fixtures.contextId, dryRun = false)
        assertTrue(runResult.applied.isNotEmpty(), "the sweep applies at least one transition")

        // (a) The real producer emitted a PropositionStatusChanged to STALE for the candidate.
        val statusEvent = recording.events
            .filterIsInstance<PropositionStatusChanged>()
            .single()
        assertEquals(fixtures.decayCandidateId, statusEvent.proposition.id)
        assertEquals(PropositionStatus.STALE, statusEvent.newStatus)

        // (b) The cascade, fed by that same emit, flipped the seeded record to STALE.
        val staleRecords = recordStore.findStale()
        assertEquals(1, staleRecords.size, "exactly the candidate's record goes stale")
        assertEquals(fixtures.decayCandidateId, staleRecords.single().propositionId)
        assertTrue(
            recordStore.findByProposition(fixtures.decayCandidateId)
                .all { it.lifecycle == ProjectionLifecycle.STALE },
            "the candidate's lineage record is STALE",
        )
    }
}
