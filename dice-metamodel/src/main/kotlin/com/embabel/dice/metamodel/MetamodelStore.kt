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

/**
 * A drift report captures a single observation of undeclared types in a live graph at a specific
 * point in time. The types are compared against a declared [MetamodelVersion], so the report
 * records which declared version was expected when the drift was detected. Later checks can
 * correlate repeated observations against the same baseline to judge whether the drift is stale
 * or growing.
 *
 * @property schemaName The schema name at check time (for lookup and reporting).
 * @property versionHash The SHA-256 hash of the declared [MetamodelVersion] this check was
 *   performed against. Consumers can match this against saved versions to understand the
 *   schema structure that was expected at the time.
 * @property driftedEntityTypes Entity type names observed in the graph but not declared in the
 *   baseline version.
 * @property driftedRelationshipTypes Relationship type names observed in the graph but not
 *   declared in the baseline version.
 * @property capturedAt The instant the observation was taken.
 * @property contextId The context this check was scoped to, or `null` if it covered the whole
 *   graph.
 */
data class DriftReport(
    val schemaName: String,
    val versionHash: String,
    val driftedEntityTypes: Set<String>,
    val driftedRelationshipTypes: Set<String>,
    val capturedAt: Instant,
    val contextId: ContextId? = null,
)

/**
 * Durable store for metamodel versions and drift reports, enabling a later drift-check runner to
 * read the history of prior observations and make decisions about quarantine or schema updates.
 * Implementations must preserve all saved data exactly as recorded (including sets, timestamps,
 * and any serialization of collections).
 *
 * **What "save" means here.** Both writes are upserts on a natural key, not blind appends. Saving a
 * version whose key already exists does not create a second record: the existing one is matched and
 * its non-key content — the type names, label sets, property sets, and relationship names — is
 * overwritten with what you just passed. Saving a drift report behaves the same way on its own key.
 * So a re-save is idempotent when the content is identical (the common case, since a version's key
 * includes its content hash) and is a genuine overwrite when it isn't. Nothing is ever deleted, and
 * records with different keys always coexist, so history accumulates — but "nothing is ever updated"
 * would be too strong a promise, and implementations are not expected to reject a re-save.
 *
 * Multiple versions of the same schema can coexist; later queries for the latest return the most
 * recent by logical write order.
 */
interface MetamodelStore {

    /**
     * Save a metamodel version stamp, keyed on `(schemaName, contentHash)`. Saving the same version
     * twice leaves one stored version, not two. Because the content hash is derived from the
     * structural fields, two versions that share a key necessarily share their content too, so this
     * write is idempotent in practice.
     *
     * @param version The version to save.
     */
    fun saveVersion(version: MetamodelVersion)

    /**
     * Return the most recently saved version for the given schema name, or null if no versions
     * have been recorded.
     *
     * @param schemaName The schema to look up.
     * @return The latest [MetamodelVersion], or null if unknown.
     */
    fun latestVersion(schemaName: String): MetamodelVersion?

    /**
     * Return all saved versions for the given schema, newest first.
     *
     * @param schemaName The schema to look up.
     * @return A list of all [MetamodelVersion]s, ordered newest first. Empty if no versions
     *   exist for this schema.
     */
    fun versionHistory(schemaName: String): List<MetamodelVersion>

    /**
     * Save a single drift observation, keyed on the schema, the version hash it checked against,
     * the capture instant, and the context. Two observations that differ in any of those are
     * separate records; re-saving one with the same key overwrites its drifted type sets rather
     * than adding a duplicate.
     *
     * @param report The observation to save.
     */
    fun saveDriftReport(report: DriftReport)

    /**
     * Return every saved drift report for the given schema, newest first — global ones and every
     * context's, mixed together.
     *
     * There are deliberately three separate reads here rather than one method with a nullable
     * context argument. `driftReports(schema)`, `globalDriftReports(schema)` and
     * `driftReportsInContext(schema, id)` each say at the call site which set you meant. A single
     * `driftReports(schema, null)` would have quietly meant "the global ones only" while
     * `driftReports(schema)` meant "all of them" — the same-looking call with a different answer,
     * and nothing but the doc to tell them apart.
     *
     * Splitting them also gives Java callers a way to ask for the global reports at all: `ContextId`
     * is a Kotlin value class, so any method taking one gets a mangled JVM name that Java can't
     * call. [driftReportsInContext] is in that boat along with the rest of this module's
     * context-scoped API; [driftReports] and [globalDriftReports] are not.
     *
     * @param schemaName The schema to look up.
     * @return A list of all [DriftReport]s, ordered newest first. Empty if no reports exist
     *   for this schema.
     */
    fun driftReports(schemaName: String): List<DriftReport>

    /**
     * Return only the reports from unscoped, whole-graph checks — the ones whose
     * [DriftReport.contextId] is `null`. A check scoped to a context is excluded no matter which
     * context it was.
     *
     * The default filters the full [driftReports] list in memory, which is correct for every
     * implementation but not necessarily efficient; a backend that can push the filter down (a
     * database `WHERE`, not an in-memory `filter`) should override this directly rather than rely
     * on the default.
     *
     * @param schemaName The schema to look up.
     * @return A list of matching [DriftReport]s, ordered newest first.
     */
    fun globalDriftReports(schemaName: String): List<DriftReport> =
        driftReports(schemaName).filter { it.contextId == null }

    /**
     * Return only the reports from checks scoped to [contextId]. Global reports are excluded, as
     * are other contexts'.
     *
     * The default filters in memory, with the same caveat as [globalDriftReports]: override it if
     * the backend can push the filter down.
     *
     * @param schemaName The schema to look up.
     * @param contextId The context to restrict to.
     * @return A list of matching [DriftReport]s, ordered newest first.
     */
    fun driftReportsInContext(schemaName: String, contextId: ContextId): List<DriftReport> =
        driftReports(schemaName).filter { it.contextId == contextId }
}
