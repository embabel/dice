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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory

/**
 * A [CollectorStrategy] that finds near-duplicate propositions and marks all but the strongest
 * member of each duplicate group.
 *
 * Because clusters from [PropositionRepository.findClusters] can overlap (a proposition may
 * appear in more than one cluster), survivors are picked globally per connected component rather
 * than per cluster. Within each component the survivor is the proposition with the highest
 * effective confidence, with reinforcement count and id as tie-breakers. Every non-survivor gets
 * a [MarkReason.Duplicate] mark pointing at its component's survivor; survivors are never marked.
 *
 * Only propositions in the runner's candidate snapshot are considered — any cluster member
 * absent from `candidates` is silently ignored. This keeps every emitted mark tied to a swept
 * candidate and avoids a second read of the repository. The strategy never writes.
 *
 * Default [similarityThreshold] (0.7) and [topK] (10) match [PropositionRepository.findClusters]
 * so behavior is consistent with the repository's own clustering out of the box.
 *
 * @property similarityThreshold Minimum cosine similarity for two propositions to be clustered together.
 * @property topK Maximum number of similar members considered per cluster seed.
 */
class DuplicateCollectorStrategy @JvmOverloads constructor(
    private val similarityThreshold: Double = 0.7,
    private val topK: Int = 10,
) : CollectorStrategy {

    private val logger = LoggerFactory.getLogger(DuplicateCollectorStrategy::class.java)

    override fun mark(
        candidates: List<Proposition>,
        repository: PropositionRepository,
        contextId: ContextId,
    ): List<PropositionMark> {
        val byId = candidates.associateBy { it.id }
        val clusters = repository.findClusters(
            similarityThreshold = similarityThreshold,
            topK = topK,
            query = PropositionQuery.forContextId(contextId).withStatus(PropositionStatus.ACTIVE),
        )

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

        // Group candidate members by their component root.
        val componentMembers: Map<String, List<Proposition>> = unionFind.members()
            .mapNotNull { id -> byId[id] }
            .groupBy { unionFind.find(it.id) }

        val marks = componentMembers.values
            .filter { it.size >= 2 }
            .flatMap { members ->
                // Global survivor per component: max effectiveConfidence, then reinforceCount,
                // then a stable id tie-break for full determinism on ties.
                val survivor = members.maxWith(
                    compareBy<Proposition>({ it.effectiveConfidence() }, { it.reinforceCount }, { it.id }),
                )
                members
                    .filter { it.id != survivor.id }
                    .map {
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
    }
}
