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
package com.embabel.dice.projection.memory

import com.embabel.agent.rag.service.Cluster
import com.embabel.dice.projection.memory.collector.CollectorRunContext
import com.embabel.dice.projection.memory.collector.retiredByFold
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorComponent
import com.embabel.dice.spi.CollectorDecision
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.spi.CollectorTraceStore
import com.embabel.dice.spi.MarkReason
import com.embabel.dice.spi.PropositionMark
import org.slf4j.LoggerFactory

/**
 * A [CollectorStrategy] that finds near-duplicate propositions and marks all but the strongest
 * member of each duplicate group.
 *
 * Clusters from [PropositionRepository.findClusters] can overlap (a proposition may appear in more
 * than one cluster), so survivors are picked once per connected component across all clusters.
 * Within each component the survivor is the proposition with the highest effective confidence,
 * with reinforcement count and id as tie-breakers. Every non-survivor gets a
 * [MarkReason.Duplicate] mark pointing at its component's survivor; survivors are never marked.
 *
 * Only propositions in the runner's candidate snapshot are considered: any cluster member absent
 * from `candidates` is silently ignored. This keeps every emitted mark tied to a swept candidate
 * and avoids a second read of the repository. The strategy never writes to the repository.
 *
 * Given a [traceStore] and a real run (a non-blank [CollectorRunContext.runId]), each component
 * that collapses is also written down as a [CollectorDecision]: the survivor, and for every loser
 * the grounding, provenance and source ids the merging sweep will fold onto the survivor from it.
 * That record is what [com.embabel.dice.spi.undoSingleCollapse] reads to reverse a collapse, so a
 * sweep run without a trace store cannot be undone afterwards. The similarity edges and the
 * components are recorded beside it, for inspection. Trace writes are best effort: a failure is
 * logged and the marks are still returned.
 *
 * Default [similarityThreshold] (0.7) and [topK] (10) match [PropositionRepository.findClusters]
 * so behavior is consistent with the repository's own clustering out of the box.
 *
 * @property similarityThreshold Minimum cosine similarity for two propositions to be clustered together.
 * @property topK Maximum number of similar members considered per cluster seed.
 * @property traceStore Where collapse decisions are recorded. Null records nothing.
 */
class DuplicateCollectorStrategy @JvmOverloads constructor(
    private val similarityThreshold: Double = 0.7,
    private val topK: Int = 10,
    private val traceStore: CollectorTraceStore? = null,
) : RunAwareCollectorStrategy {

    private val logger = LoggerFactory.getLogger(DuplicateCollectorStrategy::class.java)

    override fun mark(
        candidates: List<Proposition>,
        repository: PropositionRepository,
        ctx: CollectorRunContext,
    ): List<PropositionMark> {
        val byId = candidates.associateBy { it.id }
        val contextId = ctx.contextId
        // A blank runId is the mark-only collect() path or the legacy bridge: nothing written under
        // it could be looked up again, so no trace is kept for it.
        val trace = traceStore?.takeIf { ctx.runId.isNotBlank() }
        val runId = ctx.runId
        if (trace != null) record("recordRunContext") { trace.recordRunContext(runId, contextId) }

        val clusters = repository.findClusters(
            similarityThreshold = similarityThreshold,
            topK = topK,
            query = PropositionQuery.forContextId(contextId).withStatus(PropositionStatus.ACTIVE),
        )
        if (trace != null) record("recordCandidateEdges") { trace.recordCandidateEdges(runId, edgesFrom(clusters, byId)) }

        // Build connected components over clustered members, restricted to the runner-supplied
        // candidate snapshot so every mark maps to a swept candidate.
        val unionFind = UnionFind()
        for (cluster in clusters) {
            val memberIds = (listOf(cluster.anchor.id) + cluster.similar.map { it.match.id })
                .filter { byId.containsKey(it) }
                .distinct()
            // Union all members of this cluster into one component.
            for (i in 1 until memberIds.size) {
                unionFind.union(memberIds[0], memberIds[i])
            }
        }

        // Group candidate members by their component root; the root id names the component.
        val componentMembers: Map<String, List<Proposition>> = unionFind.members()
            .mapNotNull { id -> byId[id] }
            .groupBy { unionFind.find(it.id) }
        if (trace != null) {
            record("recordComponents") {
                trace.recordComponents(
                    runId,
                    componentMembers.map { (componentId, members) -> CollectorComponent(componentId, members.map { it.id }) },
                )
            }
        }

        val marks = componentMembers.entries
            .filter { it.value.size >= 2 }
            .flatMap { (componentId, members) ->
                // Global survivor per component: max effectiveConfidence, then reinforceCount,
                // then a stable id tie-break for full determinism on ties.
                val survivor = members.maxWith(
                    compareBy<Proposition>({ it.effectiveConfidence() }, { it.reinforceCount }, { it.id }),
                )
                val losers = members.filter { it.id != survivor.id }
                if (trace != null) {
                    record("recordDecision") {
                        trace.recordDecision(
                            runId,
                            CollectorDecision(
                                runId = runId,
                                componentId = componentId,
                                survivorId = survivor.id,
                                action = MERGE_ACTION,
                                retired = losers.map { retiredByFold(it, survivor) },
                            ),
                        )
                    }
                }
                losers.map {
                    PropositionMark(
                        propositionId = it.id,
                        reason = MarkReason.Duplicate(survivorId = survivor.id),
                        strategyName = STRATEGY_NAME,
                    )
                }
            }
            // Each non-survivor belongs to exactly one component, but dedup defensively so a
            // proposition is never marked more than once across overlapping clusters.
            .distinctBy { it.propositionId }
            .sortedBy { it.propositionId }
        logger.debug(
            "DuplicateCollectorStrategy: {} cluster(s) -> {} duplicate mark(s) from {} candidate(s)",
            clusters.size, marks.size, candidates.size,
        )
        return marks
    }

    /**
     * Runs a trace-store write and keeps going if it fails. The trace is for inspecting and undoing
     * a run later; the marks are the product, and a store hiccup must not abort the sweep.
     */
    private fun record(what: String, block: () -> Unit) {
        runCatching(block).onFailure { e ->
            logger.warn("DuplicateCollectorStrategy: trace store write '{}' failed, continuing run", what, e)
        }
    }

    /**
     * One edge per clustered pair that reached a candidate, carrying the repository's cosine score
     * as its single signal. The anchor is kept even when it is outside the candidate snapshot: the
     * union-find above still groups that cluster's candidate members through it, so the edge is
     * what explains the component. Pairs are canonicalized (smaller id as anchor) and deduped, so a
     * pair found from both ends is recorded once.
     */
    private fun edgesFrom(
        clusters: List<Cluster<Proposition>>,
        byId: Map<String, Proposition>,
    ): List<CollectorCandidateEdge> = clusters
        .flatMap { cluster ->
            cluster.similar
                .filter { byId.containsKey(it.match.id) && it.match.id != cluster.anchor.id }
                .map { similar ->
                    val (anchorId, memberId) =
                        if (cluster.anchor.id <= similar.match.id) cluster.anchor.id to similar.match.id
                        else similar.match.id to cluster.anchor.id
                    CollectorCandidateEdge(
                        anchorId = anchorId,
                        memberId = memberId,
                        aggregateScore = similar.score,
                        vetoed = false,
                        signals = listOf(CollectorSignalScore(signal = COSINE_SIGNAL, score = similar.score)),
                    )
                }
        }
        .distinctBy { it.anchorId to it.memberId }

    /**
     * Simple union-find over proposition ids, used to merge overlapping clusters into
     * connected components so one global survivor can be chosen per component.
     */
    private class UnionFind {
        private val parent = mutableMapOf<String, String>()

        fun find(id: String): String {
            parent.getOrPut(id) { id }
            var root = id
            while (parent.getValue(root) != root) {
                root = parent.getValue(root)
            }
            // Path compression: point every node on the walk directly at the root.
            var cur = id
            while (cur != root) {
                val next = parent.getValue(cur)
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: String, b: String) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) {
                // Deterministic merge direction (smaller id becomes root).
                if (rootA <= rootB) parent[rootB] = rootA else parent[rootA] = rootB
            }
        }

        fun members(): Set<String> = parent.keys.toSet()
    }

    companion object {
        private const val STRATEGY_NAME = "duplicate"

        /** Same action name the multi-signal strategy records, so trace readers see one vocabulary. */
        private const val MERGE_ACTION = "duplicate-merge"
        private const val COSINE_SIGNAL = "cosine"
    }
}
