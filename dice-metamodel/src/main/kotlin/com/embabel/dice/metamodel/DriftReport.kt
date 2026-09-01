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
import java.time.Instant
import java.util.Objects

/**
 * One drift check, written down: what a live graph held that nobody had declared, at one moment,
 * measured against one declared schema version.
 *
 * A report records one point in time. Keeping every one is what lets you answer "when did this
 * start?" and "is it getting worse?" later; a single mutable "current drift" field could only answer
 * "right now", and would lose the history the moment somebody fixed the schema.
 *
 * [versionHash] is the tie back to the schema this was judged against. A `DriftCheckRunner` stamps
 * the declared version into a [MetamodelVersionStore] before writing the report, so the hash always
 * resolves through [MetamodelVersionStore.findVersion]. Pull a year-old report and you can still
 * recover the exact shape that was expected when it was taken.
 *
 * ## A report carries both halves of the comparison
 *
 * [driftedEntityTypes] and [driftedRelationshipTypes] are the graph-truth half: what the graph holds
 * that this declaration doesn't recognise. [declaredDiff] is the other half: how the declaration
 * itself moved since the last completed sweep — a property removed, a cardinality narrowed, a whole
 * type dropped — which the graph-truth half cannot see, because nothing about such a type is
 * undeclared and only its shape moved.
 *
 * Both are here because a report is what a person reads before deciding to sweep, and a sweep acts
 * on both. A report that showed only the first half could read completely clean while a sweep
 * against the very same state would quarantine, and the person who checked would have no way to
 * know. [quarantineDiff] hands back the exact merged comparison such a sweep evaluates.
 *
 * @property schemaName The declared schema's name at check time. Together with [versionHash] this
 *   is what resolves the report back to a stored [MetamodelVersion].
 * @property versionHash The [MetamodelVersion.contentHash] of the declared schema the check ran
 *   against.
 * @property driftedEntityTypes Entity type names (labels) the graph held but the schema never
 *   declared, sorted the way the diff produced them.
 * @property driftedRelationshipTypes Relationship type names observed with no matching declaration.
 * @property capturedAt When the observation was taken: the [ObservedSchema.capturedAt] of the
 *   snapshot it was computed from, which is a different instant from the write.
 * @property contextId The context the check was scoped to, or `null` when it covered the whole
 *   graph.
 * @property declaredDiff How the declaration itself moved since the last completed sweep, described
 *   above. `null` when the store tracked no baseline to compare against, which is the state of every
 *   schema before its first sweep finishes.
 */
class DriftReport @JvmOverloads constructor(
    val schemaName: String,
    val versionHash: String,
    driftedEntityTypes: Set<String>,
    driftedRelationshipTypes: Set<String>,
    val capturedAt: Instant,
    val contextId: ContextId? = null,
    val declaredDiff: MetamodelDiff? = null,
) {

    // Both sets are copied into JVM-immutable ones that keep the order they arrived in, and this
    // is a plain class rather than a `data class`, for the same reason as the rest of this module:
    // a record of a moment must not be reshapeable afterwards, Kotlin's read-only `Set` is a
    // compile-time promise a Java caller sees straight through, and a generated `copy()` would hand
    // its arguments to the fields and skip the copying.

    val driftedEntityTypes: Set<String> = immutableCopy(driftedEntityTypes)

    val driftedRelationshipTypes: Set<String> = immutableCopy(driftedRelationshipTypes)

    /** `true` when this check found anything undeclared at all. */
    val hasDrift: Boolean
        get() = driftedEntityTypes.isNotEmpty() || driftedRelationshipTypes.isNotEmpty()

    /**
     * `true` when either half of the check found something: an undeclared type or relationship in
     * the graph, or a declared change since the last completed sweep.
     *
     * [hasDrift] answers the narrower graph-truth question, so read this one when you want to know
     * whether a sweep would have anything at all to look at.
     */
    val hasAnyChange: Boolean get() = hasDrift || declaredDiff?.isEmpty == false

    /**
     * The single comparison a deliberate sweep evaluates propositions against: this report's
     * observed drift and its [declaredDiff] merged into one [MetamodelDiff].
     *
     * A sweep decides once, off one diff. Running the two comparisons as two sweeps would let each
     * make an independent call about the same proposition, and the second call would see a
     * proposition the first had already moved.
     *
     * Every entity type name from either source lands in one [MetamodelChange.EntityTypeRemoved]
     * block, sorted by name, which is the global ordering [MetamodelDiff] promises. A removal
     * [declaredDiff] already reports is filtered out before the merge, so no name appears twice, and
     * [declaredDiff]'s remaining changes keep their relative order behind the block.
     *
     * [declaredDiff]'s own [MetamodelDiff.fromVersion] carries through as the merged `from` side
     * when there is one, so a policy can still resolve the declared former names of a type the
     * declaration removed. A type observed in the graph and declared by nobody has no former names
     * on either side.
     *
     * @param declaredVersion The stamp this check ran against — the one [versionHash] resolves to
     *   through [MetamodelVersionStore.findVersion].
     * @return The merged diff.
     */
    fun quarantineDiff(declaredVersion: MetamodelVersion): MetamodelDiff {
        val declaredChanges = declaredDiff?.changes.orEmpty()
        val removedNames = (declaredDiff?.removedEntityTypes.orEmpty() union driftedEntityTypes).sorted()
        return MetamodelDiff(
            fromVersion = declaredDiff?.fromVersion ?: declaredVersion,
            toVersion = declaredVersion,
            changes = removedNames.map { MetamodelChange.EntityTypeRemoved(it) } +
                declaredChanges.filterNot { it is MetamodelChange.EntityTypeRemoved },
        )
    }

    override fun equals(other: Any?): Boolean =
        other is DriftReport &&
            schemaName == other.schemaName &&
            versionHash == other.versionHash &&
            driftedEntityTypes == other.driftedEntityTypes &&
            driftedRelationshipTypes == other.driftedRelationshipTypes &&
            capturedAt == other.capturedAt &&
            contextId == other.contextId &&
            declaredDiff == other.declaredDiff

    override fun hashCode(): Int = Objects.hash(
        schemaName,
        versionHash,
        driftedEntityTypes,
        driftedRelationshipTypes,
        capturedAt,
        contextId,
        declaredDiff,
    )

    override fun toString(): String =
        "DriftReport(schemaName=$schemaName, versionHash=$versionHash, " +
            "driftedEntityTypes=$driftedEntityTypes, driftedRelationshipTypes=$driftedRelationshipTypes, " +
            "capturedAt=$capturedAt, contextId=${contextId?.value}, declaredDiff=$declaredDiff)"

    private companion object {

        private fun <T> immutableCopy(values: Set<T>): Set<T> =
            java.util.Collections.unmodifiableSet(LinkedHashSet(values))
    }
}

/**
 * Durable log of drift checks. Nothing is ever deleted, so the history of what a graph held against
 * what was declared accumulates and stays answerable.
 *
 * Kept apart from [MetamodelVersionStore] on purpose. Stamps and reports have different lifetimes
 * and volumes: a schema gets stamped when somebody changes it, while a scheduled drift check writes
 * a report every run. Folding both into one interface would force any backend to serve both access
 * patterns, and would make "I only want to record versions" impossible to express.
 *
 * [saveDriftReport] is an upsert on the natural key `(schemaName, versionHash, capturedAt,
 * contextId)`. Two observations differing in any of those are separate records; re-saving one with
 * the same key overwrites its drifted type sets rather than adding a duplicate.
 *
 * ## Every read is bounded
 *
 * There is no unbounded read. A drift log grows once per check per schema forever, so an unbounded
 * query works on a laptop and falls over in production after a month of hourly checks. Every read
 * here takes a `limit`, and optionally a `since` instant to bound the window.
 *
 * ## Three reads, each explicit about scope
 *
 * The scope is named at the call site: [driftReports] is everything, [globalDriftReports] is only
 * unscoped whole-graph checks, and [driftReportsInContext] is one context's. Three names rather than
 * one method with a nullable context, because `driftReports(schema, null)` would have meant "the
 * global ones" while `driftReports(schema)` meant "all of them", two near-identical calls with
 * different answers. Splitting them also gives Java callers a way to reach the global reports:
 * `ContextId` is a Kotlin value class, so [driftReportsInContext] compiles to a mangled JVM name
 * Java can't call, while the other two stay callable.
 *
 * None of the three has a default body, which follows from bounding the reads. Filtering
 * `driftReports(schema, limit)` down to the global ones in memory would return at most `limit` rows
 * *before* the filter, so a schema whose recent history is mostly context-scoped could report zero
 * global drift while plenty sat in the store. The scope has to be pushed down into the query, so
 * each implementation writes all three.
 */
interface DriftReportStore {

    /**
     * Record one drift observation, keyed on `(schemaName, versionHash, capturedAt, contextId)`.
     *
     * @param report The observation to save.
     */
    fun saveDriftReport(report: DriftReport)

    /**
     * Reports for a schema at any scope: global checks and every context's, mixed together, newest
     * first by [DriftReport.capturedAt].
     *
     * @param schemaName The schema to look up.
     * @param limit The most reports to return. Must be positive.
     * @param since When non-null, only reports captured at or after this instant.
     * @return At most [limit] matching reports, newest first. Empty when there are none.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun driftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport>

    /**
     * The same read with no time window.
     *
     * A real overload with a body rather than a Kotlin default argument, which Java cannot see.
     *
     * @param schemaName The schema to look up.
     * @param limit The most reports to return. Must be positive.
     * @return At most [limit] matching reports, newest first.
     */
    fun driftReports(schemaName: String, limit: Int): List<DriftReport> =
        driftReports(schemaName, limit, null)

    /**
     * Reports from unscoped, whole-graph checks only: those whose [DriftReport.contextId] is
     * `null`. A check scoped to a context is excluded whichever context it was.
     *
     * @param schemaName The schema to look up.
     * @param limit The most reports to return. Must be positive.
     * @param since When non-null, only reports captured at or after this instant.
     * @return At most [limit] matching reports, newest first.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun globalDriftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport>

    /**
     * The same read with no time window.
     *
     * @param schemaName The schema to look up.
     * @param limit The most reports to return. Must be positive.
     * @return At most [limit] matching reports, newest first.
     */
    fun globalDriftReports(schemaName: String, limit: Int): List<DriftReport> =
        globalDriftReports(schemaName, limit, null)

    /**
     * Reports from checks scoped to [contextId]. Global reports are excluded, as are other
     * contexts'.
     *
     * @param schemaName The schema to look up.
     * @param contextId The context to restrict to.
     * @param limit The most reports to return. Must be positive.
     * @param since When non-null, only reports captured at or after this instant.
     * @return At most [limit] matching reports, newest first.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun driftReportsInContext(
        schemaName: String,
        contextId: ContextId,
        limit: Int,
        since: Instant?,
    ): List<DriftReport>

    /**
     * The same read with no time window.
     *
     * @param schemaName The schema to look up.
     * @param contextId The context to restrict to.
     * @param limit The most reports to return. Must be positive.
     * @return At most [limit] matching reports, newest first.
     */
    fun driftReportsInContext(schemaName: String, contextId: ContextId, limit: Int): List<DriftReport> =
        driftReportsInContext(schemaName, contextId, limit, null)
}
