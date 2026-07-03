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
package com.embabel.dice.projection.memory.collector

import com.embabel.agent.core.ContextId
import com.embabel.dice.projection.memory.RunAwareCollectorStrategy
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.spi.CandidatePair
import com.embabel.dice.spi.CandidatePairSource
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorComponent
import com.embabel.dice.spi.CollectorDecision
import com.embabel.dice.spi.CollectorSignalScorer
import com.embabel.dice.spi.CollectorTraceStore
import com.embabel.dice.spi.ConnectedComponentsFinder
import com.embabel.dice.spi.MarkReason
import com.embabel.dice.spi.PropositionMark
import com.embabel.dice.spi.RetiredProposition
import org.slf4j.LoggerFactory

/**
 * Composes any number of candidate-pair sources and signal scorers into one duplicate-collapse
 * strategy, replacing the single-cosine cut in
 * [com.embabel.dice.projection.memory.DuplicateCollectorStrategy] with a pluggable multi-signal
 * blend.
 *
 * A run has four stages:
 * 1. every [CandidatePairSource] proposes pairs; they're canonicalized (smaller id as anchor)
 *    and deduped;
 * 2. every [CollectorSignalScorer] scores each pair, abstentions (null) dropped, and
 *    [CollectorEdgeAggregator] blends what's left into one [CollectorCandidateEdge] per pair;
 * 3. [componentsFinder] groups candidate ids into components using the non-vetoed edges at or
 *    above [matchThreshold];
 * 4. each component of size 2+ gets one survivor (via [survivorPolicy]); everyone else in the
 *    component is marked [MarkReason.Duplicate] pointing at the survivor.
 *
 * Every edge, component, and decision is recorded in [traceStore] under the run's id, so a run
 * can be inspected or reversed after the fact — see [RetiredProposition] for what's kept about
 * each folded proposition.
 *
 * @property pairSources proposes candidate pairs worth scoring; results from every source are
 *   pooled before scoring
 * @property scorers signals consulted for every candidate pair; a scorer that returns null for a
 *   pair abstains and is left out of that pair's blend
 * @property componentsFinder groups candidate ids into components from the surviving edges
 * @property traceStore where the run's edges, components and decisions are recorded
 * @property survivorPolicy picks which proposition in a component survives a merge; defaults to
 *   the same effective-confidence / reinforceCount / id tie-break dice already uses
 * @property matchThreshold minimum aggregate score for an edge to be eligible to union its
 *   endpoints into a component; a vetoed edge never unions regardless of its score
 */
class MultiSignalCollectorStrategy(
    private val pairSources: List<CandidatePairSource>,
    private val scorers: List<CollectorSignalScorer>,
    private val componentsFinder: ConnectedComponentsFinder,
    private val traceStore: CollectorTraceStore,
    private val survivorPolicy: CollectorSurvivorPolicy = defaultCollectorSurvivorPolicy,
    private val matchThreshold: Double = 0.6,
) : RunAwareCollectorStrategy {

    private val logger = LoggerFactory.getLogger(MultiSignalCollectorStrategy::class.java)

    /**
     * Runs one full mark pass over [candidates] and returns the duplicate marks it produced.
     * Never writes to [repository] — the run is entirely read-and-report, same contract as
     * [com.embabel.dice.projection.memory.CollectorStrategy].
     *
     * A blank [CollectorRunContext.runId] (the mark-only `collect()` path, or the legacy SAM
     * bridge) means this run isn't queryable anywhere, so trace rows are skipped entirely —
     * marks are still computed and returned as normal.
     */
    override fun mark(
        candidates: List<Proposition>,
        repository: PropositionRepository,
        ctx: CollectorRunContext,
    ): List<PropositionMark> {
        val byId = candidates.associateBy { it.id }
        val contextId = ctx.contextId
        val tracing = ctx.runId.isNotBlank()
        if (tracing) traceStore.recordRunContext(ctx.runId, contextId)

        val pairs = collectPairs(candidates, contextId)
        val edges = pairs.map { pair -> scoreAndAggregate(pair, contextId) }
        if (tracing) traceStore.recordCandidateEdges(ctx.runId, edges)

        val eligibleEdges = edges.filter { !it.vetoed && it.aggregateScore >= matchThreshold }
        val candidateIds = candidates.map { it.id }.toSet()
        val componentByPropositionId = componentsFinder.findComponents(ctx.runId, candidateIds, eligibleEdges)

        val components = componentByPropositionId.entries
            .groupBy({ it.value }, { it.key })
            .map { (componentId, memberIds) -> CollectorComponent(componentId, memberIds) }
        if (tracing) traceStore.recordComponents(ctx.runId, components)

        val marks = components
            .filter { it.memberIds.size >= 2 }
            .flatMap { component -> markComponent(component, byId, ctx.runId, tracing) }
            .distinctBy { it.propositionId }
            .sortedBy { it.propositionId }

        logger.debug(
            "MultiSignalCollectorStrategy: {} pair(s), {} edge(s) ({} eligible) -> {} duplicate mark(s) from {} candidate(s)",
            pairs.size, edges.size, eligibleEdges.size, marks.size, candidates.size,
        )
        return marks
    }

    /** Pools every pair source's proposals, canonicalizing (smaller id as anchor) and deduping. */
    private fun collectPairs(candidates: List<Proposition>, contextId: ContextId): List<CandidatePair> {
        val seen = mutableSetOf<Pair<String, String>>()
        val pairs = mutableListOf<CandidatePair>()
        for (source in pairSources) {
            for (proposed in source.propose(candidates, contextId)) {
                val (first, second) = if (proposed.anchor.id <= proposed.member.id) {
                    proposed.anchor to proposed.member
                } else {
                    proposed.member to proposed.anchor
                }
                if (first.id == second.id) continue
                val key = first.id to second.id
                if (!seen.add(key)) continue
                pairs.add(
                    if (first === proposed.anchor) proposed else proposed.copy(anchor = first, member = second),
                )
            }
        }
        return pairs
    }

    private fun scoreAndAggregate(pair: CandidatePair, contextId: ContextId): CollectorCandidateEdge {
        val signals = scorers.mapNotNull { it.score(pair, contextId) }
        return CollectorEdgeAggregator.aggregate(pair, signals)
    }

    /** One survivor for the component; every other member is marked as its duplicate. */
    private fun markComponent(
        component: CollectorComponent,
        byId: Map<String, Proposition>,
        runId: String,
        tracing: Boolean,
    ): List<PropositionMark> {
        val members = component.memberIds.mapNotNull { byId[it] }
        if (members.size < 2) return emptyList()
        val survivor = survivorPolicy.choose(members)
        val losers = members.filter { it.id != survivor.id }

        if (tracing) {
            val retired = losers.map { loser ->
                RetiredProposition(
                    propositionId = loser.id,
                    priorStatus = loser.status,
                    foldedGrounding = loser.grounding,
                    foldedProvenanceRefs = loser.provenanceEntries.map { it.locator.key() },
                    foldedSourceIds = loser.sourceIds,
                )
            }
            traceStore.recordDecision(
                runId,
                CollectorDecision(
                    componentId = component.componentId,
                    survivorId = survivor.id,
                    action = MERGE_ACTION,
                    retired = retired,
                ),
            )
        }

        return losers.map {
            PropositionMark(
                propositionId = it.id,
                reason = MarkReason.Duplicate(survivorId = survivor.id),
                strategyName = STRATEGY_NAME,
            )
        }
    }

    companion object {
        private const val STRATEGY_NAME = "multi-signal"
        private const val MERGE_ACTION = "duplicate-merge"
    }
}
