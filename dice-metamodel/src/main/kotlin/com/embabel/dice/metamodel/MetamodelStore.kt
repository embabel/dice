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
 * @property driftingEntityTypes Entity type names observed in the graph but not declared in the
 *   baseline version.
 * @property driftingRelationshipTypes Relationship type names observed in the graph but not
 *   declared in the baseline version.
 * @property capturedAt The instant the observation was taken.
 */
data class DriftReport(
    val schemaName: String,
    val versionHash: String,
    val driftingEntityTypes: Set<String>,
    val driftingRelationshipTypes: Set<String>,
    val capturedAt: Instant,
)

/**
 * Durable store for metamodel versions and drift reports, enabling a later drift-check runner to
 * read the history of prior observations and make decisions about quarantine or schema updates.
 * Implementations must preserve all saved data exactly as recorded (including sets, timestamps,
 * and any serialization of collections).
 *
 * The store is a write-append log for both versions and reports — no deletion, no updates.
 * Multiple versions of the same schema can coexist; later queries for the latest return the most
 * recent by logical write order.
 */
interface MetamodelStore {

    /**
     * Save a metamodel version stamp. Idempotent: calling [saveVersion] twice with the same
     * version produces one stored version, not two (MERGE on natural key).
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
     * Save a single drift observation. Appends a new report; never updates an existing one.
     *
     * @param report The observation to save.
     */
    fun saveDriftReport(report: DriftReport)

    /**
     * Return all saved drift reports for the given schema, newest first.
     *
     * @param schemaName The schema to look up.
     * @return A list of all [DriftReport]s, ordered newest first. Empty if no reports exist
     *   for this schema.
     */
    fun driftReports(schemaName: String): List<DriftReport>
}
