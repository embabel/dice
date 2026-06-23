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
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Data-class contract for the cycle report: totals carry through and cycleCompleted defaults to now. */
class DreamLoopReportTest {

    @Test
    fun `report carries its totals and defaults cycleCompleted to construction time`() {
        val started = Instant.now().minusSeconds(5)
        val report = DreamLoopReport(
            contextId = ContextId("c"),
            cycleStarted = started,
            passResults = emptyList(),
            totalExamined = 10,
            totalTransitioned = 3,
            totalNewPropositions = 2,
            triggered = true,
        )

        assertEquals(10, report.totalExamined)
        assertEquals(3, report.totalTransitioned)
        assertEquals(2, report.totalNewPropositions)
        assertTrue(report.triggered)
        // cycleCompleted is defaulted at construction, so it's at or after the cycle start.
        assertTrue(!report.cycleCompleted.isBefore(started))
    }
}
