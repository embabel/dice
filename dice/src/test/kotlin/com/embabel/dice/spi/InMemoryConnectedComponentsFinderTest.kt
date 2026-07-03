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
package com.embabel.dice.spi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class InMemoryConnectedComponentsFinderTest {

    private val finder = InMemoryConnectedComponentsFinder()

    private fun edge(anchorId: String, memberId: String, vetoed: Boolean = false) =
        CollectorCandidateEdge(
            anchorId = anchorId,
            memberId = memberId,
            aggregateScore = 0.9,
            vetoed = vetoed,
            signals = emptyList(),
        )

    @Test
    fun `two chained edges union all three ids into one component`() {
        val components = finder.findComponents(
            runId = "run-1",
            propositionIds = setOf("A", "B", "C"),
            edges = listOf(edge("A", "B"), edge("B", "C")),
        )
        assertEquals(3, components.size)
        val root = components.getValue("A")
        assertEquals(root, components.getValue("B"))
        assertEquals(root, components.getValue("C"))
    }

    @Test
    fun `a vetoed edge does not union its endpoints`() {
        val components = finder.findComponents(
            runId = "run-1",
            propositionIds = setOf("A", "B"),
            edges = listOf(edge("A", "B", vetoed = true)),
        )
        assertNotEquals(components.getValue("A"), components.getValue("B"))
        assertEquals("A", components.getValue("A"))
        assertEquals("B", components.getValue("B"))
    }

    @Test
    fun `an id with no edges is its own singleton only if passed in propositionIds`() {
        val components = finder.findComponents(
            runId = "run-1",
            propositionIds = setOf("A", "B", "Z"),
            edges = listOf(edge("A", "B")),
        )
        assertEquals(setOf("A", "B", "Z"), components.keys)
        assertEquals("Z", components.getValue("Z"))
    }

    @Test
    fun `edge endpoints outside propositionIds are ignored`() {
        val components = finder.findComponents(
            runId = "run-1",
            propositionIds = setOf("A"),
            edges = listOf(edge("A", "B")),
        )
        assertEquals(setOf("A"), components.keys)
        assertEquals("A", components.getValue("A"))
    }

    @Test
    fun `deterministic root selection picks the smaller id and is stable across runs`() {
        val first = finder.findComponents(
            runId = "run-1",
            propositionIds = setOf("z-node", "a-node"),
            edges = listOf(edge("z-node", "a-node")),
        )
        val second = finder.findComponents(
            runId = "run-2",
            propositionIds = setOf("z-node", "a-node"),
            edges = listOf(edge("z-node", "a-node")),
        )
        assertEquals("a-node", first.getValue("z-node"))
        assertEquals("a-node", first.getValue("a-node"))
        assertEquals(first, second)
    }
}
