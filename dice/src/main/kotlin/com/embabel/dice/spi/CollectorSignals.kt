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
package com.embabel.dice.spi

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus

/**
 * One proposed pair of propositions worth scoring.
 *
 * @property proposalScore An optional score the pair source already computed when proposing
 *   this pair (e.g. vector cosine); a matching scorer may reuse it instead of recomputing.
 *   Null when the source has no score to offer.
 */
data class CandidatePair(
    val anchor: Proposition,
    val member: Proposition,
    val proposalScore: Double? = null,
)

/**
 * Proposes candidate pairs worth scoring, e.g. by vector clustering.
 */
fun interface CandidatePairSource {
    fun propose(candidates: List<Proposition>, contextId: ContextId): List<CandidatePair>
}

/**
 * One signal's score for one pair.
 *
 * @property score The signal's score for this pair.
 * @property veto When true, this signal rejects the merge outright regardless of other signals.
 */
data class CollectorSignalScore(
    val signal: String,
    val score: Double,
    val weight: Double = 1.0,
    val veto: Boolean = false,
    val explanation: String? = null,
    val evidenceRef: String? = null,
)

/**
 * Scores one proposed pair on a single signal. Returning null means abstain — the signal is
 * left out of the blend. Inject dependencies via the constructor, not per call.
 */
fun interface CollectorSignalScorer {
    fun score(pair: CandidatePair, contextId: ContextId): CollectorSignalScore?
}

/**
 * A scored, aggregated edge between two propositions across all signals.
 */
data class CollectorCandidateEdge(
    val anchorId: String,
    val memberId: String,
    val aggregateScore: Double,
    val vetoed: Boolean,
    val signals: List<CollectorSignalScore>,
)

/**
 * A connected component of propositions the collector formed from surviving edges.
 */
data class CollectorComponent(
    val componentId: String,
    val memberIds: List<String>,
)

/**
 * Records everything needed to reverse a collapse: the run it belongs to, the chosen survivor,
 * and for each retired proposition its prior status plus the grounding, provenance and source ids
 * that a merging sweep folded onto the survivor. The [runId] lets a caller find the candidate edges
 * (and their per-signal scores) behind this decision via [CollectorTraceQuery.findEdgesByRun].
 */
data class CollectorDecision(
    val runId: String,
    val componentId: String,
    val survivorId: String,
    val action: String,
    val retired: List<RetiredProposition>,
)

/**
 * One proposition that was folded into a survivor, and what a merging sweep would carry over
 * from it (grounding, provenance and source ids) so the fold can be undone.
 */
data class RetiredProposition(
    val propositionId: String,
    val priorStatus: PropositionStatus,
    val foldedGrounding: List<String> = emptyList(),
    val foldedProvenanceRefs: List<String> = emptyList(),
    val foldedSourceIds: List<String> = emptyList(),
)

/**
 * Persists the collector's inspectable decision trace under a run id. In-memory and graph-backed
 * implementations both satisfy this.
 */
interface CollectorTraceStore {
    /**
     * Registers which context a run belongs to, so [deleteTracesForContext] can find and clear
     * that run's rows later. Call this once per run, e.g. before recording its first edge.
     */
    fun recordRunContext(runId: String, contextId: ContextId)

    fun recordCandidateEdges(runId: String, edges: List<CollectorCandidateEdge>)
    fun recordComponents(runId: String, components: List<CollectorComponent>)
    fun recordDecision(runId: String, decision: CollectorDecision)

    /** Erasure hook: deleting a context's data must cascade to its trace rows. */
    fun deleteTracesForContext(contextId: ContextId)
}

/**
 * The read side of the collector trace: look up what a run decided, or explain why one
 * proposition was collapsed. Callers that only need to inspect trace data (e.g. an admin API)
 * should depend on this instead of the concrete storage implementation.
 */
interface CollectorTraceQuery {
    fun findEdgesByRun(runId: String): List<CollectorCandidateEdge>
    fun findDecisionsByRun(runId: String): List<CollectorDecision>
    fun findDecisionForProposition(propositionId: String): CollectorDecision?
}

/**
 * Groups proposition ids into connected components from scored, non-vetoed edges.
 */
interface ConnectedComponentsFinder {
    /**
     * @return propositionId -> componentId, one entry per id in [propositionIds].
     */
    fun findComponents(
        runId: String,
        propositionIds: Set<String>,
        edges: List<CollectorCandidateEdge>,
    ): Map<String, String>
}
