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
import java.time.Instant

/**
 * Translate the lineage records to and from the property maps the durable graph stores read and
 * write. Kept separate from the stores (and free of any Drivine types) so the fiddly bits — typed
 * reasons flattened to a key, enums stored by name, timestamps as ISO strings — can be unit-tested
 * without a database.
 *
 * Timestamps are written as ISO-8601 strings and parsed back, which keeps reads off the database's
 * native temporal types and gives a single, predictable round-trip. A bad timestamp on read falls
 * back to [Instant.EPOCH] rather than "now", so a corrupt record never looks freshly written.
 */
object ProjectionRecordRowMapper {

    /** Bind values for a write — the natural key is (propositionId, runId, target). */
    fun bindMap(record: ProjectionRecord): Map<String, Any?> = mapOf(
        "propositionId" to record.propositionId,
        "runId" to record.runId,
        "target" to record.target,
        "targetRef" to record.targetRef,
        "lifecycle" to record.lifecycle.name,
        "at" to record.at.toString(),
        "reason" to record.reason,
    )

    /** Rebuild a [ProjectionRecord] from a returned node's property map. */
    fun fromRow(row: Map<*, *>): ProjectionRecord = ProjectionRecord(
        propositionId = row.str("propositionId"),
        target = row.str("target"),
        targetRef = row.strOrNull("targetRef"),
        lifecycle = runCatching { ProjectionLifecycle.valueOf(row.str("lifecycle")) }
            .getOrDefault(ProjectionLifecycle.FAILED),
        runId = row.str("runId"),
        at = parseInstant(row.strOrNull("at")),
        reason = row.strOrNull("reason"),
    )
}

object CollectorRecordRowMapper {

    /** Bind values for a write — the natural key is (propositionId, runId). */
    fun bindMap(record: CollectorRecord): Map<String, Any?> = mapOf(
        "propositionId" to record.propositionId,
        "runId" to record.runId,
        "reason" to record.reason.key,
        // A Duplicate reason carries the survivor's id; keep it so dedup audits survive a restart.
        "survivorId" to (record.reason as? MarkReason.Duplicate)?.survivorId,
        "outcome" to record.outcome.name,
        "strategyName" to record.strategyName,
        "at" to record.at.toString(),
        "previousStatus" to record.previousStatus?.name,
        "newStatus" to record.newStatus?.name,
    )

    /** Rebuild a [CollectorRecord], reconstructing the typed reason and the optional statuses. */
    fun fromRow(row: Map<*, *>): CollectorRecord = CollectorRecord(
        propositionId = row.str("propositionId"),
        reason = when (row.str("reason")) {
            MarkReason.Stale.key -> MarkReason.Stale
            "duplicate" -> MarkReason.Duplicate(row.str("survivorId"))
            else -> MarkReason.Custom(row.str("reason"), "")
        },
        outcome = runCatching { CollectorOutcome.valueOf(row.str("outcome")) }
            .getOrDefault(CollectorOutcome.SKIPPED),
        strategyName = row.str("strategyName"),
        runId = row.str("runId"),
        at = parseInstant(row.strOrNull("at")),
        previousStatus = row.statusOrNull("previousStatus"),
        newStatus = row.statusOrNull("newStatus"),
    )
}

object CollectorRunRowMapper {

    /** Bind values for a write — keyed by runId. */
    fun bindMap(run: CollectorRun): Map<String, Any?> = mapOf(
        "runId" to run.runId,
        "startedAt" to run.startedAt.toString(),
        "finishedAt" to run.finishedAt?.toString(),
        "dryRun" to run.dryRun,
    )

    /** Rebuild a [CollectorRun]; an unfinished run has no `finishedAt`. */
    fun fromRow(row: Map<*, *>): CollectorRun = CollectorRun(
        runId = row.str("runId"),
        startedAt = parseInstant(row.strOrNull("startedAt")),
        finishedAt = row.strOrNull("finishedAt")?.let { parseInstant(it) },
        dryRun = row["dryRun"]?.toString()?.toBooleanStrictOrNull() ?: false,
    )
}

private fun Map<*, *>.str(key: String): String = this[key]?.toString().orEmpty()

private fun Map<*, *>.strOrNull(key: String): String? = this[key]?.toString()

private fun Map<*, *>.statusOrNull(key: String): PropositionStatus? =
    strOrNull(key)?.takeIf { it.isNotBlank() }?.let { runCatching { PropositionStatus.valueOf(it) }.getOrNull() }

private fun parseInstant(value: String?): Instant =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
