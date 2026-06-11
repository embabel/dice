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
package com.embabel.dice.projection.lineage

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.PropositionPinned
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectionLineageStaleCascadeTest {

    private fun proposition(id: String, status: PropositionStatus = PropositionStatus.ACTIVE): Proposition =
        Proposition(
            id = id,
            contextId = ContextId("ctx"),
            text = "text for $id",
            mentions = emptyList(),
            confidence = 1.0,
            status = status,
        )

    private fun projected(propositionId: String, targetRef: String): ProjectionRecord =
        ProjectionRecord(
            propositionId = propositionId,
            target = "neo4j",
            targetRef = targetRef,
            lifecycle = ProjectionLifecycle.PROJECTED,
            runId = "run-1",
        )

    @Test
    fun `terminal status change marks that proposition's records STALE leaving others untouched`() {
        val store = InMemoryProjectionRecordStore()
        store.record(projected("p1", "node-1"))
        store.record(projected("p1", "node-2"))
        store.record(projected("p2", "node-3"))

        val cascade = ProjectionLineageStaleCascade(store)

        cascade.onEvent(
            PropositionStatusChanged(
                proposition = proposition("p1", PropositionStatus.SUPERSEDED),
                previousStatus = PropositionStatus.ACTIVE,
                newStatus = PropositionStatus.SUPERSEDED,
            ),
        )

        val byRef = store.all().associateBy { it.targetRef }
        assertEquals(ProjectionLifecycle.STALE, byRef.getValue("node-1").lifecycle)
        assertEquals(ProjectionLifecycle.STALE, byRef.getValue("node-2").lifecycle)
        assertEquals(ProjectionLifecycle.PROJECTED, byRef.getValue("node-3").lifecycle)
    }

    @Test
    fun `non-terminal status change does not mark records STALE`() {
        val store = InMemoryProjectionRecordStore()
        store.record(projected("p2", "node-3"))

        val cascade = ProjectionLineageStaleCascade(store)

        cascade.onEvent(
            PropositionStatusChanged(
                proposition = proposition("p2", PropositionStatus.PROMOTED),
                previousStatus = PropositionStatus.ACTIVE,
                newStatus = PropositionStatus.PROMOTED,
            ),
        )

        assertEquals(ProjectionLifecycle.PROJECTED, store.all().single().lifecycle)
    }

    @Test
    fun `non-matching event type is ignored without failure`() {
        val store = InMemoryProjectionRecordStore()
        store.record(projected("p1", "node-1"))

        val cascade = ProjectionLineageStaleCascade(store)

        cascade.onEvent(PropositionPinned(proposition("p1")))

        assertEquals(ProjectionLifecycle.PROJECTED, store.all().single().lifecycle)
    }
}
