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
import com.embabel.dice.projection.lineage.CollectorOutcome
import com.embabel.dice.projection.lineage.CollectorRecord
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.CollectorRun
import com.embabel.dice.projection.memory.collector.CollectorRunContext
import com.embabel.dice.projection.memory.collector.CollectorSurvivorPolicy
import com.embabel.dice.projection.memory.collector.MultiSignalCollectorStrategy
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.ProvenanceSubtractionCapable
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.ProvenanceEvidenceKey
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.spi.CandidatePair
import com.embabel.dice.spi.CollapseUndoCommand
import com.embabel.dice.spi.CandidatePairSource
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorComponent
import com.embabel.dice.spi.CollectorDecision
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.spi.CollectorSignalScorer
import com.embabel.dice.spi.InMemoryConnectedComponentsFinder
import com.embabel.dice.spi.MarkReason
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
import java.time.Instant

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

    @Autowired
    private lateinit var recordStore: DrivineCollectorRecordStore

    @AfterEach
    fun cleanUp() {
        (CollectorTraceSchema.LABELS + listOf("CollectorRecord", "CollectorRun")).forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
        propositionRepository.clearAll()
    }

    /** The context every fixture proposition below is built in, and the one an undo is issued for. */
    private val undoContextId = ContextId("ctx-undo")

    /**
     * Calls the real entry point. Undo requires a context and an audit record store, so every test
     * here goes through one place that supplies both.
     */
    private fun undo(
        propositions: PropositionStore,
        survivorId: String,
        retiredId: String,
        records: CollectorRecordStore? = recordStore,
        context: ContextId = undoContextId,
    ) = undoSingleCollapse(
        command = CollapseUndoCommand(contextId = context, survivorId = survivorId, retiredId = retiredId),
        traceQuery = traceStore,
        propositions = propositions,
        collectorRecords = records,
    )

    /**
     * The audit trail a real merging sweep leaves behind: a live run header and a transition record
     * naming the survivor it folded this member into. That pair is what authorizes an undo.
     */
    private fun authorize(runId: String, memberId: String, survivorId: String) {
        recordStore.recordRun(CollectorRun(runId = runId, startedAt = Instant.now(), dryRun = false))
        recordStore.record(collectorRecord(runId, memberId, proposed = survivorId, applied = survivorId))
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

    private fun derivedFromEdges(propositionId: String): Long =
        persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (:Proposition {id: \$id})-[r:DERIVED_FROM]->() RETURN count(r) AS c")
                .bind(mapOf("id" to propositionId))
                .transform(Long::class.java),
        )

    /** A deterministic one-pair collapse, so the trace under test comes from the real strategy. */
    private fun collapsingStrategy(survivorId: String) = MultiSignalCollectorStrategy(
        pairSources = listOf(
            CandidatePairSource { candidates, _ -> listOf(CandidatePair(anchor = candidates[0], member = candidates[1])) },
        ),
        scorers = listOf(CollectorSignalScorer { _, _ -> CollectorSignalScore(signal = "fixed", score = 1.0) }),
        componentsFinder = InMemoryConnectedComponentsFinder(),
        traceStore = traceStore,
        survivorPolicy = CollectorSurvivorPolicy { members -> members.single { it.id == survivorId } },
        matchThreshold = 0.5,
    )

    /** Counts the writes an undo attempts, so "nothing was written" is an assertion. */
    private class RecordingPropositionRepository(
        private val delegate: DrivinePropositionRepository,
    ) : PropositionRepository by delegate, ProvenanceSubtractionCapable {

        var writes = 0
            private set

        override fun save(proposition: Proposition): Proposition {
            writes++
            return delegate.save(proposition)
        }

        override fun setProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? {
            writes++
            return delegate.setProvenance(propositionId, entries)
        }

        override fun subtractProvenance(propositionId: String, provenanceRefs: List<String>): Proposition? {
            writes++
            return delegate.subtractProvenance(propositionId, provenanceRefs)
        }
    }

    /**
     * What a caller holding no repository type sees: the base store contract plus the one capability
     * undo insists on. The subtraction forwards to the Drivine repository, so this pins that undo
     * needs nothing richer than that pair.
     */
    private class BaseStoreView(
        private val delegate: DrivinePropositionRepository,
    ) : PropositionStore by delegate, ProvenanceSubtractionCapable {

        override fun subtractProvenance(propositionId: String, provenanceRefs: List<String>): Proposition? =
            delegate.subtractProvenance(propositionId, provenanceRefs)
    }

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
                foldedProvenanceEvidenceKeys = listOf("dice-provenance:v1:evidence-1"),
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
        assertEquals(listOf("dice-provenance:v1:evidence-1"), retired.foldedProvenanceEvidenceKeys)
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
        // The path undo reads: the whole node, not the projected decision row.
        assertEquals(listOf("dice-provenance:v1:evidence-1"), retirement?.foldedProvenanceEvidenceKeys)

        assertNull(traceStore.findRetirement("S4")) // survivor id, not a retired member
        assertNull(traceStore.findRetirement("unknown"))
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
        authorize(runId, "loserA-5", survivor.id)

        val result = undo(propositionRepository, survivor.id, "loserA-5")

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

        // Merge the loser's evidence onto the survivor and retire it, the way
        // DefaultCollectorRunner would. Undo reads the retirement off the loser's status, so a
        // fold that never transitioned it is not an applied fold.
        val mergedSurvivor = propositionRepository.findById(survivor.id)!!.absorbEvidence(loser)
        propositionRepository.save(mergedSurvivor)
        propositionRepository.save(propositionRepository.findById(loser.id)!!.withStatus(PropositionStatus.STALE))

        val decision = traceStore.findDecisionsByRun(runId).single()
        val retired = decision.retired.single { it.propositionId == loser.id }
        // The fix under test: only the delta the loser actually contributed is recorded.
        assertEquals(listOf("loser-exclusive"), retired.foldedGrounding)
        authorize(runId, loser.id, survivor.id)

        val result = undo(propositionRepository, survivor.id, loser.id)

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
    fun `folding a revisioned loser and undoing leaves the survivor's evidence as it was`() {
        // The survivor cites r1 of a document; the loser cites r1 and r2. Both revisions share one
        // locator key, so a fold recorded by locator key alone would name nothing, and r2 would sit
        // on the survivor after the undo with an edge nobody can account for.
        val runId = "run-revision-undo"
        val locator = UriLocator("https://example.com/revision-undo")
        val revisionOne = ProvenanceEntry(locator = locator, sourceRevision = "r1")
        val revisionTwo = ProvenanceEntry(locator = locator, sourceRevision = "r2")
        val survivor = propositionRepository.save(
            prop("survivor-revision", "Acme signed the agreement", provenance = listOf(revisionOne)),
        )
        val loser = propositionRepository.save(
            prop("loser-revision", "Acme signed an agreement", provenance = listOf(revisionOne, revisionTwo)),
        )
        val evidenceBeforeTheFold = propositionRepository.findById(survivor.id)!!.provenanceEntries
        assertEquals(listOf(revisionOne), evidenceBeforeTheFold)
        assertEquals(1L, derivedFromEdges(survivor.id))

        collapsingStrategy(survivor.id).mark(
            listOf(survivor, loser),
            propositionRepository,
            CollectorRunContext(runId, survivor.contextId),
        )
        propositionRepository.save(propositionRepository.findById(survivor.id)!!.absorbEvidence(loser))
        propositionRepository.save(propositionRepository.findById(loser.id)!!.withStatus(PropositionStatus.STALE))

        assertEquals(
            setOf(revisionOne, revisionTwo),
            propositionRepository.findById(survivor.id)!!.provenanceEntries.toSet(),
        )
        assertEquals(2L, derivedFromEdges(survivor.id))
        assertEquals(
            listOf(ProvenanceEvidenceKey.encode(revisionTwo)),
            traceStore.findRetirement(loser.id)?.foldedProvenanceEvidenceKeys,
        )
        authorize(runId, loser.id, survivor.id)

        undo(propositionRepository, survivor.id, loser.id)

        assertEquals(evidenceBeforeTheFold, propositionRepository.findById(survivor.id)?.provenanceEntries)
        assertEquals(1L, derivedFromEdges(survivor.id))
        assertEquals(PropositionStatus.ACTIVE, propositionRepository.findById(loser.id)?.status)
    }

    @Test
    fun `undo removes folded evidence for a caller holding the base store and the subtraction`() {
        val runId = "run-base-store-undo"
        val keep = ProvenanceEntry(UriLocator("https://example.com/base-store-undo/keep"))
        val folded = ProvenanceEntry(UriLocator("https://example.com/base-store-undo/remove"))
        val survivor = propositionRepository.save(
            prop("survivor-base-store", "Acme signed the agreement", provenance = listOf(keep, folded)),
        )
        propositionRepository.save(
            prop(
                "retired-base-store",
                "Acme signed an agreement",
                status = PropositionStatus.STALE,
                provenance = listOf(folded),
            ),
        )
        traceStore.recordRunContext(runId, survivor.contextId)
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
                        foldedProvenanceRefs = listOf(folded.locator.key()),
                        foldedProvenanceEvidenceKeys = listOf(ProvenanceEvidenceKey.encode(folded)),
                    ),
                ),
            ),
        )

        authorize(runId, "retired-base-store", survivor.id)

        undo(BaseStoreView(propositionRepository), survivor.id, "retired-base-store")

        assertEquals(listOf(keep), propositionRepository.findById(survivor.id)?.provenanceEntries)
        assertEquals(1L, derivedFromEdges(survivor.id))
    }

    @Test
    fun `undoing both members of a shared fold drains the survivor's edge back to pre-collapse`() {
        // Two losers folded the same revision onto the survivor. Only the last undo may take it,
        // and it must take the DERIVED_FROM edge with it — the whole point of routing evidence
        // removal through subtractProvenance rather than save.
        val runId = "run-shared-fold"
        val locator = UriLocator("https://example.com/shared-fold")
        val kept = ProvenanceEntry(locator = locator, sourceRevision = "r1")
        val shared = ProvenanceEntry(locator = locator, sourceRevision = "r2")
        val survivor = propositionRepository.save(
            prop("survivor-shared", "Acme signed the agreement", provenance = listOf(kept, shared)),
        )
        propositionRepository.save(
            prop("loser-a-shared", "Acme signed a deal", status = PropositionStatus.STALE, provenance = listOf(shared)),
        )
        propositionRepository.save(
            prop("loser-b-shared", "Acme signed the deal", status = PropositionStatus.STALE, provenance = listOf(shared)),
        )
        val sharedKey = ProvenanceEvidenceKey.encode(shared)
        traceStore.recordRunContext(runId, survivor.contextId)
        traceStore.recordDecision(
            runId,
            CollectorDecision(
                runId = runId,
                componentId = "comp-shared-fold",
                survivorId = survivor.id,
                action = "duplicate-merge",
                retired = listOf(
                    RetiredProposition(
                        propositionId = "loser-a-shared",
                        priorStatus = PropositionStatus.ACTIVE,
                        foldedProvenanceEvidenceKeys = listOf(sharedKey),
                    ),
                    RetiredProposition(
                        propositionId = "loser-b-shared",
                        priorStatus = PropositionStatus.ACTIVE,
                        foldedProvenanceEvidenceKeys = listOf(sharedKey),
                    ),
                ),
            ),
        )
        assertEquals(2L, derivedFromEdges(survivor.id))
        authorize(runId, "loser-a-shared", survivor.id)
        authorize(runId, "loser-b-shared", survivor.id)

        undo(propositionRepository, survivor.id, "loser-a-shared")

        assertEquals(
            setOf(kept, shared),
            propositionRepository.findById(survivor.id)!!.provenanceEntries.toSet(),
        )
        assertEquals(2L, derivedFromEdges(survivor.id))

        undo(propositionRepository, survivor.id, "loser-b-shared")

        assertEquals(listOf(kept), propositionRepository.findById(survivor.id)?.provenanceEntries)
        assertEquals(1L, derivedFromEdges(survivor.id))
        assertEquals(PropositionStatus.ACTIVE, propositionRepository.findById("loser-a-shared")?.status)
        assertEquals(PropositionStatus.ACTIVE, propositionRepository.findById("loser-b-shared")?.status)
    }

    @Test
    fun `on the graph store one record survives per member and it names the applied merge target`() {
        // The audit store MERGEs on (propositionId, runId), so a member marked by two strategies
        // keeps ONE row here and the last write wins. Reading the merge target off the mark would
        // therefore report whichever mark happened to be written last — here, a survivor the sweep
        // never merged into. The applied target is written on every one of the member's records, so
        // it is the same answer whichever row survives.
        val runId = "run-overwrite"
        val locator = UriLocator("https://example.com/overwrite")
        val kept = ProvenanceEntry(locator = locator, sourceRevision = "r1")
        val disputed = ProvenanceEntry(locator = locator, sourceRevision = "r2")
        val appliedSurvivor = propositionRepository.save(
            prop("survivor-applied-target", "Acme signed the agreement", provenance = listOf(kept, disputed)),
        )
        val bystander = propositionRepository.save(
            prop("survivor-never-merged", "Acme closed the round", provenance = listOf(kept, disputed)),
        )
        propositionRepository.save(
            prop(
                "loser-overwrite",
                "Acme signed an agreement",
                status = PropositionStatus.STALE,
                provenance = listOf(disputed),
            ),
        )
        // The trace names the bystander as survivor — the collapse some strategy proposed.
        traceStore.recordRunContext(runId, bystander.contextId)
        traceStore.recordDecision(
            runId,
            CollectorDecision(
                runId = runId,
                componentId = "comp-overwrite",
                survivorId = bystander.id,
                action = "duplicate-merge",
                retired = listOf(
                    RetiredProposition(
                        propositionId = "loser-overwrite",
                        priorStatus = PropositionStatus.ACTIVE,
                        foldedProvenanceEvidenceKeys = listOf(ProvenanceEvidenceKey.encode(disputed)),
                    ),
                ),
            ),
        )
        recordStore.recordRun(CollectorRun(runId = runId, startedAt = Instant.now(), dryRun = false))
        // Two marks, written in the order the runner writes them, both carrying the applied target.
        recordStore.record(collectorRecord(runId, "loser-overwrite", proposed = appliedSurvivor.id, applied = appliedSurvivor.id))
        recordStore.record(collectorRecord(runId, "loser-overwrite", proposed = bystander.id, applied = appliedSurvivor.id))

        // The overwrite is real: one row, and its reason names the LAST mark written.
        val stored = recordStore.findByProposition("loser-overwrite").single()
        assertEquals(bystander.id, (stored.reason as MarkReason.Duplicate).survivorId)
        assertEquals(appliedSurvivor.id, stored.mergedIntoId)

        val result = undo(propositionRepository, bystander.id, "loser-overwrite")

        assertNull(result)
        assertEquals(
            setOf(kept, disputed),
            propositionRepository.findById(bystander.id)!!.provenanceEntries.toSet(),
            "the survivor the sweep never merged into keeps everything it owns",
        )
        assertEquals(2L, derivedFromEdges(bystander.id))
    }

    private fun collectorRecord(runId: String, propositionId: String, proposed: String, applied: String?) =
        CollectorRecord(
            propositionId = propositionId,
            reason = MarkReason.Duplicate(survivorId = proposed),
            outcome = CollectorOutcome.TRANSITIONED,
            strategyName = "strategy-$proposed",
            runId = runId,
            previousStatus = PropositionStatus.ACTIVE,
            newStatus = PropositionStatus.STALE,
            mergedIntoId = applied,
        )

    @Test
    fun `undo writes nothing when the retired proposition is missing`() {
        val runId = "run-missing-retired"
        val evidence = ProvenanceEntry(UriLocator("https://example.com/missing-retired"))
        val survivor = propositionRepository.save(
            prop("survivor-missing-retired", "Survivor remains", provenance = listOf(evidence)),
        )
        traceStore.recordRunContext(runId, survivor.contextId)
        traceStore.recordDecision(
            runId,
            decisionFor("comp-missing-retired", survivorId = survivor.id, retiredId = "missing-retired"),
        )
        val before = propositionRepository.findAll().sortedBy { it.id }
        val recording = RecordingPropositionRepository(propositionRepository)

        authorize(runId, "missing-retired", survivor.id)

        val result = undo(recording, survivor.id, "missing-retired")

        assertNull(result)
        assertEquals(0, recording.writes)
        assertEquals(before, propositionRepository.findAll().sortedBy { it.id })
        assertEquals(1L, derivedFromEdges(survivor.id))
    }

    @Test
    fun `undo writes nothing when the survivor proposition is missing`() {
        val runId = "run-missing-survivor"
        val retired = propositionRepository.save(
            prop("retired-missing-survivor", "Retired remains", status = PropositionStatus.STALE),
        )
        traceStore.recordRunContext(runId, retired.contextId)
        traceStore.recordDecision(
            runId,
            decisionFor("comp-missing-survivor", survivorId = "missing-survivor", retiredId = retired.id),
        )
        val before = propositionRepository.findAll().sortedBy { it.id }
        val recording = RecordingPropositionRepository(propositionRepository)

        authorize(runId, retired.id, "missing-survivor")

        val result = undo(recording, "missing-survivor", retired.id)

        assertNull(result)
        assertEquals(0, recording.writes)
        assertEquals(before, propositionRepository.findAll().sortedBy { it.id })
    }
}
