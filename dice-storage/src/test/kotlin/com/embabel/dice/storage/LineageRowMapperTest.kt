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

import com.embabel.dice.projection.lineage.CollectorOutcome
import com.embabel.dice.projection.lineage.CollectorRecord
import com.embabel.dice.projection.lineage.CollectorRun
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.projection.memory.MarkReason
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Round-trips the lineage records through bind-map → property-map — the part that flattens typed
 * reasons and enums and rebuilds them, without needing a database.
 */
class LineageRowMapperTest {

    @Test
    fun `projection record round-trips through the row mapper`() {
        val record = ProjectionRecord(
            propositionId = "p1",
            target = "neo4j",
            targetRef = "node-42",
            lifecycle = ProjectionLifecycle.ADOPTED,
            runId = "run-1",
            at = Instant.parse("2026-01-01T00:00:00Z"),
            reason = "matched existing entity",
        )

        assertEquals(record, ProjectionRecordRowMapper.fromRow(ProjectionRecordRowMapper.bindMap(record)))
    }

    @Test
    fun `an unrecognised lifecycle falls back to FAILED`() {
        val row = mapOf("propositionId" to "p1", "target" to "neo4j", "lifecycle" to "NOPE", "runId" to "run-1")

        assertEquals(ProjectionLifecycle.FAILED, ProjectionRecordRowMapper.fromRow(row).lifecycle)
    }

    @Test
    fun `collector record round-trips each reason variant`() {
        listOf(
            MarkReason.Stale,
            MarkReason.Duplicate("survivor-7"),
            MarkReason.Custom("pinned", "kept by policy"),
        ).forEach { reason ->
            val record = CollectorRecord(
                propositionId = "p1",
                reason = reason,
                outcome = CollectorOutcome.TRANSITIONED,
                strategyName = "decay",
                runId = "run-1",
                at = Instant.parse("2026-01-01T00:00:00Z"),
                previousStatus = PropositionStatus.ACTIVE,
                newStatus = PropositionStatus.STALE,
            )

            val roundTripped = CollectorRecordRowMapper.fromRow(CollectorRecordRowMapper.bindMap(record))

            // Custom carries a human description that is intentionally not persisted; compare its
            // stable key for that variant, the others round-trip whole.
            if (reason is MarkReason.Custom) {
                assertEquals(reason.key, roundTripped.reason.key)
                assertEquals(record.copy(reason = roundTripped.reason), roundTripped)
            } else {
                assertEquals(record, roundTripped)
            }
        }
    }

    @Test
    fun `collector run round-trips, including an unfinished run`() {
        val finished = CollectorRun("run-1", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:05:00Z"), true)
        val unfinished = CollectorRun("run-2", Instant.parse("2026-02-01T00:00:00Z"))

        assertEquals(finished, CollectorRunRowMapper.fromRow(CollectorRunRowMapper.bindMap(finished)))
        assertEquals(unfinished, CollectorRunRowMapper.fromRow(CollectorRunRowMapper.bindMap(unfinished)))
    }
}
