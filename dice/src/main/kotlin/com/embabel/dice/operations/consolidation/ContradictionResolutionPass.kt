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
package com.embabel.dice.operations.consolidation

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.revision.PropositionRelation
import com.embabel.dice.proposition.revision.PropositionReviser

/**
 * Consolidation pass that resolves contradictions among ACTIVE propositions by delegating
 * classification to an injected [PropositionReviser] and retiring the losing side of each conflict.
 *
 * This pass does NOT re-implement contradiction detection. For each ACTIVE proposition it asks
 * `reviser.classify()` to compare it against its entity-overlapping ACTIVE peers; for every pair the
 * reviser reports as [PropositionRelation.CONTRADICTORY], the weaker member (lower
 * [Proposition.effectiveConfidence]; ties favor the proposition being classified) is transitioned to
 * [PropositionStatus.CONTRADICTED].
 *
 * Classification uses [PropositionReviser.classify] — NOT a contraster — because resolving a
 * contradiction is a lifecycle transition, not an articulation of differences.
 *
 * ## Idempotency
 *
 * Only ACTIVE propositions are compared and only an ACTIVE loser is transitioned, so once a conflict
 * is resolved the retired proposition drops out of the candidate set and is never re-classified. A
 * re-run over an already-resolved snapshot therefore yields [ConsolidationPassResult.NoOp]. Candidates
 * are pruned to those sharing at least one resolved entity, bounding `classify()` fan-out.
 *
 * @property reviser The delegate that classifies the relation between propositions.
 */
class ContradictionResolutionPass @JvmOverloads constructor(
    private val reviser: PropositionReviser,
) : ConsolidationPass {

    override val name: String = "contradiction-resolution"

    override fun run(contextId: ContextId, propositions: List<Proposition>): ConsolidationPassResult {
        return try {
            val active = propositions.filter { it.status == PropositionStatus.ACTIVE }
            val toSave = mutableListOf<Proposition>()
            // Each unordered pair is resolved once: a symmetric reviser reports the same conflict
            // from both sides, and resolving both would retire both members and leave no survivor.
            val resolvedPairs = mutableSetOf<Pair<String, String>>()
            for (p in active) {
                val candidates = active.filter { it.id != p.id && sharesEntity(it, p) }
                val classified = reviser.classify(p, candidates)
                classified
                    .filter { it.relation == PropositionRelation.CONTRADICTORY }
                    .forEach { c ->
                        val pairKey =
                            if (p.id <= c.proposition.id) p.id to c.proposition.id
                            else c.proposition.id to p.id
                        if (!resolvedPairs.add(pairKey)) return@forEach
                        // The lower effective confidence loses; a tie favors the proposition being
                        // classified (p), so only the candidate is retired in that case.
                        val weaker =
                            if (p.effectiveConfidence() < c.proposition.effectiveConfidence()) p
                            else c.proposition
                        if (weaker.status == PropositionStatus.ACTIVE) {
                            // withStatus preserves the contentRevised decay anchor.
                            toSave += weaker.withStatus(PropositionStatus.CONTRADICTED)
                        }
                    }
            }
            val deduped = toSave.distinctBy { it.id }
            if (deduped.isEmpty()) {
                ConsolidationPassResult.NoOp(name, "no contradictions found")
            } else {
                ConsolidationPassResult.Changed(
                    passName = name,
                    propositionsToSave = deduped,
                    summary = "resolved ${deduped.size} contradictory propositions to CONTRADICTED",
                )
            }
        } catch (e: Throwable) {
            ConsolidationPassResult.Failed(name, e)
        }
    }

    /** True when [a] and [b] share at least one resolved entity. */
    private fun sharesEntity(a: Proposition, b: Proposition): Boolean {
        val aEntities = a.mentions.mapNotNull { it.resolvedId }.toSet()
        if (aEntities.isEmpty()) return false
        return b.mentions.any { it.resolvedId != null && it.resolvedId in aEntities }
    }
}
