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
import com.embabel.dice.provenance.ProvenanceEvidenceKey
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
 *    above [matchThreshold]; because this unions transitively, two members can end up in the
 *    same component even when their own direct pair was never proposed (A~B, B~C puts A and C
 *    together even if no source ever proposed (A,C)), or even when it *was* proposed and scored
 *    but vetoed - a veto only drops that one edge from the union, it doesn't stop A and C landing
 *    in the same component via bridging B. So before survivors are picked, every such never-scored
 *    or vetoed intra-component pair is treated as a cannot-cohabit constraint and one side of any
 *    contradicting pair is vetoed out of the component (see `resolveContradictions`);
 * 4. each surviving component of size 2+ gets one survivor (via [survivorPolicy]); everyone else
 *    in the component is marked [MarkReason.Duplicate] pointing at the survivor.
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
     * Reused directly for the component-level contradiction guard below — see [resolveContradictions].
     * Not necessarily the same instance as any [PolarityVetoSignalScorer] in [scorers]; it only ever
     * abstains or vetoes, so calling it twice on an already-scored pair is harmless.
     */
    private val polarityGuard = PolarityVetoSignalScorer()

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
        if (tracing) trace("recordRunContext") { traceStore.recordRunContext(ctx.runId, contextId) }

        val pairs = collectPairs(candidates, contextId)
        val edges = pairs.map { pair -> scoreAndAggregate(pair, contextId) }
        if (tracing) trace("recordCandidateEdges") { traceStore.recordCandidateEdges(ctx.runId, edges) }

        val eligibleEdges = edges.filter { !it.vetoed && it.aggregateScore >= matchThreshold }
        val candidateIds = candidates.map { it.id }.toSet()
        val componentByPropositionId = componentsFinder.findComponents(ctx.runId, candidateIds, eligibleEdges)

        val components = componentByPropositionId.entries
            .groupBy({ it.value }, { it.key })
            .map { (componentId, memberIds) -> CollectorComponent(componentId, memberIds) }
        if (tracing) trace("recordComponents") { traceStore.recordComponents(ctx.runId, components) }

        // A component can chain propositions that were never directly compared to each other -
        // e.g. A~B and B~C union A and C into one component even though no pair source ever
        // proposed (A,C). Before we pick a survivor, every intra-component pair is treated as a
        // cannot-cohabit constraint if either: (a) it was proposed, scored, and vetoed at the edge
        // stage - a component union only removes that one edge, not the two ids from the same
        // component via a bridge, so a vetoed pair is still a live contradiction here; or (b) it
        // was never scored at all and the guard's own polarity check flags it. Pairs that were
        // scored and NOT vetoed are the only ones already known safe and get skipped.
        val edgeByPairKey = edges.associateBy { pairKey(it.anchorId, it.memberId) }
        val vetoedPairKeys = edgeByPairKey.filterValues { it.vetoed }.keys
        val safeScoredPairKeys = edgeByPairKey.filterValues { !it.vetoed }.keys

        val resolvedComponents = components.mapNotNull { component ->
            resolveContradictions(component, byId, safeScoredPairKeys, vetoedPairKeys, eligibleEdges, contextId)
        }

        val marks = resolvedComponents
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

    /**
     * Runs a trace-store write best-effort: trace/audit data is observability, not the product,
     * so a failure here (a transient Neo4j hiccup, a constraint violation) is logged and swallowed
     * rather than aborting the collector run.
     */
    private fun trace(what: String, block: () -> Unit) {
        runCatching(block).onFailure { e ->
            logger.warn("MultiSignalCollectorStrategy: trace store write '{}' failed, continuing run", what, e)
        }
    }

    /** Pools every pair source's proposals, canonicalizing (smaller id as anchor) and deduping. */
    private fun collectPairs(candidates: List<Proposition>, contextId: ContextId): List<CandidatePair> {
        return pairSources
            .flatMap { it.propose(candidates, contextId) }
            .mapNotNull { proposed ->
                val (first, second) = if (proposed.anchor.id <= proposed.member.id) {
                    proposed.anchor to proposed.member
                } else {
                    proposed.member to proposed.anchor
                }
                if (first.id == second.id) return@mapNotNull null
                if (first === proposed.anchor) proposed else proposed.copy(anchor = first, member = second)
            }
            .distinctBy { it.anchor.id to it.member.id }
    }

    private fun scoreAndAggregate(pair: CandidatePair, contextId: ContextId): CollectorCandidateEdge {
        val signals = scorers.mapNotNull { it.score(pair, contextId) }
        return CollectorEdgeAggregator.aggregate(pair, signals)
    }

    private fun pairKey(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a

    /**
     * Guards one component against contradictions that only show up once ids are chained
     * together transitively (never scored as a direct edge), and against pairs that *were*
     * proposed and scored but vetoed at the edge stage - a veto only drops that one edge from
     * [componentsFinder]'s input, it doesn't stop the two ids from still landing in the same
     * component via a bridging third member, so those pairs remain live cannot-cohabit
     * constraints here too. Repeatedly finds such a pair among the component's current members
     * and vetoes one side of it (fewer surviving edges within the current membership loses,
     * lowest id wins a tie) until none remain. Surviving-edge counts are recomputed after every
     * eviction so later rounds in a multi-contradiction component judge against the current
     * membership, not the pre-guard graph. Returns null if the component drops below 2 members
     * along the way, meaning it should be skipped entirely.
     *
     * Skips the check (and just returns the component unchanged) once a component is bigger than
     * [MAX_CONTRADICTION_CHECK_MEMBERS] members, since the check is quadratic in component size
     * and components this large are already unusual.
     */
    private fun resolveContradictions(
        component: CollectorComponent,
        byId: Map<String, Proposition>,
        safeScoredPairKeys: Set<Pair<String, String>>,
        vetoedPairKeys: Set<Pair<String, String>>,
        eligibleEdges: List<CollectorCandidateEdge>,
        contextId: ContextId,
    ): CollectorComponent? {
        if (component.memberIds.size > MAX_CONTRADICTION_CHECK_MEMBERS) {
            logger.warn(
                "MultiSignalCollectorStrategy: component {} has {} member(s), skipping the " +
                    "transitive contradiction guard above {} member(s)",
                component.componentId, component.memberIds.size, MAX_CONTRADICTION_CHECK_MEMBERS,
            )
            return component
        }

        var members = component.memberIds
        while (true) {
            val contradiction = findContradiction(members, byId, safeScoredPairKeys, vetoedPairKeys, contextId)
                ?: break
            val (a, b) = contradiction
            val edgeCounts = surviveEdgeCounts(members, eligibleEdges)
            val loser = pickLoser(a, b, edgeCounts)
            logger.warn(
                "MultiSignalCollectorStrategy: component {} - {} and {} assert opposite polarity " +
                    "about a shared entity (directly vetoed or via a transitive chain); vetoing {} out of the merge",
                component.componentId, a, b, loser,
            )
            members = members.filterNot { it == loser }
        }

        if (members.size < 2) return null
        return if (members.size == component.memberIds.size) component else component.copy(memberIds = members)
    }

    /**
     * First pair among [members] that is a cannot-cohabit constraint: either it was proposed,
     * scored, and vetoed at the edge stage, or it was never scored at all and the guard's own
     * polarity check flags it now. Pairs already known safe ([safeScoredPairKeys] - scored and
     * not vetoed) are skipped.
     */
    private fun findContradiction(
        members: List<String>,
        byId: Map<String, Proposition>,
        safeScoredPairKeys: Set<Pair<String, String>>,
        vetoedPairKeys: Set<Pair<String, String>>,
        contextId: ContextId,
    ): Pair<String, String>? {
        for (i in members.indices) {
            for (j in i + 1 until members.size) {
                val a = members[i]
                val b = members[j]
                val key = pairKey(a, b)
                if (key in vetoedPairKeys) return a to b
                if (key in safeScoredPairKeys) continue
                val anchor = byId[a] ?: continue
                val member = byId[b] ?: continue
                val score = polarityGuard.score(CandidatePair(anchor, member), contextId)
                if (score?.veto == true) return a to b
            }
        }
        return null
    }

    /** Degree of each of [members] within the eligible edges connecting only current [members]. */
    private fun surviveEdgeCounts(
        members: List<String>,
        eligibleEdges: List<CollectorCandidateEdge>,
    ): Map<String, Int> {
        val memberSet = members.toSet()
        return eligibleEdges
            .filter { it.anchorId in memberSet && it.memberId in memberSet }
            .flatMap { listOf(it.anchorId, it.memberId) }
            .groupingBy { it }
            .eachCount()
    }

    /** The member to drop from a contradicting pair: fewer surviving edges loses; ties keep the lower id. */
    private fun pickLoser(a: String, b: String, edgeCounts: Map<String, Int>): String {
        val countA = edgeCounts[a] ?: 0
        val countB = edgeCounts[b] ?: 0
        return when {
            countA > countB -> b
            countB > countA -> a
            else -> maxOf(a, b)
        }
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
            // Record only what each loser actually ADDS to the survivor, not its whole evidence
            // set: absorbEvidence is a deduplicating union, so any ref the survivor already owned
            // pre-merge (common for near-duplicates from the same source) must not be recorded as
            // "folded", or undo would later strip evidence the survivor held independently.
            val survivorGrounding = survivor.grounding.toSet()
            val survivorProvenanceRefs = survivor.provenanceEntries
                .map(ProvenanceEvidenceKey::encode)
                .toSet()
            val survivorSourceIds = survivor.sourceIds.toSet()
            val retired = losers.map { loser ->
                RetiredProposition(
                    propositionId = loser.id,
                    priorStatus = loser.status,
                    foldedGrounding = loser.grounding - survivorGrounding,
                    foldedProvenanceRefs = loser.provenanceEntries
                        .map(ProvenanceEvidenceKey::encode)
                        .distinct() - survivorProvenanceRefs,
                    foldedSourceIds = loser.sourceIds - survivorSourceIds,
                )
            }
            trace("recordDecision") {
                traceStore.recordDecision(
                    runId,
                    CollectorDecision(
                        runId = runId,
                        componentId = component.componentId,
                        survivorId = survivor.id,
                        action = MERGE_ACTION,
                        retired = retired,
                    ),
                )
            }
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

        /**
         * Above this many members, the transitive contradiction guard is skipped for a component
         * (it's quadratic in member count and components this large are already anomalous).
         */
        private const val MAX_CONTRADICTION_CHECK_MEMBERS = 32
    }
}
