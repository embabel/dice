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
 * A report is a fact about a point in time, not a running total. Keeping every one of them is what
 * lets you answer "when did this start?" and "is it getting worse?" later — a single mutable
 * "current drift" field could only ever answer "right now", and would quietly lose the answer the
 * moment somebody fixed the schema.
 *
 * [versionHash] is the tie back to the schema this was judged against. A `DriftCheckRunner` stamps
 * the declared version into a [MetamodelVersionStore] before writing the report, so the hash always
 * resolves through [MetamodelVersionStore.findVersion] — you can pull a year-old report and still
 * recover the exact shape that was expected when it was taken.
 *
 * @property schemaName The declared schema's name at check time. Together with [versionHash] this
 *   is what resolves the report back to a stored [MetamodelVersion].
 * @property versionHash The [MetamodelVersion.contentHash] of the declared schema the check ran
 *   against.
 * @property driftedEntityTypes Entity type names (labels) the graph held but the schema never
 *   declared, sorted the way the diff produced them.
 * @property driftedRelationshipTypes Relationship type names observed with no matching declaration.
 * @property capturedAt When the observation was taken — the [ObservedSchema.capturedAt] of the
 *   snapshot it was computed from, not the time of the write.
 * @property contextId The context the check was scoped to, or `null` when it covered the whole
 *   graph.
 */
class DriftReport @JvmOverloads constructor(
    val schemaName: String,
    val versionHash: String,
    driftedEntityTypes: Set<String>,
    driftedRelationshipTypes: Set<String>,
    val capturedAt: Instant,
    val contextId: ContextId? = null,
) {

    // Both sets are copied into genuinely immutable ones that keep the order they arrived in, and
    // this is a plain class rather than a `data class`, for the same reason as everything else in
    // this module: a record of a moment must not be reshapeable afterwards, Kotlin's read-only
    // `Set` is a compile-time promise a Java caller sees straight through, and a generated `copy()`
    // would hand its arguments to the fields and skip the copying entirely.

    val driftedEntityTypes: Set<String> = immutableCopy(driftedEntityTypes)

    val driftedRelationshipTypes: Set<String> = immutableCopy(driftedRelationshipTypes)

    /** `true` when this check found anything undeclared at all. */
    val hasDrift: Boolean
        get() = driftedEntityTypes.isNotEmpty() || driftedRelationshipTypes.isNotEmpty()

    override fun equals(other: Any?): Boolean =
        other is DriftReport &&
            schemaName == other.schemaName &&
            versionHash == other.versionHash &&
            driftedEntityTypes == other.driftedEntityTypes &&
            driftedRelationshipTypes == other.driftedRelationshipTypes &&
            capturedAt == other.capturedAt &&
            contextId == other.contextId

    override fun hashCode(): Int = Objects.hash(
        schemaName,
        versionHash,
        driftedEntityTypes,
        driftedRelationshipTypes,
        capturedAt,
        contextId,
    )

    override fun toString(): String =
        "DriftReport(schemaName=$schemaName, versionHash=$versionHash, " +
            "driftedEntityTypes=$driftedEntityTypes, driftedRelationshipTypes=$driftedRelationshipTypes, " +
            "capturedAt=$capturedAt, contextId=${contextId?.value})"

    private companion object {

        private fun <T> immutableCopy(values: Set<T>): Set<T> =
            java.util.Collections.unmodifiableSet(LinkedHashSet(values))
    }
}

/**
 * Durable log of drift checks. Append-only in spirit: nothing is ever deleted, so the history of
 * what a graph looked like against what was declared accumulates and stays answerable.
 *
 * Kept apart from [MetamodelVersionStore] on purpose. Stamps and reports have different lifetimes
 * and very different volumes — a schema gets stamped when somebody changes it, while a scheduled
 * drift check writes a report every run whether it found anything or not. Folding both into one
 * interface would force any backend to serve both access patterns, and would make "I only want to
 * record versions" impossible to express.
 *
 * **What "save" means here.** [saveDriftReport] is an upsert on the natural key `(schemaName,
 * versionHash, capturedAt, contextId)`. Two observations differing in any of those are separate
 * records; re-saving one with the same key overwrites its drifted type sets rather than adding a
 * duplicate.
 *
 * ## Every read is bounded
 *
 * There is no "give me all of them". A drift log grows once per check per schema forever, so an
 * unbounded read is a query that works on a laptop and falls over in production after a month of
 * hourly checks — and the caller who wrote it had no way to know. Every read here therefore takes a
 * `limit`, and optionally a `since` instant to bound the window as well. Callers ask for a page;
 * they never ask for a table.
 *
 * ## Three reads, no defaults
 *
 * The scope is explicit at the call site: [driftReports] is everything, [globalDriftReports] is
 * only unscoped whole-graph checks, and [driftReportsInContext] is one context's. Three names
 * rather than one method with a nullable context, because `driftReports(schema, null)` would have
 * quietly meant "the global ones" while `driftReports(schema)` meant "all of them" — the same
 * looking call with a different answer and nothing but the doc to tell them apart. Splitting them
 * also gives Java callers a way to reach the global reports at all: `ContextId` is a Kotlin value
 * class, so [driftReportsInContext] compiles to a mangled JVM name Java can't call, while the other
 * two stay callable.
 *
 * None of the three has a default body, and that is the direct consequence of bounding the reads.
 * Filtering `driftReports(schema, limit)` down to the global ones in memory would return at most
 * `limit` rows *before* the filter, so a schema whose recent history is mostly context-scoped could
 * report zero global drift while plenty sat in the store — a wrong answer that looks like a right
 * one. The scope has to be pushed down into the query, so each implementation writes all three.
 */
interface DriftReportStore {

    /**
     * Record one drift observation, keyed on `(schemaName, versionHash, capturedAt, contextId)`.
     *
     * @param report The observation to save.
     */
    fun saveDriftReport(report: DriftReport)

    /**
     * Reports for a schema at any scope — global checks and every context's, mixed together —
     * newest first by [DriftReport.capturedAt].
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
     * A real overload with a body rather than a Kotlin default argument, so Java callers can write
     * it too — Java cannot see a Kotlin default argument.
     *
     * @param schemaName The schema to look up.
     * @param limit The most reports to return. Must be positive.
     * @return At most [limit] matching reports, newest first.
     */
    fun driftReports(schemaName: String, limit: Int): List<DriftReport> =
        driftReports(schemaName, limit, null)

    /**
     * Reports from unscoped, whole-graph checks only — those whose [DriftReport.contextId] is
     * `null`. A check scoped to a context is excluded no matter which context it was.
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
