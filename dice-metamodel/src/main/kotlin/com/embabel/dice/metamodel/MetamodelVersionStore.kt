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
}

/**
 * A [MetamodelVersionStore] that can also remember which declaration a drift sweep last finished
 * reconciling against.
 *
 * Split off from [MetamodelVersionStore] because it is a genuinely harder promise: a store keeps
 * this pointer only when it can move it exactly once per completed sweep, and a store that can't do
 * that keeps the plain version contract and implements nothing here. `DriftCheckRunner` reads the
 * baseline through this interface when its store offers one, the same way DICE's other opt-in
 * capabilities work, and reports no declared-vs-previous comparison at all when the store doesn't.
 * Silence about a baseline nobody tracks is the honest answer; guessing one from write order is how
 * a dry, scoped, or interrupted write gets mistaken for a finished sweep.
 *
 * Neither method has a default body, and that is the whole point. A forwarding default would make
 * every store look like it tracked a baseline while answering with write order, so a host would
 * read a confident wrong comparison and never know.
 */
interface SweptBaselineStore : MetamodelVersionStore {

    /**
     * The version the last COMPLETED drift sweep reconciled against — the baseline for the next
     * declared-vs-previous comparison.
     *
     * This is a different question from [latestVersion], which answers "what's the newest stamp by
     * write order" and gets the wrong answer once a declaration cycles back to a stamp that already
     * exists: [saveVersion]'s own contract says a re-saved stamp keeps its *original* place in write
     * order, so after a schema goes `A` → `B` → `A` again, [latestVersion] still answers `B`, even
     * though the schema is back to declaring `A`. A drift check that diffed against [latestVersion]
     * would compare the reverted `A` against `B` — the wrong pair — and could miss a lossy change
     * that came back. [sweptVersion] tracks the reconciled baseline itself, moved forward only by
     * [markSwept], so it always answers the version a sweep genuinely finished comparing against,
     * whatever order the schema's stamps arrived in.
     *
     * [saveVersion] must never move this pointer. Stamping is a history write that happens on every
     * drift check, and a drift check reports without changing a proposition, so treating a stamp as
     * a sweep would retire a lossy declared change nobody had acted on yet.
     *
     * @param schemaName The schema to look up.
     * @return The reconciled baseline, or `null` if no sweep has ever completed for it.
     */
    fun sweptVersion(schemaName: String): MetamodelVersion?

    /**
     * Record [version] as the new reconciled baseline for its schema.
     *
     * **A completed sweep is the only thing that may move the baseline.** Call this after a
     * deliberate sweep has handled every candidate it was going to touch, across the whole schema,
     * and nowhere else. Three writes look tempting and are all wrong:
     *
     * - a **drift check**, which reports and touches no proposition, so the lossy declared change it
     *   found is still waiting for somebody to act on it;
     * - a sweep **scoped to one context**, which reconciled that context alone and would strand
     *   every other context against a change nothing ever swept them for;
     * - an **interrupted** sweep, where marking before the last candidate is handled makes a crash
     *   look like a finished reconciliation.
     *
     * Marking after a sweep that found nothing to quarantine is correct: "nothing needed doing" is a
     * completed reconciliation against that declaration.
     *
     * Sweeps of one schema must not overlap. The store records whichever completion arrives last,
     * so a sweep that started against an older declaration and finished after a newer one would
     * move the baseline back to the older stamp, and the next check would report changes that were
     * already swept. The call site runs sweeps of a schema one at a time.
     *
     * @param version The version to record as reconciled.
     */
    fun markSwept(version: MetamodelVersion)
}
