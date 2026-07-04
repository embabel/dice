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
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorComponent
import com.embabel.dice.spi.CollectorDecision
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.spi.RetiredProposition
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

    @AfterEach
    fun cleanUp() {
        CollectorTraceSchema.LABELS.forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
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
}
