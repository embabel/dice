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
package com.embabel.dice.conformance

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorComponent
import com.embabel.dice.spi.CollectorDecision
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.spi.CollectorTraceQuery
import com.embabel.dice.spi.CollectorTraceStore
import com.embabel.dice.spi.RetiredProposition
import org.jetbrains.annotations.ApiStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

/**
 * Cross-backend contract for [CollectorTraceStore] and its read side, [CollectorTraceQuery]. It
 * checks what the interfaces document: edges and decisions read back by run with their signals and
 * retired members, the finders that look up a proposition, the undo record for a retired member,
 * and what deleting a context removes. Each subclass supplies an empty store and inherits the whole
 * suite.
 *
 * Each case reads through [query], so a store whose read side is another object can still run the
 * suite. The suite compares signals as sets. It does not check the order of edges or decisions,
 * except for the newest retirement that [CollectorTraceQuery.findDecisionRetiring] returns.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
abstract class AbstractCollectorTraceStoreContractTest {

    /** A store with no trace rows. */
    protected abstract fun store(): CollectorTraceStore

    /**
     * The read side of [store]. The default returns the store itself when it implements
     * [CollectorTraceQuery]. A backend whose read side is another object overrides this.
     *
     * @param store The store a case got from [store].
     * @return The query that reads what [store] wrote.
     */
    protected open fun query(store: CollectorTraceStore): CollectorTraceQuery =
        store as? CollectorTraceQuery ?: throw AssertionFailedError(
            "${store::class.java.name} does not implement CollectorTraceQuery. Override query(store) to return its read side.",
        )

    private val ctxA = ContextId("trace-ctx-a")
    private val ctxB = ContextId("trace-ctx-b")

    /**
     * Builds one named signal score.
     *
     * @param name The signal name.
     * @param score The signal's score.
     * @return The signal score.
     */
    private fun signal(name: String, score: Double): CollectorSignalScore =
        CollectorSignalScore(signal = name, score = score)

    /**
     * Builds a candidate edge that is not vetoed and carries one embedding signal.
     *
     * @param anchorId The anchor proposition.
     * @param memberId The member proposition.
     * @param score The edge's aggregate score, also used as the embedding score.
     * @return The edge.
     */
    private fun edge(anchorId: String, memberId: String, score: Double = 0.75): CollectorCandidateEdge =
        CollectorCandidateEdge(
            anchorId = anchorId,
            memberId = memberId,
            aggregateScore = score,
            vetoed = false,
            signals = listOf(signal("embedding", score)),
        )

    /**
     * Builds a component of two propositions.
     *
     * @param componentId The component's id.
     * @param anchorId The first member.
     * @param memberId The second member.
     * @return The component.
     */
    private fun component(componentId: String, anchorId: String, memberId: String): CollectorComponent =
        CollectorComponent(componentId = componentId, memberIds = listOf(anchorId, memberId))

    /**
     * Builds the undo record of a retired proposition that was active, with one folded value of each kind.
     *
     * @param propositionId The retired proposition.
     * @return The undo record.
     */
    private fun retired(propositionId: String): RetiredProposition = RetiredProposition(
        propositionId = propositionId,
        priorStatus = PropositionStatus.ACTIVE,
        foldedGrounding = listOf("$propositionId-grounding"),
        foldedProvenanceRefs = listOf("$propositionId-ref"),
        foldedSourceIds = listOf("$propositionId-source"),
        foldedProvenanceEvidenceKeys = listOf("$propositionId-evidence-key"),
    )

    /**
     * Builds a merge decision that keeps one proposition and retires another.
     *
     * @param runId The run that made the decision.
     * @param componentId The component the decision covers.
     * @param survivorId The proposition that is kept.
     * @param retiredId The proposition that is retired.
     * @return The decision.
     */
    private fun decision(
        runId: String,
        componentId: String,
        survivorId: String,
        retiredId: String,
    ): CollectorDecision = CollectorDecision(
        runId = runId,
        componentId = componentId,
        survivorId = survivorId,
        action = "MERGE",
        retired = listOf(retired(retiredId)),
    )

    @Test
    @DisplayName("an unknown run has no edges and no decisions")
    open fun anUnknownRunHasNoEdgesAndNoDecisions() {
        val store = store()
        val reader = query(store)

        assertTrue(reader.findEdgesByRun("run-unknown").isEmpty())
        assertTrue(reader.findDecisionsByRun("run-unknown").isEmpty())
    }

    @Test
    @DisplayName("edges read back by run with their signals")
    open fun edgesReadBackByRunWithTheirSignals() {
        val store = store()
        val signals = listOf(
            CollectorSignalScore(
                signal = "embedding",
                score = 0.75,
                weight = 2.0,
                veto = true,
                explanation = "Close in meaning",
                evidenceRef = "evidence-1",
            ),
            CollectorSignalScore(
                signal = "name",
                score = 0.5,
                weight = 0.5,
                veto = false,
                explanation = "Same short name",
                evidenceRef = "evidence-2",
            ),
        )
        val written = CollectorCandidateEdge(
            anchorId = "p-1",
            memberId = "p-2",
            aggregateScore = 0.75,
            vetoed = true,
            signals = signals,
        )
        store.recordRunContext("run-1", ctxA)
        store.recordCandidateEdges("run-1", listOf(written))

        val read = query(store).findEdgesByRun("run-1").single()

        assertEquals(written.anchorId, read.anchorId)
        assertEquals(written.memberId, read.memberId)
        assertEquals(written.aggregateScore, read.aggregateScore)
        assertEquals(written.vetoed, read.vetoed)
        assertEquals(signals.toSet(), read.signals.toSet())
    }

    @Test
    @DisplayName("edges of one run are not returned for another")
    open fun edgesOfOneRunAreNotReturnedForAnother() {
        val store = store()
        val edgeOne = edge("p-1", "p-2")
        val edgeTwo = edge("p-3", "p-4")
        store.recordRunContext("run-1", ctxA)
        store.recordRunContext("run-2", ctxA)
        store.recordCandidateEdges("run-1", listOf(edgeOne))
        store.recordCandidateEdges("run-2", listOf(edgeTwo))

        val reader = query(store)

        assertEquals(listOf(edgeOne.anchorId to edgeOne.memberId), reader.findEdgesByRun("run-1").map { it.anchorId to it.memberId })
        assertEquals(listOf(edgeTwo.anchorId to edgeTwo.memberId), reader.findEdgesByRun("run-2").map { it.anchorId to it.memberId })
    }

    @Test
    @DisplayName("decisions read back by run with their retired members")
    open fun decisionsReadBackByRunWithTheirRetiredMembers() {
        val store = store()
        val written = decision("run-1", "comp-1", "p-1", "p-2")
        store.recordRunContext("run-1", ctxA)
        store.recordDecision("run-1", written)

        assertEquals(listOf(written), query(store).findDecisionsByRun("run-1"))
    }

    @Test
    @DisplayName("findDecisionForProposition answers for the survivor and for a retired member")
    open fun findDecisionForPropositionAnswersForSurvivorAndRetiredMember() {
        val store = store()
        val written = decision("run-1", "comp-1", "p-1", "p-2")
        store.recordRunContext("run-1", ctxA)
        store.recordDecision("run-1", written)

        val reader = query(store)

        assertEquals(written, reader.findDecisionForProposition("p-1"))
        assertEquals(written, reader.findDecisionForProposition("p-2"))
    }

    @Test
    @DisplayName("findDecisionForProposition is null for an id no decision names")
    open fun findDecisionForPropositionIsNullForAnUnknownId() {
        val store = store()
        store.recordRunContext("run-1", ctxA)
        store.recordDecision("run-1", decision("run-1", "comp-1", "p-1", "p-2"))

        assertNull(query(store).findDecisionForProposition("p-unknown"))
    }

    @Test
    @DisplayName("findDecisionRetiring ignores a decision the id only survived")
    open fun findDecisionRetiringIgnoresADecisionTheIdOnlySurvived() {
        val store = store()
        val written = decision("run-1", "comp-1", "p-1", "p-2")
        store.recordRunContext("run-1", ctxA)
        store.recordDecision("run-1", written)

        val reader = query(store)

        assertNull(reader.findDecisionRetiring("p-1"))
        assertEquals(written, reader.findDecisionRetiring("p-2"))
    }

    @Test
    @DisplayName("findDecisionRetiring answers the newest decision that retired the id")
    open fun findDecisionRetiringAnswersTheNewestRetirement() {
        val store = store()
        val first = decision("run-1", "comp-1", "p-b", "p-a")
        val second = decision("run-2", "comp-2", "p-c", "p-a")
        store.recordRunContext("run-1", ctxA)
        store.recordRunContext("run-2", ctxA)
        store.recordDecision("run-1", first)
        store.recordDecision("run-2", second)

        assertEquals(second, query(store).findDecisionRetiring("p-a"))
    }

    @Test
    @DisplayName("findRetirement returns the undo record of a retired member")
    open fun findRetirementReturnsTheUndoRecordOfARetiredMember() {
        val store = store()
        val written = decision("run-1", "comp-1", "p-1", "p-2")
        store.recordRunContext("run-1", ctxA)
        store.recordDecision("run-1", written)

        val reader = query(store)

        assertEquals(written.retired.single(), reader.findRetirement("p-2"))
        assertNull(reader.findRetirement("p-1"))
    }

    @Test
    @DisplayName("deleteTracesForContext removes the rows of that context and leaves the others")
    open fun deleteTracesForContextRemovesThatContextOnly() {
        val store = store()
        val edgeA = edge("p-1", "p-2")
        val edgeB = edge("p-3", "p-4")
        val decisionA = decision("run-a", "comp-a", "p-1", "p-2")
        val decisionB = decision("run-b", "comp-b", "p-3", "p-4")

        store.recordRunContext("run-a", ctxA)
        store.recordCandidateEdges("run-a", listOf(edgeA))
        store.recordComponents("run-a", listOf(component("comp-a", "p-1", "p-2")))
        store.recordDecision("run-a", decisionA)

        store.recordRunContext("run-b", ctxB)
        store.recordCandidateEdges("run-b", listOf(edgeB))
        store.recordComponents("run-b", listOf(component("comp-b", "p-3", "p-4")))
        store.recordDecision("run-b", decisionB)

        store.deleteTracesForContext(ctxA)

        val reader = query(store)
        assertTrue(reader.findEdgesByRun("run-a").isEmpty())
        assertTrue(reader.findDecisionsByRun("run-a").isEmpty())
        assertNull(reader.findDecisionRetiring("p-2"))
        assertEquals(listOf(edgeB.anchorId to edgeB.memberId), reader.findEdgesByRun("run-b").map { it.anchorId to it.memberId })
        assertEquals(listOf(decisionB), reader.findDecisionsByRun("run-b"))
    }
}
