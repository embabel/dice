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

import com.embabel.dice.common.DiceMetadataKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaxonomyTest {

    private val root = TaxonomyNode(id = "work", label = "Work")
    private val engineering = TaxonomyNode(
        id = "engineering",
        label = "Engineering",
        parentId = root.id,
    )
    private val product = TaxonomyNode(
        id = "product",
        label = "Product",
        parentId = root.id,
    )
    private val platform = TaxonomyNode(
        id = "platform",
        label = "Platform",
        parentId = engineering.id,
    )
    private val roadmap = TaxonomyNode(
        id = "roadmap",
        label = "Roadmap",
        parentId = product.id,
    )

    @Test
    fun `walks ancestors nearest first and respects the level bound`() {
        val taxonomy = taxonomy()

        assertEquals(listOf(engineering, root), taxonomy.ancestors(platform.id))
        assertEquals(listOf(engineering), taxonomy.ancestors(platform.id, maxLevels = 1))
        assertEquals(emptyList<TaxonomyNode>(), taxonomy.ancestors(platform.id, maxLevels = 0))
        assertEquals(2, taxonomy.levelsTo(platform.id, root.id))
        assertEquals(0, taxonomy.levelsTo(platform.id, platform.id))
    }

    @Test
    fun `finds the nearest shared ancestor within each node's level bound`() {
        val taxonomy = taxonomy()

        assertEquals(root, taxonomy.sharedAncestor(platform.id, roadmap.id, maxLevels = 2))
        assertEquals(engineering, taxonomy.sharedAncestor(platform.id, engineering.id, maxLevels = 1))
        assertNull(taxonomy.sharedAncestor(platform.id, roadmap.id, maxLevels = 1))
    }

    @Test
    fun `preserves declaration order and supports exact lookup`() {
        val declared = listOf(root, product, roadmap, engineering, platform)
        val taxonomy = Taxonomy(declared)

        assertEquals(declared, taxonomy.nodes)
        assertEquals(roadmap, taxonomy.node(roadmap.id))
        assertNull(taxonomy.node("unknown"))
    }

    @Test
    fun `rejects a two node cycle with an offending id`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Taxonomy(
                listOf(
                    TaxonomyNode(id = "a", label = "A", parentId = "b"),
                    TaxonomyNode(id = "b", label = "B", parentId = "a"),
                ),
            )
        }

        assertEquals("Taxonomy cycle contains node 'a'", failure.message)
    }

    @Test
    fun `rejects an unknown parent with the offending node id`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Taxonomy(
                listOf(
                    TaxonomyNode(id = "orphan", label = "Orphan", parentId = "missing"),
                ),
            )
        }

        assertEquals("Taxonomy node 'orphan' has unknown parent 'missing'", failure.message)
    }

    @Test
    fun `rejects blank and duplicate ids`() {
        val blank = assertThrows(IllegalArgumentException::class.java) {
            Taxonomy(listOf(TaxonomyNode(id = " ", label = "Blank")))
        }
        val duplicate = assertThrows(IllegalArgumentException::class.java) {
            Taxonomy(
                listOf(
                    TaxonomyNode(id = "same", label = "First"),
                    TaxonomyNode(id = "same", label = "Second"),
                ),
            )
        }

        assertEquals("Taxonomy node id ' ' must not be blank", blank.message)
        assertEquals("Duplicate taxonomy node id 'same'", duplicate.message)
    }

    @Test
    fun `keeps taxonomy content version separate from extraction shape version`() {
        assertEquals("1", taxonomy().version)
        assertEquals("customer-categories-v2", taxonomy(version = "customer-categories-v2").version)
        assertEquals("dice.taxonomy.node", DiceMetadataKeys.TAXONOMY_NODE)
        assertEquals("dice.taxonomy.version", DiceMetadataKeys.TAXONOMY_VERSION)
        assertNotEquals(DiceMetadataKeys.METAMODEL_VERSION, DiceMetadataKeys.TAXONOMY_VERSION)
    }

    @Test
    fun `throws when the requested target is not an ancestor`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            taxonomy().levelsTo(platform.id, product.id)
        }

        assertTrue(failure.message.orEmpty().contains(product.id))
    }

    private fun taxonomy(version: String = "1") = Taxonomy(
        nodes = listOf(root, engineering, product, platform, roadmap),
        version = version,
    )
}
