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
package com.embabel.dice.query.taxonomy

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.taxonomy.Taxonomy
import org.jetbrains.annotations.ApiStatus

/**
 * Portable taxonomy-axis queries over proposition data.
 *
 * Version 1 loads ACTIVE propositions and derives taxonomy membership in memory so it works with
 * every [PropositionStore]. Native query push-down is a stated follow-up for stores that can support
 * it without changing this facade.
 *
 * @param store the backing proposition store
 * @param taxonomy the taxonomy used to validate category identifiers and compare their lineages
 * @param contextId optional scope; when present, queries are confined to this context
 */
@ApiStatus.Experimental
class TaxonomyQuery(
    private val store: PropositionStore,
    private val taxonomy: Taxonomy,
    private val contextId: ContextId? = null,
) {

    /**
     * Distinct valid taxonomy node identifiers evidenced by ACTIVE propositions mentioning
     * [entityId].
     *
     * An unknown entity has no categories.
     */
    @ApiStatus.Experimental
    fun categoriesOf(entityId: String): Set<String> =
        categorizedEntities()[entityId].orEmpty()

    /**
     * Entity identifiers whose evidenced categories share an ancestor with a category of
     * [entityId] within [maxLevels], excluding [entityId] itself.
     *
     * The starting category is level zero. A negative [maxLevels] is treated as zero, and an
     * unknown entity produces an empty set.
     */
    @ApiStatus.Experimental
    fun entitiesSharingAncestor(entityId: String, maxLevels: Int): Set<String> {
        val categoriesByEntity = categorizedEntities()
        val sourceCategories = categoriesByEntity[entityId].orEmpty()
        if (sourceCategories.isEmpty()) {
            return emptySet()
        }
        val levelBound = maxLevels.coerceAtLeast(0)
        return categoriesByEntity
            .asSequence()
            .filter { (candidateId) -> candidateId != entityId }
            .filter { (_, candidateCategories) ->
                sourceCategories.any { sourceCategory ->
                    candidateCategories.any { candidateCategory ->
                        taxonomy.sharedAncestor(sourceCategory, candidateCategory, levelBound) != null
                    }
                }
            }
            .mapTo(linkedSetOf()) { (candidateId) -> candidateId }
    }

    private fun categorizedEntities(): Map<String, Set<String>> {
        val query = (contextId?.let(PropositionQuery::forContextId) ?: PropositionQuery())
            .withStatus(PropositionStatus.ACTIVE)
        val categoriesByEntity = linkedMapOf<String, MutableSet<String>>()
        store.query(query).forEach { proposition ->
            val categoryId = proposition.metadata[DiceMetadataKeys.TAXONOMY_NODE] as? String
                ?: return@forEach
            if (taxonomy.node(categoryId) == null) {
                return@forEach
            }
            proposition.mentions
                .mapNotNull { it.resolvedId }
                .distinct()
                .forEach { entityId ->
                    categoriesByEntity.getOrPut(entityId) { linkedSetOf() } += categoryId
                }
        }
        return categoriesByEntity
    }
}
