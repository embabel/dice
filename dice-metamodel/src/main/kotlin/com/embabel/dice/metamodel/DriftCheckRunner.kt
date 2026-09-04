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

import org.jetbrains.annotations.ApiStatus

import com.embabel.agent.core.ContextId
import java.util.Objects

/**
 * What one [DriftCheckRunner.run] call found.
 *
 * The drifted types are read off [report], with no second copy kept here. Every run persists a
 * report, so a copy would be a second version of the same answer, and two versions can disagree: a
 * caller who logged the result and an operator who read the stored report would then see different
 * type sets for the same check.
 *
 * @property report The [DriftReport] this run saved. Every run saves one, including a check that
 *   found nothing.
 * @property declaredVersion The stamp the check ran against. [DriftReport.versionHash] is this
 *   stamp's [MetamodelVersion.contentHash], and holding the stamp itself is what lets [quarantineDiff]
 *   answer without a second trip to the version store.
 */
@ApiStatus.Experimental
class DriftCheckResult(
    val report: DriftReport,
    val declaredVersion: MetamodelVersion,
) {

    init {
        require(report.versionHash == declaredVersion.contentHash) {
            "report was judged against ${report.versionHash} but was handed the stamp " +
                "${declaredVersion.contentHash}"
        }
    }

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

    /**
     * How the declaration itself moved since the last completed sweep. `null` when the version store
     * tracked no baseline to compare against. See [DriftReport.declaredDiff].
     */
    val declaredDiff: MetamodelDiff? get() = report.declaredDiff

    /** `true` when either half of the check found something. See [DriftReport.hasAnyChange]. */
    val hasAnyChange: Boolean get() = report.hasAnyChange

    /**
     * The merged comparison a deliberate sweep would evaluate propositions against, so what this
     * check reports and what a sweep would act on are the same facts. See
     * [DriftReport.quarantineDiff].
     */
    val quarantineDiff: MetamodelDiff by lazy { report.quarantineDiff(declaredVersion) }

    override fun equals(other: Any?): Boolean =
        other is DriftCheckResult &&
            report == other.report &&
            declaredVersion == other.declaredVersion

    override fun hashCode(): Int = Objects.hash(report, declaredVersion)

    override fun toString(): String =
        "DriftCheckResult(report=$report, declaredVersion=$declaredVersion)"
}

/**
 * Runs a drift check end to end: takes the declared schema, stamps it, snapshots what a live graph
 * holds, compares the two, compares the declaration against the baseline a sweep last reconciled,
 * and writes the whole answer down.
 *
 * ## A check reports; it changes nothing
 *
 * There is one mode. A check reads, compares, and persists a [DriftReport], and no path through it
 * moves a proposition or the swept baseline. Acting on what a check found is a separate, deliberate
 * step a host takes through `DriftSweepCapable`, which lives in dice core with the rest of the
 * proposition lifecycle, and which has its own bounded, context-scoped candidate selection.
 *
 * That split is why [DriftCheckResult.quarantineDiff] exists. A check is the only half DICE runs on
 * its own, so its report has to show the *complete* comparison a sweep would act on, including the
 * declared-vs-previous half. A report reading clean while a sweep on the very same state would
 * quarantine is exactly the surprise this shape removes.
 *
 * Nothing here schedules itself. A consuming application decides when [run] is called, as it does
 * for the collector.
 *
 * The no-argument [run] is a real overload with a body, because Java can't see a Kotlin default
 * argument: `runner.run()` has to exist as a method for a Java caller to write it. It is also the
 * whole Java surface, since `ContextId` is a Kotlin value class and the scoped form compiles to a
 * mangled JVM name Java can't call. An implementation writes the scoped form and gets the other
 * free.
 */
interface DriftCheckRunner {

    /**
     * Declare, stamp, observe, diff against the graph, diff against the swept baseline, and persist
     * the report.
     *
     * @param contextId `null` means the check covers the whole graph. Non-null scopes both the
     *   observed snapshot and the persisted [DriftReport] to that one context, so a scoped check
     *   reports what that context alone holds.
     * @return What was found.
     */
    fun run(contextId: ContextId?): DriftCheckResult

    /**
     * Check the whole graph.
     *
     * @return What was found.
     */
    fun run(): DriftCheckResult = run(null)
}
