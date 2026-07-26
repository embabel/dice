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
import com.embabel.dice.proposition.PropositionStore

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
 *
 * @property foldedProvenanceRefs Versioned opaque evidence keys. Older persisted traces can
 *   contain raw locator keys; interpret both forms only through the provenance evidence codec.
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

    /**
     * The undo-record for one retired member of a collapse: its prior status and exactly the
     * grounding/provenance/source ids a merging sweep folded onto its survivor from it. Looks up
     * the [CollectorDecision] that retired [retiredId] and picks out its entry — a targeted
     * alternative to [findDecisionForProposition] for callers that only want this one member's
     * record, e.g. before undoing a single collapse rather than a whole run.
     *
     * @return null if no decision retired [retiredId] (nothing recorded, or it's a survivor id).
     */
    fun findRetirement(retiredId: String): RetiredProposition? =
        findDecisionForProposition(retiredId)?.retired?.firstOrNull { it.propositionId == retiredId }
}

/**
 * The result of undoing one collapse: the survivor with that member's exclusive evidence
 * subtracted, and the member restored to its prior status.
 */
data class CollapseUndoResult(
    val survivor: Proposition,
    val restored: Proposition,
)

/**
 * Undoes ONE collapse — a single survivor/retired-member pair — without disturbing the run's
 * other collapses. This is the targeted counterpart to a run-level undo: restore exactly the
 * member named by [retiredId] to its prior status, and subtract only what it (not some other
 * still-retired member of the same collapse) contributed to the survivor.
 *
 * Overlap-safe: if another member retired in the same [CollectorDecision] also folded the same
 * grounding/provenance/source id, that ref is left on the survivor — subtracting [retiredId]'s
 * copy would otherwise strip evidence a sibling merge still needs. This is why each
 * [RetiredProposition] carries its own folded set rather than the decision carrying one shared
 * union.
 *
 * @param traceQuery where the collapse decision (and its retired members) is looked up
 * @param propositions where the survivor and retired proposition are read and saved
 * @param survivorId the collapse's survivor — must match the decision that retired [retiredId]
 * @param retiredId the one retired member to restore
 * @return the updated survivor and restored proposition, or null if nothing was retired under
 *   [retiredId] (no trace of this collapse), or if either proposition no longer exists
 * @throws IllegalArgumentException if [retiredId] was retired into a different survivor than
 *   [survivorId] — a caller error, not a missing-data case
 */
fun undoSingleCollapse(
    traceQuery: CollectorTraceQuery,
    propositions: PropositionStore,
    survivorId: String,
    retiredId: String,
): CollapseUndoResult? {
    val decision = traceQuery.findDecisionForProposition(retiredId) ?: return null
    require(decision.survivorId == survivorId) {
        "Proposition $retiredId was retired into survivor ${decision.survivorId}, not $survivorId"
    }
    val retirement = decision.retired.firstOrNull { it.propositionId == retiredId } ?: return null

    // Other members of this same collapse: whatever they also folded must stay on the survivor
    // even though we're subtracting retirement's copy of it.
    val others = decision.retired.filter { it.propositionId != retiredId }
    val stillNeededGrounding = others.flatMap { it.foldedGrounding }.toSet()
    val stillNeededProvenanceRefs = others.flatMap { it.foldedProvenanceRefs }.toSet()
    val stillNeededSourceIds = others.flatMap { it.foldedSourceIds }.toSet()

    val survivor = propositions.findById(survivorId) ?: return null
    val updatedSurvivor = propositions.save(
        survivor.withoutFoldedEvidence(
            groundingToRemove = retirement.foldedGrounding.filterNot { it in stillNeededGrounding },
            provenanceRefsToRemove = retirement.foldedProvenanceRefs.filterNot { it in stillNeededProvenanceRefs },
            sourceIdsToRemove = retirement.foldedSourceIds.filterNot { it in stillNeededSourceIds },
        ),
    )

    val retiredProposition = propositions.findById(retiredId) ?: return null
    val restored = propositions.save(retiredProposition.withStatus(retirement.priorStatus))

    return CollapseUndoResult(survivor = updatedSurvivor, restored = restored)
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
