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
import com.embabel.dice.projection.lineage.ReconciliationDecision
import com.embabel.dice.projection.lineage.Reconciler
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.ProjectionSuccess
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that [GraphProjectionService] records the correct [ProjectionLifecycle]
 * (PROJECTED vs ADOPTED) and target reference when a [Reconciler] is in play.
 */
class GraphProjectionServiceReconciliationTest {

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

    private fun success(p: Proposition): ProjectionSuccess<ProjectedRelationship> =
        ProjectionSuccess(
            p,
            ProjectedRelationship(
                sourceId = "created-${p.id}",
                targetId = "node-target",
                type = "KNOWS",
                confidence = 1.0,
                sourcePropositionIds = listOf(p.id),
            ),
        )

    @Test
    fun `Adopt and Align record ADOPTED with the decision targetRef`() {
        val pAdopt = proposition("p-adopt")
        val pAlign = proposition("p-align")
        val propositions = listOf(pAdopt, pAlign)

        val results = ProjectionResults(listOf(success(pAdopt), success(pAlign)))
        val persistence = RelationshipPersistenceResult(persistedCount = 2, failedCount = 0)

        every {
            mockPersister.projectAndPersist(propositions, mockProjector, mockSchema)
        } returns Pair(results, persistence)

        val resolver = object : Reconciler {
            override fun reconcile(proposition: Proposition, target: String): ReconciliationDecision =
                when (proposition.id) {
                    "p-adopt" -> ReconciliationDecision.Adopt("node-42")
                    "p-align" -> ReconciliationDecision.Align("node-77")
                    else -> ReconciliationDecision.CreateNew
                }
        }

        val store = InMemoryProjectionRecordStore()
        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema, store, resolver)

        service.projectAndPersist(propositions)

        val byProposition = store.all().associateBy { it.propositionId }

        val adopted = byProposition.getValue("p-adopt")
        assertEquals(ProjectionLifecycle.ADOPTED, adopted.lifecycle)
        assertEquals("node-42", adopted.targetRef)

        val aligned = byProposition.getValue("p-align")
        assertEquals(ProjectionLifecycle.ADOPTED, aligned.lifecycle)
        assertEquals("node-77", aligned.targetRef)
    }

    @Test
    fun `default constructor (no resolver) records PROJECTED for successes`() {
        val p = proposition("p-default")
        val propositions = listOf(p)

        val results = ProjectionResults(listOf(success(p)))
        val persistence = RelationshipPersistenceResult(persistedCount = 1, failedCount = 0)

        every {
            mockPersister.projectAndPersist(propositions, mockProjector, mockSchema)
        } returns Pair(results, persistence)

        val store = InMemoryProjectionRecordStore()
        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema, store)

        service.projectAndPersist(propositions)

        val record = store.all().single()
        assertEquals(ProjectionLifecycle.PROJECTED, record.lifecycle)
        assertEquals("created-p-default", record.targetRef)
    }
}
