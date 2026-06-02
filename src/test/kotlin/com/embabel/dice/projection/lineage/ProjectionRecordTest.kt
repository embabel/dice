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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class ProjectionRecordTest {

    @Test
    fun `construction with defaults`() {
        val record = ProjectionRecord(
            propositionId = "p1",
            target = "graph",
            lifecycle = ProjectionLifecycle.PROJECTED,
            runId = "run-1",
        )
        assertEquals("p1", record.propositionId)
        assertEquals("graph", record.target)
        assertNull(record.targetRef)
        assertNull(record.reason)
        assertNotNull(record.at)
    }

    @Test
    fun `factory of populates fields`() {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val record = ProjectionRecord.of(
            propositionId = "p2",
            target = "prolog",
            lifecycle = ProjectionLifecycle.ADOPTED,
            runId = "run-2",
            targetRef = "node-99",
            at = at,
            reason = "matched existing node",
        )
        assertEquals("node-99", record.targetRef)
        assertEquals(ProjectionLifecycle.ADOPTED, record.lifecycle)
        assertEquals(at, record.at)
        assertEquals("matched existing node", record.reason)
    }

    @Test
    fun `blank required fields rejected`() {
        assertThrows<IllegalArgumentException> {
            ProjectionRecord(propositionId = "", target = "graph", lifecycle = ProjectionLifecycle.PROJECTED, runId = "r")
        }
        assertThrows<IllegalArgumentException> {
            ProjectionRecord(propositionId = "p", target = " ", lifecycle = ProjectionLifecycle.PROJECTED, runId = "r")
        }
        assertThrows<IllegalArgumentException> {
            ProjectionRecord(propositionId = "p", target = "graph", lifecycle = ProjectionLifecycle.PROJECTED, runId = "")
        }
    }
}
