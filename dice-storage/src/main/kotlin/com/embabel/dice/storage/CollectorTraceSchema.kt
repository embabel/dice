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

import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.UniquenessConstraintSpec

/**
 * The constraints and indexes [DrivineCollectorTraceStore] needs, factored out as plain data so
 * both the integration-test harness and the Phase-4 autoconfigure module can share one source of
 * truth instead of redeclaring the schema twice.
 */
object CollectorTraceSchema {

    fun specs(): List<SchemaItemSpec> = listOf(
        // Natural-key uniqueness, one per node label.
        UniquenessConstraintSpec(label = "CollectorTraceRun", property = "runId"),
        UniquenessConstraintSpec(label = "CollectorCandidateEdge", property = "id"),
        UniquenessConstraintSpec(label = "CollectorSignalScore", property = "id"),
        UniquenessConstraintSpec(label = "CollectorComponent", property = "id"),
        UniquenessConstraintSpec(label = "CollectorDecision", property = "id"),
        UniquenessConstraintSpec(label = "CollectorRetired", property = "id"),

        // Lookup indexes: everything gets queried by runId or contextId (per-context deletion,
        // per-run rehydration); componentId/signal/createdAt back the more targeted lookups.
        RangeIndexSpec(label = "CollectorTraceRun", property = "contextId"),
        RangeIndexSpec(label = "CollectorTraceRun", property = "createdAt"),
        RangeIndexSpec(label = "CollectorCandidateEdge", property = "runId"),
        RangeIndexSpec(label = "CollectorCandidateEdge", property = "contextId"),
        RangeIndexSpec(label = "CollectorSignalScore", property = "runId"),
        RangeIndexSpec(label = "CollectorSignalScore", property = "contextId"),
        RangeIndexSpec(label = "CollectorSignalScore", property = "signal"),
        RangeIndexSpec(label = "CollectorComponent", property = "runId"),
        RangeIndexSpec(label = "CollectorComponent", property = "contextId"),
        RangeIndexSpec(label = "CollectorComponent", property = "componentId"),
        RangeIndexSpec(label = "CollectorDecision", property = "runId"),
        RangeIndexSpec(label = "CollectorDecision", property = "contextId"),
        RangeIndexSpec(label = "CollectorDecision", property = "componentId"),
        RangeIndexSpec(label = "CollectorDecision", property = "survivorId"),
        RangeIndexSpec(label = "CollectorDecision", property = "createdAt"),
        RangeIndexSpec(label = "CollectorRetired", property = "runId"),
        RangeIndexSpec(label = "CollectorRetired", property = "contextId"),
        RangeIndexSpec(label = "CollectorRetired", property = "propositionId"),
    )

    /** Every node label the trace store writes, for cascade deletes and test cleanup. */
    val LABELS: List<String> = listOf(
        "CollectorTraceRun",
        "CollectorCandidateEdge",
        "CollectorSignalScore",
        "CollectorComponent",
        "CollectorDecision",
        "CollectorRetired",
    )
}
