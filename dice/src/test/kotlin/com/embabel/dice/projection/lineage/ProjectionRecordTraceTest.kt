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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectionRecordTraceTest {

    private fun store(): InMemoryProjectionRecordStore = InMemoryProjectionRecordStore().apply {
        record(
            ProjectionRecord(
                propositionId = "p1",
                target = "neo4j",
                targetRef = "node-1",
                lifecycle = ProjectionLifecycle.PROJECTED,
                runId = "run-1",
            ),
        )
        record(
            ProjectionRecord(
                propositionId = "p2",
                target = "neo4j",
                targetRef = "node-2",
                lifecycle = ProjectionLifecycle.ADOPTED,
                runId = "run-1",
            ),
        )
        record(
            ProjectionRecord(
                propositionId = "p3",
                target = "neo4j",
                targetRef = null,
                lifecycle = ProjectionLifecycle.SKIPPED,
                runId = "run-1",
            ),
        )
    }

    @Test
    fun `findByTargetRef traces an adopted artifact back to its source record`() {
        val record = store().findByTargetRef("node-2").single()
        assertEquals("p2", record.propositionId)
        assertEquals(ProjectionLifecycle.ADOPTED, record.lifecycle)
    }

    @Test
    fun `findByProposition traces back to the DICE-created record`() {
        val record = store().findByProposition("p1").single()
        assertEquals("node-1", record.targetRef)
        assertEquals(ProjectionLifecycle.PROJECTED, record.lifecycle)
    }

    @Test
    fun `lifecycle reads classify created vs adopted vs skipped`() {
        val byLifecycle = store().all().groupBy { it.lifecycle }
        assertEquals(listOf("p1"), byLifecycle.getValue(ProjectionLifecycle.PROJECTED).map { it.propositionId })
        assertEquals(listOf("p2"), byLifecycle.getValue(ProjectionLifecycle.ADOPTED).map { it.propositionId })
        assertEquals(listOf("p3"), byLifecycle.getValue(ProjectionLifecycle.SKIPPED).map { it.propositionId })
    }
}
