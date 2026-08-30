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

import com.embabel.agent.core.ContextId

/**
 * Summary of a single [DriftCheckRunner.run] call.
 *
 * @property dryRun Whether this was a preview run (no propositions were quarantined).
 * @property schemaName The declared schema name the check was performed against.
 * @property contextId The context this check was scoped to, or `null` if it checked the whole
 *   graph.
 * @property driftedEntityTypes Entity type names observed in the graph but never declared.
 * @property driftedRelationshipTypes Relationship type names observed but never declared.
 * @property quarantinedCount How many propositions were newly quarantined. Always 0 on a dry run
 *   or when there was no entity-type drift.
 * @property report The [DriftReport] this run persisted — every run persists one, even a clean one.
 */
data class DriftCheckResult(
    val dryRun: Boolean,
    val schemaName: String,
    val driftedEntityTypes: Set<String>,
    val driftedRelationshipTypes: Set<String>,
    val quarantinedCount: Int,
    val report: DriftReport,
    val contextId: ContextId? = null,
) {
    /** `true` when the graph contained any type or relationship never declared. */
    val hasDrift: Boolean get() = driftedEntityTypes.isNotEmpty() || driftedRelationshipTypes.isNotEmpty()
}

/**
 * Runs a drift check: snapshots what a live graph actually contains, compares it against the
 * declared schema, records the observation, and — opt-in — quarantines propositions whose
 * mentions reference types the graph has but the schema doesn't.
 *
 * Mirrors [com.embabel.dice.projection.memory.CollectorRunner]'s shape: dry-run by default, no
 * scheduling of its own (a consumer schedules calls to [run]), and every run leaves a durable
 * record — "checked and found nothing" has to be as retrievable as "checked and found drift".
 *
 * The shorter [run] forms are real overloads with bodies rather than Kotlin default arguments,
 * because Java can't see a default argument: `runner.run()` has to exist as a method for a Java
 * caller to write it. Implementations override the two-argument form and get the other two free.
 * For Java those two shorter forms are the whole surface — `ContextId` is a Kotlin value class, so
 * the two-argument form compiles to a mangled JVM name Java can't call.
 */
interface DriftCheckRunner {

    /**
     * Snapshot, diff, persist, and — if [dryRun] is `false` and drift touched any entity type —
     * quarantine.
     *
     * @param dryRun When `true`, the check runs and its [DriftReport] is persisted, but no
     *   proposition is touched. When `false`, propositions whose mentions reference a drifted
     *   entity type are handed to the configured [DriftQuarantinePolicy] and any it flags are
     *   persisted as quarantined.
     * @param contextId `null` means the check covers the whole graph. Non-null scopes everything
     *   the check touches to that one context: the candidate propositions read for quarantine, the
     *   observed schema snapshot, and the persisted [DriftReport]. A mis-declared schema in one
     *   context can then only ever quarantine propositions in that same context — it has no way to
     *   reach another one.
     * @return A [DriftCheckResult] summarizing what was found and (if live) what was quarantined.
     */
    fun run(dryRun: Boolean, contextId: ContextId?): DriftCheckResult

    /**
     * Run a dry check over the whole graph — the safe default. Nothing is quarantined; the report
     * is still persisted.
     */
    fun run(): DriftCheckResult = run(dryRun = true, contextId = null)

    /**
     * Run over the whole graph, dry or live.
     *
     * @param dryRun `true` to preview without touching any proposition.
     */
    fun run(dryRun: Boolean): DriftCheckResult = run(dryRun, null)
}
