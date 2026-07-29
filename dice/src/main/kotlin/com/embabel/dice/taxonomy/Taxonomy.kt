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

import org.jetbrains.annotations.ApiStatus

/**
 * A caller-authored category in a [Taxonomy].
 *
 * @param id Stable identifier used in metadata and parent links.
 * @param label Human-readable category name.
 * @param parentId Identifier of the parent category, or `null` for a root.
 * @param keywords Terms that can help a classifier recognize this category.
 */
@ApiStatus.Experimental
data class TaxonomyNode(
    val id: String,
    val label: String,
    val parentId: String? = null,
    val keywords: List<String> = emptyList(),
)

/**
 * An immutable, validated hierarchy of caller-authored categories.
 *
 * Nodes retain declaration order. Every identifier must be nonblank and unique, parent
 * links must resolve within this taxonomy, and the resulting hierarchy must be acyclic.
 */
@ApiStatus.Experimental
class Taxonomy(
    nodes: List<TaxonomyNode>,
    /**
     * Version of the category content and assignments.
     *
     * This does not version the metamodel used to shape extracted propositions.
     */
    @get:ApiStatus.Experimental
    val version: String = "1",
) {

    internal val nodes: List<TaxonomyNode> = nodes.toList()

    private val nodesById: Map<String, TaxonomyNode>

    init {
        val indexed = LinkedHashMap<String, TaxonomyNode>()
        this.nodes.forEach { node ->
            require(node.id.isNotBlank()) {
                "Taxonomy node id '${node.id}' must not be blank"
            }
            require(indexed.putIfAbsent(node.id, node) == null) {
                "Duplicate taxonomy node id '${node.id}'"
            }
        }
        nodesById = indexed

        this.nodes.forEach { node ->
            val parentId = node.parentId
            require(parentId == null || parentId in nodesById) {
                "Taxonomy node '${node.id}' has unknown parent '$parentId'"
            }
        }
        validateAcyclic()
    }

    /**
     * Find a category by its stable identifier.
     */
    @ApiStatus.Experimental
    fun node(id: String): TaxonomyNode? = nodesById[id]

    /**
     * Walk toward the root, returning the nearest parents first.
     *
     * An unknown identifier has no ancestors.
     */
    @ApiStatus.Experimental
    fun ancestors(id: String, maxLevels: Int = Int.MAX_VALUE): List<TaxonomyNode> {
        require(maxLevels >= 0) { "maxLevels must not be negative: $maxLevels" }
        val ancestors = mutableListOf<TaxonomyNode>()
        var current = nodesById[id]
        while (ancestors.size < maxLevels) {
            current = current?.parentId?.let(nodesById::get) ?: break
            ancestors += current
        }
        return ancestors
    }

    /**
     * Find the lowest category shared by both paths toward the root.
     *
     * Each starting category is included at level zero. An unknown identifier has no
     * shared ancestor.
     */
    @ApiStatus.Experimental
    fun sharedAncestor(a: String, b: String, maxLevels: Int): TaxonomyNode? {
        require(maxLevels >= 0) { "maxLevels must not be negative: $maxLevels" }
        val bLineage = lineage(b, maxLevels).mapTo(mutableSetOf()) { it.id }
        return lineage(a, maxLevels).firstOrNull { it.id in bLineage }
    }

    /**
     * Count the parent links from a category to one of its ancestors.
     *
     * @throws IllegalArgumentException when either identifier is unknown or the target
     * is not on the path to the root.
     */
    @ApiStatus.Experimental
    fun levelsTo(id: String, ancestorId: String): Int {
        var current = nodesById[id]
        var levels = 0
        while (current != null) {
            if (current.id == ancestorId) {
                return levels
            }
            current = current.parentId?.let(nodesById::get)
            levels++
        }
        throw IllegalArgumentException(
            "Taxonomy node '$ancestorId' is not an ancestor of '$id'",
        )
    }

    private fun lineage(id: String, maxLevels: Int): List<TaxonomyNode> {
        val start = nodesById[id] ?: return emptyList()
        return buildList {
            add(start)
            addAll(ancestors(id, maxLevels))
        }
    }

    private fun validateAcyclic() {
        val states = mutableMapOf<String, VisitState>()

        fun visit(id: String) {
            when (states[id]) {
                VisitState.VISITING -> throw IllegalArgumentException(
                    "Taxonomy cycle contains node '$id'",
                )

                VisitState.VISITED -> return
                null -> Unit
            }
            states[id] = VisitState.VISITING
            nodesById.getValue(id).parentId?.let(::visit)
            states[id] = VisitState.VISITED
        }

        nodes.forEach { visit(it.id) }
    }

    private enum class VisitState {
        VISITING,
        VISITED,
    }
}
