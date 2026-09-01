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
package com.embabel.dice.storage

import org.drivine.schema.UniquenessConstraintSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [LineageSchema]: the one place the lineage stores' natural keys live.
 *
 * The MERGE pattern and the uniqueness constraint are built from the same key here, so these pin
 * that they render the key a store is actually upserting on.
 * `DrivineLineageRecordStoreIntegrationTest` runs the resulting statements against a database.
 */
class LineageSchemaTest {

    @Test
    fun `a merge pattern names every property of the natural key`() {
        assertEquals(
            "(n:ProjectionRecord {propositionId: \$propositionId, runId: \$runId, target: \$target})",
            LineageSchema.mergePattern("n", LineageSchema.PROJECTION_RECORD),
        )
        assertEquals(
            "(n:CollectorRun {runId: \$runId})",
            LineageSchema.mergePattern("n", LineageSchema.COLLECTOR_RUN),
        )
    }

    @Test
    fun `a label the lineage stores never write has no pattern`() {
        assertThrows<IllegalArgumentException> { LineageSchema.mergePattern("n", "Proposition") }
    }

    @Test
    fun `every label gets a uniqueness constraint on the key its store merges on`() {
        val constraints = LineageSchema.specs().filterIsInstance<UniquenessConstraintSpec>()
            .associate { it.label to it.properties }

        assertEquals(LineageSchema.NATURAL_KEYS, constraints)
    }
}
