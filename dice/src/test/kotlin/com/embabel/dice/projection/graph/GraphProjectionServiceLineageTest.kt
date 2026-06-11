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
package com.embabel.dice.projection.graph

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.proposition.ProjectionFailed
import com.embabel.dice.proposition.ProjectionFailureReason
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.ProjectionSkipped
import com.embabel.dice.proposition.ProjectionSuccess
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that [GraphProjectionService] emits the right [ProjectionLifecycle] and reason
 * on each [ProjectionRecord] — one per result, all sharing a single run ID.
 */
class GraphProjectionServiceLineageTest {

    private val mockProjector = mockk<GraphProjector>()
    private val mockPersister = mockk<GraphRelationshipPersister>()
    private val mockSchema = DataDictionary.fromDomainTypes("test", emptyList())

    private fun proposition(id: String): Proposition =
        Proposition(
            id = id,
            contextId = ContextId("ctx"),
            text = "text for $id",
            mentions = emptyList(),
            confidence = 1.0,
        )

    @Test
    fun `records one ProjectionRecord per result with correct lifecycle and reason`() {
        val pSuccess = proposition("p-success")
        val pSkipped = proposition("p-skipped")
        val pFailed = proposition("p-failed")

        val skipReason = ProjectionFailureReason.NoMatchingPredicate("nothing matched")
        val failReason = ProjectionFailureReason.PolicyRejected("rejected by policy")

        val results = ProjectionResults(
            listOf(
                ProjectionSuccess(
                    pSuccess,
                    ProjectedRelationship(
                        sourceId = "node-1",
                        targetId = "node-2",
                        type = "KNOWS",
                        confidence = 1.0,
                        sourcePropositionIds = listOf("p-success"),
                    ),
                ),
                ProjectionSkipped(pSkipped, reason = "skip text", structuredReason = skipReason),
                ProjectionFailed(pFailed, reason = "fail text", structuredReason = failReason),
            ),
        )
        val persistence = RelationshipPersistenceResult(persistedCount = 1, failedCount = 0)
        val propositions = listOf(pSuccess, pSkipped, pFailed)

        every {
            mockPersister.projectAndPersist(propositions, mockProjector, mockSchema)
        } returns Pair(results, persistence)

        val store = InMemoryProjectionRecordStore()
        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema, store)

        service.projectAndPersist(propositions)

        val records = store.all()
        assertEquals(3, records.size)

        val byProposition = records.associateBy { it.propositionId }
        assertEquals(setOf("p-success", "p-skipped", "p-failed"), byProposition.keys)

        val success = byProposition.getValue("p-success")
        assertEquals(ProjectionLifecycle.PROJECTED, success.lifecycle)
        assertNull(success.reason)

        val skipped = byProposition.getValue("p-skipped")
        assertEquals(ProjectionLifecycle.SKIPPED, skipped.lifecycle)
        assertEquals(skipReason.describe(), skipped.reason)

        val failed = byProposition.getValue("p-failed")
        assertEquals(ProjectionLifecycle.FAILED, failed.lifecycle)
        assertEquals(failReason.describe(), failed.reason)

        // all share one runId, target is neo4j
        assertEquals(1, records.map { it.runId }.toSet().size)
        assertTrue(records.all { it.target == "neo4j" })
    }

    @Test
    fun `with no store the returned pair is unchanged and nothing is recorded`() {
        val propositions = listOf<Proposition>()
        val results = ProjectionResults<ProjectedRelationship>(emptyList())
        val persistence = RelationshipPersistenceResult(persistedCount = 0, failedCount = 0)
        val expectedPair = Pair(results, persistence)

        every {
            mockPersister.projectAndPersist(propositions, mockProjector, mockSchema)
        } returns expectedPair

        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema)
        val result = service.projectAndPersist(propositions)

        assertSame(expectedPair, result)
    }
}
