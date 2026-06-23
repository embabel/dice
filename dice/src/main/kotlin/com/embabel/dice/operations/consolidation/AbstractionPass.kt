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
import com.embabel.dice.operations.PropositionGroup
import com.embabel.dice.operations.abstraction.PropositionAbstractor
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory

/**
 * Consolidation pass that synthesizes higher-level propositions from groups of related ground-level
 * propositions by delegating to an injected [PropositionAbstractor].
 *
 * This pass does NOT re-implement abstraction — it groups level-0 ACTIVE snapshot propositions by
 * resolved entity, hands each qualifying group to the abstractor, and marks the consumed sources
 * `SUPERSEDED`. It adds only pass-level concerns: snapshot filtering, an idempotency guard, and a
 * `maxLevel` cap.
 *
 * ## Why the idempotency guard exists
 *
 * Without a guard, every cycle would re-abstract the same groups, producing fresh higher-level
 * propositions whose own sources are then re-abstracted again — unbounded level inflation that never
 * settles. The guard skips a group whenever an existing higher-level proposition already covers all of
 * the group's member IDs (`sourceIds.containsAll(memberIds)`), so a re-run over an unchanged snapshot
 * is a [ConsolidationPassResult.NoOp]. The `maxLevel` cap is a secondary safeguard that drops any
 * abstraction whose resulting level exceeds the configured ceiling.
 *
 * @property abstractor The delegate that synthesizes higher-level propositions for a group.
 * @property abstractionThreshold Minimum group size before a group is eligible for abstraction.
 * @property abstractionTargetCount Desired number of abstractions per group, passed to the abstractor.
 * @property maxLevel Highest abstraction level permitted; abstractions above this are discarded.
 */
class AbstractionPass @JvmOverloads constructor(
    private val abstractor: PropositionAbstractor,
    private val abstractionThreshold: Int = 5,
    private val abstractionTargetCount: Int = 3,
    private val maxLevel: Int = 3,
) : ConsolidationPass {

    override val name: String = "abstraction"

    private val logger = LoggerFactory.getLogger(AbstractionPass::class.java)

    override fun run(contextId: ContextId, propositions: List<Proposition>): ConsolidationPassResult {
        return try {
            val level0Active = propositions.filter {
                it.status == PropositionStatus.ACTIVE && it.level == 0
            }
            val existingAbstractions = propositions.filter { it.level > 0 }

            val groups = level0Active
                .flatMap { prop -> prop.mentions.mapNotNull { it.resolvedId }.map { entityId -> entityId to prop } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, props) -> props.distinct() }
                .filter { (_, props) -> props.size >= abstractionThreshold }

            val toSave = mutableListOf<Proposition>()
            var skipped = 0
            var abstractedGroups = 0
            for ((entityId, props) in groups) {
                val memberIds = props.map { it.id }.toSet()
                // Idempotency guard: a group already covered by a higher-level proposition is not
                // re-abstracted, preventing unbounded level inflation across cycles.
                val alreadyCovered = existingAbstractions.any { it.sourceIds.toSet().containsAll(memberIds) }
                if (alreadyCovered) {
                    skipped += props.size
                    continue
                }
                val abstractions = abstractor.abstract(PropositionGroup.of(entityId, props), abstractionTargetCount)
                    .filter { it.level <= maxLevel }
                if (abstractions.isEmpty()) {
                    // Every candidate abstraction exceeded maxLevel. Don't retire the sources to
                    // SUPERSEDED with nothing to replace them — that would silently lose the facts.
                    // Skip the group and leave the sources ACTIVE.
                    skipped += props.size
                    continue
                }
                toSave += abstractions
                // withStatus preserves the contentRevised decay anchor (only metadataRevised moves).
                // Pinned sources are eviction-immune: keep them ACTIVE alongside the abstraction
                // rather than retiring them to SUPERSEDED.
                toSave += props.filter { !it.pinned }.map { it.withStatus(PropositionStatus.SUPERSEDED) }
                abstractedGroups++
            }

            logger.debug(
                "Abstraction over {} level-0 active proposition(s) for {}: {} group(s) abstracted, {} skipped (already covered or over the level cap), {} proposition(s) to save",
                level0Active.size, contextId, abstractedGroups, skipped, toSave.size,
            )
            if (toSave.isEmpty()) {
                ConsolidationPassResult.NoOp(name, "no groups above threshold, all covered, or all abstractions over the level cap")
            } else {
                ConsolidationPassResult.Changed(
                    passName = name,
                    propositionsToSave = toSave,
                    skipped = skipped,
                    summary = "abstracted $abstractedGroups groups, $skipped propositions skipped (already covered or over the level cap)",
                )
            }
        } catch (e: Throwable) {
            ConsolidationPassResult.Failed(name, e)
        }
    }
}
