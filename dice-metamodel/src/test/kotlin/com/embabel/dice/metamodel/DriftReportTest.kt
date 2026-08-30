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
package com.embabel.dice.metamodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class DriftReportTest {

    @Test
    fun `drift report preserves entity and relationship type sets`() {
        val entities = setOf("UnknownPerson", "UnknownOrg")
        val relationships = setOf("UNDECLARED_LINKS")
        val now = Instant.now()

        val report = DriftReport(
            schemaName = "test-schema",
            versionHash = "abc123",
            driftedEntityTypes = entities,
            driftedRelationshipTypes = relationships,
            capturedAt = now,
        )

        assertEquals("test-schema", report.schemaName)
        assertEquals("abc123", report.versionHash)
        assertEquals(entities, report.driftedEntityTypes)
        assertEquals(relationships, report.driftedRelationshipTypes)
        assertEquals(now, report.capturedAt)
    }

    @Test
    fun `drift report with empty type sets constructs successfully`() {
        val report = DriftReport(
            schemaName = "clean-schema",
            versionHash = "def456",
            driftedEntityTypes = emptySet(),
            driftedRelationshipTypes = emptySet(),
            capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        assertEquals(0, report.driftedEntityTypes.size)
        assertEquals(0, report.driftedRelationshipTypes.size)
    }
}
