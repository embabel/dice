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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryCollectorTraceStoreTest {

    private val tenantA = ContextId("tenant-a")
    private val tenantB = ContextId("tenant-b")

    private fun edge(anchorId: String, memberId: String) = CollectorCandidateEdge(
        anchorId = anchorId,
        memberId = memberId,
        aggregateScore = 0.8,
        vetoed = false,
        signals = emptyList(),
    )

    private fun component(id: String, members: List<String>) = CollectorComponent(
        componentId = id,
        memberIds = members,
    )

    private fun decision(componentId: String, survivorId: String) = CollectorDecision(
        componentId = componentId,
        survivorId = survivorId,
        action = "merge",
        retired = listOf(
            RetiredProposition(
                propositionId = "P2",
                priorStatus = PropositionStatus.ACTIVE,
            ),
        ),
    )

    @Test
    fun `edges components and decisions can be recorded and read back under a runId`() {
        val store = InMemoryCollectorTraceStore()
        store.recordCandidateEdges("run-1", listOf(edge("A", "B")))
        store.recordComponents("run-1", listOf(component("A", listOf("A", "B"))))
        store.recordDecision("run-1", decision("A", "A"))

        assertEquals(listOf(edge("A", "B")), store.edgesFor("run-1"))
        assertEquals(listOf(component("A", listOf("A", "B"))), store.componentsFor("run-1"))
        assertEquals(listOf(decision("A", "A")), store.decisionsFor("run-1"))
    }

    @Test
    fun `recording accumulates rather than overwriting within a runId`() {
        val store = InMemoryCollectorTraceStore()
        store.recordCandidateEdges("run-1", listOf(edge("A", "B")))
        store.recordCandidateEdges("run-1", listOf(edge("C", "D")))

        assertEquals(listOf(edge("A", "B"), edge("C", "D")), store.edgesFor("run-1"))
    }

    @Test
    fun `unknown runId reads back empty lists`() {
        val store = InMemoryCollectorTraceStore()
        assertTrue(store.edgesFor("missing").isEmpty())
        assertTrue(store.componentsFor("missing").isEmpty())
        assertTrue(store.decisionsFor("missing").isEmpty())
    }

    @Test
    fun `deleteTracesForContext removes only that context's runs and leaves others intact`() {
        val store = InMemoryCollectorTraceStore()
        store.recordRunContext("run-a", tenantA)
        store.recordRunContext("run-b", tenantB)

        store.recordCandidateEdges("run-a", listOf(edge("A", "B")))
        store.recordComponents("run-a", listOf(component("A", listOf("A", "B"))))
        store.recordDecision("run-a", decision("A", "A"))

        store.recordCandidateEdges("run-b", listOf(edge("C", "D")))
        store.recordComponents("run-b", listOf(component("C", listOf("C", "D"))))
        store.recordDecision("run-b", decision("C", "C"))

        store.deleteTracesForContext(tenantA)

        assertTrue(store.edgesFor("run-a").isEmpty())
        assertTrue(store.componentsFor("run-a").isEmpty())
        assertTrue(store.decisionsFor("run-a").isEmpty())

        assertEquals(listOf(edge("C", "D")), store.edgesFor("run-b"))
        assertEquals(listOf(component("C", listOf("C", "D"))), store.componentsFor("run-b"))
        assertEquals(listOf(decision("C", "C")), store.decisionsFor("run-b"))
    }
}
