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

/**
 * Durable store for metamodel version stamps. Keeping every stamp a schema has ever had is what
 * later lets you say when a shape changed and what knowledge was extracted under which version.
 * Implementations must preserve saved stamps exactly as recorded, including set contents and the
 * ordering guarantees below.
 *
 * **What "save" means here.** [saveVersion] is an upsert on the natural key `(schemaName,
 * contentHash)`. Saving a stamp whose key already exists matches the existing record and
 * overwrites its non-key content, so no second record appears. That is idempotence in practice,
 * because the key carries the content: the hash is derived from exactly the fields a re-save would
 * overwrite, so anything landing on an existing key has identical content by construction. Nothing
 * is deleted, and records with different keys always coexist, so history accumulates.
 * Implementations are not expected to reject a re-save.
 *
 * This contract covers stamping and recall. Comparing a declaration against a live graph is a
 * separate concern with its own store contract.
 */
interface MetamodelVersionStore {

    /**
     * Save a version stamp, keyed on `(schemaName, contentHash)`. Saving the same version twice
     * leaves one stored version.
     *
     * @param version The version to save.
     */
    fun saveVersion(version: MetamodelVersion)

    /**
     * Return the most recently saved version for the given schema name, or `null` if no versions
     * have been recorded.
     *
     * "Most recent" means logical write order. Re-saving an existing version does not make it the
     * latest again, because the write matched a record that was already there.
     *
     * @param schemaName The schema to look up.
     * @return The latest [MetamodelVersion], or `null` if unknown.
     */
    fun latestVersion(schemaName: String): MetamodelVersion?

    /**
     * Return all saved versions for the given schema, newest first.
     *
     * @param schemaName The schema to look up.
     * @return All [MetamodelVersion]s for the schema, newest first. Empty if there are none.
     */
    fun versionHistory(schemaName: String): List<MetamodelVersion>

    /**
     * Look up one exact stamp by its natural key.
     *
     * Resolves a recorded hash, such as the one a proposition carries as the version it was
     * extracted under, back into the schema shape it stood for.
     *
     * The default scans [versionHistory], which is correct for any implementation but reads the
     * whole history to answer a keyed question. A backend that can push the lookup down to the
     * database (a keyed `MATCH` rather than an in-memory `filter`) should override it.
     *
     * @param schemaName The schema the version belongs to.
     * @param contentHash The [MetamodelVersion.contentHash] to find.
     * @return The matching version, or `null` if this schema has no version with that hash.
     */
    fun findVersion(schemaName: String, contentHash: String): MetamodelVersion? =
        versionHistory(schemaName).firstOrNull { it.contentHash == contentHash }
}
