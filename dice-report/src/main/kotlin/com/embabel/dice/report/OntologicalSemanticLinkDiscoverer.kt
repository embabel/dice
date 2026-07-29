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
package com.embabel.dice.report

import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.taxonomy.Taxonomy
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory

/**
 * Discovers adjacency-invisible entity links grounded in a shared taxonomy ancestor.
 *
 * Category assignments come only from ACTIVE propositions. A candidate is suppressed
 * when its endpoints are directly co-mentioned or share an entity neighbour.
 */
@ApiStatus.Experimental
class OntologicalSemanticLinkDiscoverer(
    private val taxonomy: Taxonomy,
    private val maxAncestorLevels: Int = 2,
) : SemanticLinkDiscoverer {

    private val logger = LoggerFactory.getLogger(OntologicalSemanticLinkDiscoverer::class.java)

    init {
        require(maxAncestorLevels >= 0) {
            "maxAncestorLevels must not be negative: $maxAncestorLevels"
        }
    }

    @ApiStatus.Experimental
    override fun discover(propositions: List<Proposition>): List<SemanticLink> {
        val active = propositions.filter { it.status == PropositionStatus.ACTIVE }
        logger.debug(
            "Ontological discovery: {} proposition(s) in ({} active)",
            propositions.size,
            active.size,
        )

        val categoryEvidence = categoryEvidence(active)
        val (directPairs, neighbours) = adjacency(propositions)
        val entities = categoryEvidence.keys.sorted()
        val links = mutableListOf<SemanticLink>()

        for (i in entities.indices) {
            for (j in i + 1 until entities.size) {
                val (a, b) = canonical(entities[i], entities[j])
                if ((a to b) in directPairs) continue
                if (neighbours[a].orEmpty().intersect(neighbours[b].orEmpty()).isNotEmpty()) continue

                val connection = bestConnection(
                    categoryEvidence.getValue(a),
                    categoryEvidence.getValue(b),
                ) ?: continue
                links += SemanticLink(
                    sourceEntityId = a,
                    targetEntityId = b,
                    connectingEntityIds = emptyList(),
                    kind = LinkKind.INFERRED,
                    sourcePropositionIds = (
                        categoryEvidence.getValue(a).getValue(connection.categoryA) +
                            categoryEvidence.getValue(b).getValue(connection.categoryB)
                        ).sorted(),
                    reviewStatus = ReviewStatus.CANDIDATE,
                    confidence = 1.0 / (1 + maxOf(connection.levelsA, connection.levelsB)),
                    rationale = "shared taxonomy ancestor '${connection.ancestorLabel}' " +
                        "($a via ${connection.categoryA}, $b via ${connection.categoryB})",
                )
            }
        }

        val result = links.sortedWith(
            compareBy(
                { it.sourceEntityId },
                { it.targetEntityId },
            ),
        )
        logger.debug("Ontological discovery: {} indirect link(s) found", result.size)
        return result
    }

    private fun categoryEvidence(
        active: List<Proposition>,
    ): Map<String, Map<String, Set<String>>> {
        val evidence = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
        for (proposition in active) {
            val categoryId = proposition.metadata[DiceMetadataKeys.TAXONOMY_NODE] as? String ?: continue
            for (entityId in proposition.mentions.mapNotNull { it.resolvedId }.distinct()) {
                evidence
                    .getOrPut(entityId) { linkedMapOf() }
                    .getOrPut(categoryId) { linkedSetOf() }
                    .add(proposition.id)
            }
        }
        return evidence
    }

    private fun adjacency(
        propositions: List<Proposition>,
    ): Pair<Set<Pair<String, String>>, Map<String, Set<String>>> {
        val directPairs = linkedSetOf<Pair<String, String>>()
        val neighbours = linkedMapOf<String, MutableSet<String>>()
        for (proposition in propositions) {
            val ids = proposition.mentions.mapNotNull { it.resolvedId }.distinct()
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val (a, b) = canonical(ids[i], ids[j])
                    directPairs += a to b
                    neighbours.getOrPut(a) { linkedSetOf() }.add(b)
                    neighbours.getOrPut(b) { linkedSetOf() }.add(a)
                }
            }
        }
        return directPairs to neighbours
    }

    private fun bestConnection(
        categoriesA: Map<String, Set<String>>,
        categoriesB: Map<String, Set<String>>,
    ): AncestryConnection? = categoriesA.keys
        .flatMap { categoryA ->
            categoriesB.keys.mapNotNull { categoryB ->
                val ancestor = taxonomy.sharedAncestor(categoryA, categoryB, maxAncestorLevels)
                    ?: return@mapNotNull null
                AncestryConnection(
                    categoryA = categoryA,
                    categoryB = categoryB,
                    ancestorId = ancestor.id,
                    ancestorLabel = ancestor.label,
                    levelsA = taxonomy.levelsTo(categoryA, ancestor.id),
                    levelsB = taxonomy.levelsTo(categoryB, ancestor.id),
                )
            }
        }
        .minWithOrNull(
            compareBy(
                { maxOf(it.levelsA, it.levelsB) },
                { it.levelsA + it.levelsB },
                { it.ancestorId },
                { it.categoryA },
                { it.categoryB },
            ),
        )

    private fun canonical(x: String, y: String): Pair<String, String> =
        if (x <= y) x to y else y to x

    private data class AncestryConnection(
        val categoryA: String,
        val categoryB: String,
        val ancestorId: String,
        val ancestorLabel: String,
        val levelsA: Int,
        val levelsB: Int,
    )
}
