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
import java.util.Objects

/**
 * What one [DriftCheckRunner.run] call found and did.
 *
 * The drifted types are read off [report] rather than copied into fields here. Every run persists a
 * report, so a copy would be a second version of the same answer, and two versions can disagree: a
 * caller who logged the result and an operator who read the stored report would then see different
 * type sets for the same check.
 *
 * @property dryRun Whether this was a preview. On a dry run the report is still persisted; no
 *   proposition is touched.
 * @property report The [DriftReport] this run saved. Every run saves one, including a check that
 *   found nothing.
 * @property quarantinedCount How many propositions this run newly quarantined. Always 0 on a dry
 *   run, and 0 whenever there was no entity-type drift.
 */
class DriftCheckResult(
    val dryRun: Boolean,
    val report: DriftReport,
    val quarantinedCount: Int,
) {

    /** The declared schema the check ran against. */
    val schemaName: String get() = report.schemaName

    /** The context the check was scoped to, or `null` when it covered the whole graph. */
    val contextId: ContextId? get() = report.contextId

    /** Entity type names the graph held but the schema never declared. */
    val driftedEntityTypes: Set<String> get() = report.driftedEntityTypes

    /** Relationship type names observed with no matching declaration. */
    val driftedRelationshipTypes: Set<String> get() = report.driftedRelationshipTypes

    /** `true` when the graph contained any type or relationship that was never declared. */
    val hasDrift: Boolean get() = report.hasDrift

    override fun equals(other: Any?): Boolean =
        other is DriftCheckResult &&
            dryRun == other.dryRun &&
            report == other.report &&
            quarantinedCount == other.quarantinedCount

    override fun hashCode(): Int = Objects.hash(dryRun, report, quarantinedCount)

    override fun toString(): String =
        "DriftCheckResult(dryRun=$dryRun, quarantinedCount=$quarantinedCount, report=$report)"
}

/**
 * Runs a drift check end to end: takes the declared schema, stamps it, snapshots what a live graph
 * holds, compares the two, writes the result down, and quarantines the propositions the drift
 * stranded when asked to.
 *
 * Dry-run by default. Observing and reporting changes nothing; moving propositions to `STALE` is a
 * separate decision a caller opts into. Nothing here schedules itself, so a consuming application
 * decides when [run] is called, as it does for the collector.
 *
 * The shorter [run] forms are real overloads with bodies rather than Kotlin default arguments,
 * because Java can't see a default argument: `runner.run()` has to exist as a method for a Java
 * caller to write it. Implementations override the two-argument form and get the other two free.
 * Those two shorter forms are also the whole Java surface, since `ContextId` is a Kotlin value class
 * and the two-argument form compiles to a mangled JVM name Java can't call.
 */
interface DriftCheckRunner {

    /**
     * Declare, stamp, observe, diff, report, and quarantine when [dryRun] is `false` and drift
     * touched an entity type.
     *
     * @param dryRun When `true`, the check runs and its [DriftReport] is persisted, but no
     *   proposition is touched. When `false`, propositions whose mentions reference a drifted
     *   entity type are handed to the configured [DriftQuarantinePolicy] and whatever it flags is
     *   persisted.
     * @param contextId `null` means the check covers the whole graph. Non-null scopes everything
     *   the check touches to that one context: the observed snapshot, the candidate propositions
     *   read for quarantine, and the persisted [DriftReport]. A mis-declared schema in one context
     *   can only quarantine propositions in that same context.
     * @return What was found, and what was quarantined if this was a live run.
     */
    fun run(dryRun: Boolean, contextId: ContextId?): DriftCheckResult

    /**
     * Run a dry check over the whole graph. Nothing is quarantined; the report is still persisted.
     *
     * @return What was found.
     */
    fun run(): DriftCheckResult = run(dryRun = true, contextId = null)

    /**
     * Run over the whole graph, dry or live.
     *
     * @param dryRun `true` to preview without touching any proposition.
     * @return What was found, and what was quarantined if this was a live run.
     */
    fun run(dryRun: Boolean): DriftCheckResult = run(dryRun, null)
}
