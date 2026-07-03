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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.CandidatePair
import com.embabel.dice.spi.CandidatePairSource

/**
 * Proposes candidate pairs from the repository's own vector clustering, the same recall path
 * [com.embabel.dice.projection.memory.DuplicateCollectorStrategy] uses today.
 *
 * Runs [PropositionRepository.findClusters] and turns every anchor/member edge into a
 * [CandidatePair] carrying the cluster's cosine as [CandidatePair.proposalScore], so a
 * [VectorSignalScorer] downstream can reuse it instead of re-embedding. Only pairs where both
 * sides are in the runner's candidate snapshot are proposed — a cluster member outside that
 * snapshot is silently dropped, mirroring the `byId` filter in `DuplicateCollectorStrategy`.
 *
 * @property similarityThreshold cosine floor handed to [PropositionRepository.findClusters].
 *   Defaults to 0.7, matching the dice default so recall is consistent out of the box.
 * @property topK maximum similar members considered per cluster seed, passed straight through.
 */
class VectorCandidatePairSource(
    private val repository: PropositionRepository,
    private val similarityThreshold: Double = 0.7,
    private val topK: Int = 10,
) : CandidatePairSource {

    override fun propose(candidates: List<Proposition>, contextId: ContextId): List<CandidatePair> {
        val byId = candidates.associateBy { it.id }
        val clusters = repository.findClusters(
            similarityThreshold = similarityThreshold,
            topK = topK,
            query = PropositionQuery.forContextId(contextId).withStatus(PropositionStatus.ACTIVE),
        )

        // Canonicalize (smaller id as anchor) and dedupe: findClusters already anchors on the
        // smaller id and never emits symmetric duplicates, but we don't want to depend on that
        // guarantee here, so we re-derive and dedupe defensively.
        val seen = mutableSetOf<Pair<String, String>>()
        val pairs = mutableListOf<CandidatePair>()
        for (cluster in clusters) {
            val anchor = byId[cluster.anchor.id] ?: continue
            for (result in cluster.similar) {
                val member = byId[result.match.id] ?: continue
                if (anchor.id == member.id) continue
                val (first, second) = if (anchor.id <= member.id) anchor to member else member to anchor
                val key = first.id to second.id
                if (!seen.add(key)) continue
                pairs.add(CandidatePair(anchor = first, member = second, proposalScore = result.score))
            }
        }
        return pairs
    }
}
