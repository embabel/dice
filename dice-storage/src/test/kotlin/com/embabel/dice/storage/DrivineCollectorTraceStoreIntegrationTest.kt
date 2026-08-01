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
import com.embabel.dice.projection.memory.collector.CollectorRunContext
import com.embabel.dice.projection.memory.collector.CollectorSurvivorPolicy
import com.embabel.dice.projection.memory.collector.MultiSignalCollectorStrategy
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.spi.CandidatePair
import com.embabel.dice.spi.CandidatePairSource
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorComponent
import com.embabel.dice.spi.CollectorDecision
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.spi.CollectorSignalScorer
import com.embabel.dice.spi.InMemoryConnectedComponentsFinder
import com.embabel.dice.spi.RetiredProposition
import com.embabel.dice.spi.undoSingleCollapse
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Integration tests for [DrivineCollectorTraceStore] against a Neo4j testcontainer (provided by
 * Drivine's test support). Each test starts from an empty graph via [cleanUp].
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineCollectorTraceStoreIntegrationTest {

    @Autowired
    private lateinit var traceStore: DrivineCollectorTraceStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var propositionRepository: DrivinePropositionRepository

    private class RecordingPropositionRepository(
        private val delegate: PropositionRepository,
    ) : PropositionRepository by delegate {

        var saveCalls = 0
            private set
        var setProvenanceCalls = 0
            private set

        override fun save(proposition: Proposition): Proposition {
            saveCalls++
            return delegate.save(proposition)
        }

        override fun setProvenance(
            propositionId: String,
            entries: List<ProvenanceEntry>,
        ): Proposition? {
            setProvenanceCalls++
            return delegate.setProvenance(propositionId, entries)
        }
    }

    private class BaseStoreView(
        delegate: PropositionRepository,
    ) : PropositionStore by delegate

    @AfterEach
    fun cleanUp() {
        CollectorTraceSchema.LABELS.forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
        propositionRepository.clearAll()
    }

    private fun prop(
        id: String,
        text: String,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        grounding: List<String> = emptyList(),
        provenance: List<ProvenanceEntry> = emptyList(),
    ) = Proposition(
        id = id,
        contextId = ContextId("ctx-undo"),
        text = text,
        mentions = emptyList(),
        confidence = 0.9,
        status = status,
        grounding = grounding,
        provenanceEntries = provenance,
    )

    private fun edge(anchorId: String, memberId: String, vetoed: Boolean = false, score: Double = 0.9) = CollectorCandidateEdge(
        anchorId = anchorId,
        memberId = memberId,
        aggregateScore = score,
        vetoed = vetoed,
        signals = listOf(
            CollectorSignalScore(signal = "lexical", score = 0.8, weight = 1.0, veto = false, explanation = "close match", evidenceRef = "ev-1"),
            CollectorSignalScore(signal = "entityOverlap", score = 1.0, weight = 0.5, veto = vetoed, explanation = null, evidenceRef = null),
        ),
    )

    private fun decisionFor(componentId: String, survivorId: String, retiredId: String) = CollectorDecision(
        // Persistence keys off the runId passed to recordDecision, so the field here is irrelevant.
        runId = "",
        componentId = componentId,
        survivorId = survivorId,
        action = "duplicate-merge",
        retired = listOf(
            RetiredProposition(
                propositionId = retiredId,
                priorStatus = PropositionStatus.ACTIVE,
                foldedGrounding = listOf("g1", "g2"),
                foldedProvenanceRefs = listOf("prov-1"),
                foldedSourceIds = listOf("src-1", "src-2"),
                foldedProvenanceEvidenceKeys = listOf("evidence-1"),
            ),
        ),
    )

    @Test
    fun `a full run's edges, components and decision persist and read back by runId`() {
        val runId = "run-1"
        val contextId = ContextId("ctx-1")

        traceStore.recordRunContext(runId, contextId)
        traceStore.recordCandidateEdges(runId, listOf(edge("A", "B"), edge("C", "D", vetoed = true, score = 0.2)))
        traceStore.recordComponents(runId, listOf(CollectorComponent("comp-1", listOf("A", "B"))))
        traceStore.recordDecision(runId, decisionFor("comp-1", survivorId = "A", retiredId = "B"))

        val edges = traceStore.findEdgesByRun(runId)
        assertEquals(2, edges.size)
        val ab = edges.single { it.anchorId == "A" && it.memberId == "B" }
        assertEquals(0.9, ab.aggregateScore)
        assertTrue(!ab.vetoed)
        assertEquals(2, ab.signals.size)
        assertEquals(setOf("lexical", "entityOverlap"), ab.signals.map { it.signal }.toSet())
        val cd = edges.single { it.anchorId == "C" && it.memberId == "D" }
        assertTrue(cd.vetoed)

        val decisions = traceStore.findDecisionsByRun(runId)
        val decision = decisions.single()
        assertEquals("comp-1", decision.componentId)
        assertEquals("A", decision.survivorId)
        assertEquals(1, decision.retired.size)
        val retired = decision.retired.single()
        assertEquals("B", retired.propositionId)
        assertEquals(PropositionStatus.ACTIVE, retired.priorStatus)
        assertEquals(listOf("g1", "g2"), retired.foldedGrounding)
        assertEquals(listOf("prov-1"), retired.foldedProvenanceRefs)
        assertEquals(listOf("src-1", "src-2"), retired.foldedSourceIds)
        assertEquals(listOf("evidence-1"), retired.foldedProvenanceEvidenceKeys)
    }

    @Test
    fun `findDecisionForProposition finds the decision by survivor or by a retired member`() {
        val runId = "run-2"
        traceStore.recordRunContext(runId, ContextId("ctx-2"))
        traceStore.recordDecision(runId, decisionFor("comp-2", survivorId = "S", retiredId = "R"))

        val bySurvivor = traceStore.findDecisionForProposition("S")
        assertEquals("comp-2", bySurvivor?.componentId)

        val byRetired = traceStore.findDecisionForProposition("R")
        assertEquals("comp-2", byRetired?.componentId)

        assertNull(traceStore.findDecisionForProposition("unknown"))
    }

    @Test
    fun `deleteTracesForContext removes only that context's rows and leaves another context intact`() {
        val runA = "run-a"
        val runB = "run-b"
        val ctxA = ContextId("ctx-a")
        val ctxB = ContextId("ctx-b")

        traceStore.recordRunContext(runA, ctxA)
        traceStore.recordCandidateEdges(runA, listOf(edge("A", "B")))
        traceStore.recordComponents(runA, listOf(CollectorComponent("comp-a", listOf("A", "B"))))
        traceStore.recordDecision(runA, decisionFor("comp-a", survivorId = "A", retiredId = "B"))

        traceStore.recordRunContext(runB, ctxB)
        traceStore.recordCandidateEdges(runB, listOf(edge("C", "D")))
        traceStore.recordComponents(runB, listOf(CollectorComponent("comp-b", listOf("C", "D"))))
        traceStore.recordDecision(runB, decisionFor("comp-b", survivorId = "C", retiredId = "D"))

        traceStore.deleteTracesForContext(ctxA)

        assertTrue(traceStore.findEdgesByRun(runA).isEmpty())
        assertTrue(traceStore.findDecisionsByRun(runA).isEmpty())

        assertEquals(1, traceStore.findEdgesByRun(runB).size)
        assertEquals(1, traceStore.findDecisionsByRun(runB).size)
    }

    @Test
    fun `recording the same runId twice is idempotent, not duplicated`() {
        val runId = "run-3"
        val contextId = ContextId("ctx-3")

        traceStore.recordRunContext(runId, contextId)
        traceStore.recordCandidateEdges(runId, listOf(edge("A", "B")))
        traceStore.recordComponents(runId, listOf(CollectorComponent("comp-3", listOf("A", "B"))))
        traceStore.recordDecision(runId, decisionFor("comp-3", survivorId = "A", retiredId = "B"))

        // Replay the exact same run.
        traceStore.recordRunContext(runId, contextId)
        traceStore.recordCandidateEdges(runId, listOf(edge("A", "B")))
        traceStore.recordComponents(runId, listOf(CollectorComponent("comp-3", listOf("A", "B"))))
        traceStore.recordDecision(runId, decisionFor("comp-3", survivorId = "A", retiredId = "B"))

        assertEquals(1, traceStore.findEdgesByRun(runId).size)
        assertEquals(2, traceStore.findEdgesByRun(runId).single().signals.size) // signals also MERGE, not duplicate
        assertEquals(1, traceStore.findDecisionsByRun(runId).size)
        assertEquals(1, traceStore.findDecisionsByRun(runId).single().retired.size)
    }

    @Test
    fun `findRetirement fetches one retired member's undo record by id`() {
        val runId = "run-4"
        traceStore.recordRunContext(runId, ContextId("ctx-4"))
        traceStore.recordDecision(runId, decisionFor("comp-4", survivorId = "S4", retiredId = "R4"))

        val retirement = traceStore.findRetirement("R4")
        assertEquals("R4", retirement?.propositionId)
        assertEquals(PropositionStatus.ACTIVE, retirement?.priorStatus)
        assertEquals(listOf("g1", "g2"), retirement?.foldedGrounding)

        assertNull(traceStore.findRetirement("S4")) // survivor id, not a retired member
        assertNull(traceStore.findRetirement("unknown"))
    }

    @Test
    fun `undoSingleCollapse does not write when the retired proposition is missing`() {
        val runId = "run-missing-retired"
        val survivor = propositionRepository.save(prop("survivor-missing-retired", "Survivor remains"))
        traceStore.recordRunContext(runId, survivor.contextId)
        traceStore.recordDecision(
            runId,
            decisionFor(
                componentId = "comp-missing-retired",
                survivorId = survivor.id,
                retiredId = "missing-retired",
            ),
        )
        val before = propositionRepository.findAll().sortedBy { it.id }
        val recordingRepository = RecordingPropositionRepository(propositionRepository)

        val result = undoSingleCollapse(
            traceQuery = traceStore,
            propositions = recordingRepository,
            survivorId = survivor.id,
            retiredId = "missing-retired",
        )

        val after = propositionRepository.findAll().sortedBy { it.id }
        assertNull(result)
        assertEquals(before, after)
        assertEquals(0, recordingRepository.saveCalls)
        assertEquals(0, recordingRepository.setProvenanceCalls)
    }

    @Test
    fun `undoSingleCollapse does not write when the survivor proposition is missing`() {
        val runId = "run-missing-survivor"
        val retired = propositionRepository.save(
            prop("retired-missing-survivor", "Retired remains", status = PropositionStatus.STALE),
        )
        traceStore.recordRunContext(runId, retired.contextId)
        traceStore.recordDecision(
            runId,
            decisionFor(
                componentId = "comp-missing-survivor",
                survivorId = "missing-survivor",
                retiredId = retired.id,
            ),
        )
        val before = propositionRepository.findAll().sortedBy { it.id }
        val recordingRepository = RecordingPropositionRepository(propositionRepository)

        val result = undoSingleCollapse(
            traceQuery = traceStore,
            propositions = recordingRepository,
            survivorId = "missing-survivor",
            retiredId = retired.id,
        )

        val after = propositionRepository.findAll().sortedBy { it.id }
        assertNull(result)
        assertEquals(before, after)
        assertEquals(0, recordingRepository.saveCalls)
        assertEquals(0, recordingRepository.setProvenanceCalls)
    }

    @Test
    fun `undoSingleCollapse restores one member and subtracts only its exclusive grounding, leaving the sibling member retired`() {
        val runId = "run-5"
        val contextId = ContextId("ctx-5")
        traceStore.recordRunContext(runId, contextId)

        // Survivor absorbed evidence from two losers. "shared" grounding was contributed by BOTH
        // losers (an overlap), "exclusive-a" only by loserA, "exclusive-b" only by loserB.
        val survivor = propositionRepository.save(
            prop("survivor-5", "Survivor text", grounding = listOf("shared", "exclusive-a", "exclusive-b")),
        )
        propositionRepository.save(prop("loserA-5", "Loser A text", status = PropositionStatus.STALE))
        propositionRepository.save(prop("loserB-5", "Loser B text", status = PropositionStatus.STALE))

        val decision = CollectorDecision(
            runId = "",
            componentId = "comp-5",
            survivorId = survivor.id,
            action = "duplicate-merge",
            retired = listOf(
                RetiredProposition(
                    propositionId = "loserA-5",
                    priorStatus = PropositionStatus.ACTIVE,
                    foldedGrounding = listOf("shared", "exclusive-a"),
                ),
                RetiredProposition(
                    propositionId = "loserB-5",
                    priorStatus = PropositionStatus.ACTIVE,
                    foldedGrounding = listOf("shared", "exclusive-b"),
                ),
            ),
        )
        traceStore.recordDecision(runId, decision)

        val result = undoSingleCollapse(
            traceQuery = traceStore,
            propositions = propositionRepository,
            survivorId = survivor.id,
            retiredId = "loserA-5",
        )

        assertTrue(result != null)
        assertEquals(PropositionStatus.ACTIVE, result!!.restored.status)

        val restoredA = propositionRepository.findById("loserA-5")
        assertEquals(PropositionStatus.ACTIVE, restoredA?.status)

        // exclusive-a is gone; shared stays because loserB (still retired) also contributed it;
        // exclusive-b is untouched since it was never loserA's to begin with.
        val updatedSurvivor = propositionRepository.findById(survivor.id)
        assertEquals(setOf("shared", "exclusive-b"), updatedSurvivor?.grounding?.toSet())

        // loserB is untouched — still STALE, not restored.
        val loserB = propositionRepository.findById("loserB-5")
        assertEquals(PropositionStatus.STALE, loserB?.status)
    }

    @Test
    fun `collapse then undo keeps grounding the survivor already owned before the merge`() {
        // Regression for B1 (fable review): the survivor independently owns "shared" grounding
        // BEFORE the collapse; the loser also carries it, plus a ref the loser exclusively
        // contributed. A blind foldedGrounding = loser.grounding would record "shared" as folded
        // from the loser, and undo would then strip it from the survivor even though the survivor
        // never lost it — silent evidence loss on the near-duplicate case the sweep exists for.
        val runId = "run-6"
        val contextId = ContextId("ctx-6")

        // Distinct text: DrivinePropositionRepository dedups by (contextId, text), and this test
        // needs two genuinely separate proposition rows to merge.
        val survivor = propositionRepository.save(
            prop("survivor-6", "Acme signed the deal", grounding = listOf("shared")),
        )
        val loser = propositionRepository.save(
            prop("loser-6", "Acme signed a deal", grounding = listOf("shared", "loser-exclusive")),
        )

        // Deterministic pair source/scorer/survivor policy so the real markComponent path runs
        // without depending on embeddings: this exercises the actual fix, not a hand-built trace.
        val strategy = MultiSignalCollectorStrategy(
            pairSources = listOf(
                CandidatePairSource { candidates, _ -> listOf(CandidatePair(anchor = candidates[0], member = candidates[1])) },
            ),
            scorers = listOf(
                CollectorSignalScorer { _, _ -> CollectorSignalScore(signal = "fixed", score = 1.0) },
            ),
            componentsFinder = InMemoryConnectedComponentsFinder(),
            traceStore = traceStore,
            survivorPolicy = CollectorSurvivorPolicy { members -> members.single { it.id == survivor.id } },
            matchThreshold = 0.5,
        )

        strategy.mark(listOf(survivor, loser), propositionRepository, CollectorRunContext(runId, contextId))

        // Merge the loser's evidence onto the survivor the way DefaultCollectorRunner would.
        val mergedSurvivor = propositionRepository.findById(survivor.id)!!.absorbEvidence(loser)
        propositionRepository.save(mergedSurvivor)

        val decision = traceStore.findDecisionsByRun(runId).single()
        val retired = decision.retired.single { it.propositionId == loser.id }
        // The fix under test: only the delta the loser actually contributed is recorded.
        assertEquals(listOf("loser-exclusive"), retired.foldedGrounding)

        val result = undoSingleCollapse(
            traceQuery = traceStore,
            propositions = propositionRepository,
            survivorId = survivor.id,
            retiredId = loser.id,
        )

        assertTrue(result != null)
        assertEquals(PropositionStatus.ACTIVE, result!!.restored.status)

        val restoredLoser = propositionRepository.findById(loser.id)
        assertEquals(PropositionStatus.ACTIVE, restoredLoser?.status)

        val updatedSurvivor = propositionRepository.findById(survivor.id)
        // (a) the survivor keeps "shared" — it owned that ref before the merge.
        // (b) the survivor loses "loser-exclusive" — that one really did come from the loser.
        assertEquals(setOf("shared"), updatedSurvivor?.grounding?.toSet())
    }

    @Test
    fun `collector undo removes only the folded revision from persistent provenance`() {
        val runId = "run-revision-undo"
        val contextId = ContextId("ctx-revision-undo")
        val locator = UriLocator("https://example.com/revision-undo")
        val revisionOne = ProvenanceEntry(locator = locator, sourceRevision = "r1")
        val revisionTwo = ProvenanceEntry(locator = locator, sourceRevision = "r2")
        val survivor = propositionRepository.save(
            prop(
                id = "survivor-revision",
                text = "Acme signed the agreement",
                provenance = listOf(revisionOne),
            ),
        )
        val loser = propositionRepository.save(
            prop(
                id = "loser-revision",
                text = "Acme signed an agreement",
                provenance = listOf(revisionOne, revisionTwo),
            ),
        )
        val strategy = MultiSignalCollectorStrategy(
            pairSources = listOf(
                CandidatePairSource {
                        candidates, _ ->
                    listOf(CandidatePair(anchor = candidates[0], member = candidates[1]))
                },
            ),
            scorers = listOf(
                CollectorSignalScorer { _, _ -> CollectorSignalScore(signal = "fixed", score = 1.0) },
            ),
            componentsFinder = InMemoryConnectedComponentsFinder(),
            traceStore = traceStore,
            survivorPolicy = CollectorSurvivorPolicy { members -> members.single { it.id == survivor.id } },
            matchThreshold = 0.5,
        )

        strategy.mark(listOf(survivor, loser), propositionRepository, CollectorRunContext(runId, contextId))
        propositionRepository.save(
            propositionRepository.findById(survivor.id)!!.absorbEvidence(loser),
        )

        undoSingleCollapse(
            traceQuery = traceStore,
            propositions = propositionRepository,
            survivorId = survivor.id,
            retiredId = loser.id,
        )

        assertEquals(
            listOf(revisionOne),
            propositionRepository.findById(survivor.id)?.provenanceEntries,
        )
    }

    @Test
    fun `collector undo removes folded provenance through a base store decorator`() {
        val runId = "run-base-store-undo"
        val contextId = ContextId("ctx-base-store-undo")
        val survivorEvidence = ProvenanceEntry(
            locator = UriLocator("https://example.com/base-store-undo/keep"),
        )
        val foldedEvidence = ProvenanceEntry(
            locator = UriLocator("https://example.com/base-store-undo/remove"),
        )
        val survivor = propositionRepository.save(
            prop(
                id = "survivor-base-store",
                text = "Acme signed the agreement",
                provenance = listOf(survivorEvidence, foldedEvidence),
            ),
        )
        propositionRepository.save(
            prop(
                id = "retired-base-store",
                text = "Acme signed an agreement",
                status = PropositionStatus.STALE,
                provenance = listOf(foldedEvidence),
            ),
        )
        traceStore.recordRunContext(runId, contextId)
        traceStore.recordDecision(
            runId,
            CollectorDecision(
                runId = runId,
                componentId = "component-base-store",
                survivorId = survivor.id,
                action = "duplicate-merge",
                retired = listOf(
                    RetiredProposition(
                        propositionId = "retired-base-store",
                        priorStatus = PropositionStatus.ACTIVE,
                        foldedProvenanceRefs = listOf(foldedEvidence.locator.key()),
                    ),
                ),
            ),
        )

        undoSingleCollapse(
            traceQuery = traceStore,
            propositions = BaseStoreView(propositionRepository),
            survivorId = survivor.id,
            retiredId = "retired-base-store",
        )

        assertEquals(
            listOf(survivorEvidence),
            propositionRepository.findById(survivor.id)?.provenanceEntries,
        )
    }
}
