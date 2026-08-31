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
 * **Why the schema name is in the key.** [MetamodelVersion.contentHash] excludes the schema name
 * on purpose, so two schemas with the same shape share a hash: a schema and its staging copy, or a
 * schema forked under a new name. History is per schema, which is what [latestVersion] and
 * [versionHistory] answer, so the same content has to be a separate record under each name.
 * Keyed on the hash alone, one schema adopting a shape another had earlier would land on the other
 * schema's record and pull that schema's history into its own. The name in the key is what keeps
 * two schemas' histories from bleeding into each other.
 *
 * This contract covers stamping and recall. Comparing a declaration against a live graph is a
 * separate concern with its own store contract.
 */
@ApiStatus.Experimental
interface MetamodelVersionStore {

    /**
     * Save a version stamp, keyed on `(schemaName, contentHash)`. Saving the same version twice
     * leaves one stored version.
     *
     * Everything about a stored stamp is content the key already determines, so a re-save
     * overwrites it with an identical value.
     *
     * Whatever an implementation records as the moment of the save keeps its existing value on a
     * re-save, along with the stamp's place in the write order: a re-saved old stamp does not
     * become the latest.
     *
     * An implementation may fail a save with its backend's own concurrency exception when two
     * writers race to save the same schema at once. Since the write is idempotent, the caller can
     * simply retry it.
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
     * The default scans [versionHistory], which is correct for any implementation and reads the
     * whole history to answer a keyed question. That is fine for the in-memory reference and for
     * a test double. A durable backend should override it with a keyed lookup, since a long-lived
     * schema's history only grows and this default grows with it; the Drivine store does, with a
     * `MATCH` on the natural key.
     *
     * @param schemaName The schema the version belongs to.
     * @param contentHash The [MetamodelVersion.contentHash] to find.
     * @return The matching version, or `null` if this schema has no version with that hash.
     */
    fun findVersion(schemaName: String, contentHash: String): MetamodelVersion? =
        versionHistory(schemaName).firstOrNull { it.contentHash == contentHash }

    /**
     * The version the last COMPLETED, unscoped live drift sweep reconciled against — the correct
     * baseline for the next declared-vs-previous comparison.
     *
     * This is a different question from [latestVersion], which answers "what's the newest stamp by
     * write order" and gets the wrong answer once a declaration cycles back to a stamp that already
     * exists: [saveVersion]'s own contract says a re-saved stamp keeps its *original* place in write
     * order, so after a schema goes `A` → `B` → `A` again, [latestVersion] still answers `B`, even
     * though the schema is back to declaring `A`. A drift check that diffed against [latestVersion]
     * would compare the reverted `A` against `B` — the wrong pair — and could miss a lossy change
     * that came back. [sweptVersion] tracks the actual reconciled baseline instead, moved forward
     * only by [markSwept], so it always answers the version a sweep genuinely finished comparing
     * against, whatever order the schema's stamps arrived in.
     *
     * The default answers [latestVersion]. That is a real gap, not a placeholder pretending the gap
     * is closed, and it is wider than the `A` → `B` → `A` case above: [saveVersion] runs on every
     * check regardless of [DriftCheckRunner]'s `dryRun` or `contextId`, so if this method still
     * answers [latestVersion], every path that reading the reconciled baseline separately was meant
     * to close reopens for a non-overriding store — a dry run's save moves what the next live run
     * treats as "already reconciled," a scoped live run's save does the same for the contexts it
     * never touched, and a crash between the save and the sweep finishing leaves the moved pointer
     * behind with nothing having actually been swept against it. A store must override this method
     * and [markSwept] together to get an independently-tracked baseline; overriding only one leaves
     * the other inconsistent. [InMemoryMetamodelVersionStore] overrides both; a durable backend
     * should do the same.
     *
     * @param schemaName The schema to look up.
     * @return The reconciled baseline, or `null` if no live sweep has ever completed for it.
     */
    fun sweptVersion(schemaName: String): MetamodelVersion? = latestVersion(schemaName)

    /**
     * Record [version] as the new reconciled baseline for its schema, once a live, unscoped drift
     * sweep has finished comparing the whole schema against it. See [sweptVersion] for why this is
     * tracked apart from [saveVersion]'s write-order history.
     *
     * Call this only after every candidate the sweep was going to touch has actually been handled —
     * calling it earlier (or on a dry run, or a run scoped to one context) would let a later check
     * believe a comparison happened that a crash interrupted, or that covered contexts it never
     * touched. [DefaultDriftCheckRunner] calls this last, after persisting every proposition its
     * sweep quarantined.
     *
     * The default forwards to [saveVersion]. That keeps the stamp itself recorded (harmless, since a
     * completed sweep's version is normally already stored by the time this runs) but does not give
     * [sweptVersion] independent tracking on its own — see that method's doc for what a store needs
     * to override to close the gap.
     *
     * @param version The version to record as reconciled.
     */
    fun markSwept(version: MetamodelVersion) = saveVersion(version)
}
