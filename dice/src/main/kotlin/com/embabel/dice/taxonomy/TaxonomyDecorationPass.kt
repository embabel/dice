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
package com.embabel.dice.taxonomy

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.operations.consolidation.ConsolidationPass
import com.embabel.dice.operations.consolidation.ConsolidationPassResult
import com.embabel.dice.proposition.Proposition
import org.jetbrains.annotations.ApiStatus

/**
 * Decorates propositions with classifications from a caller-provided [Taxonomy].
 *
 * Already-current classifications are left untouched. The pass reports decorated copies
 * for the consolidation orchestrator to save and never writes to a repository directly.
 */
@ApiStatus.Experimental
class TaxonomyDecorationPass(
    private val taxonomy: Taxonomy,
    private val classifier: TaxonomyClassifier,
) : ConsolidationPass {

    @get:ApiStatus.Experimental
    override val name: String = "taxonomy"

    @ApiStatus.Experimental
    override fun run(
        contextId: ContextId,
        propositions: List<Proposition>,
    ): ConsolidationPassResult {
        var skipped = 0
        val decorated = buildList {
            propositions.forEach { proposition ->
                if (proposition.isCurrentDecoration()) {
                    skipped++
                    return@forEach
                }

                val nodeId = classifier.classify(proposition, taxonomy)
                if (nodeId == null || taxonomy.node(nodeId) == null) {
                    skipped++
                    return@forEach
                }

                add(
                    proposition
                        .withMetadataValue(DiceMetadataKeys.TAXONOMY_NODE, nodeId)
                        .withMetadataValue(DiceMetadataKeys.TAXONOMY_VERSION, taxonomy.version),
                )
            }
        }

        return if (decorated.isEmpty()) {
            ConsolidationPassResult.NoOp(
                passName = name,
                reason = "No taxonomy decorations changed",
            )
        } else {
            ConsolidationPassResult.Changed(
                passName = name,
                propositionsToSave = decorated,
                skipped = skipped,
                summary = "Decorated ${decorated.size} propositions",
            )
        }
    }

    private fun Proposition.isCurrentDecoration(): Boolean =
        metadata.containsKey(DiceMetadataKeys.TAXONOMY_NODE) &&
            metadata[DiceMetadataKeys.TAXONOMY_VERSION] == taxonomy.version
}
