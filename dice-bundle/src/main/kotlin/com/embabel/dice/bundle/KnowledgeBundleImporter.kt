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
package com.embabel.dice.bundle

import com.embabel.dice.proposition.PropositionStore
import java.io.InputStream
import java.io.Reader

/**
 * Policy governing what to do when a proposition being imported already exists
 * (by ID) in the target [PropositionStore].
 */
enum class ImportConflictPolicy {

    /**
     * Leave the existing proposition unchanged and count the incoming one as skipped.
     */
    SKIP_EXISTING,

    /**
     * Replace the existing proposition with the incoming one.
     */
    OVERWRITE,
}

/**
 * A note attached to a single proposition outcome during import. Carries the
 * proposition ID and a human-readable explanation of why it was not imported
 * as-is (e.g., skipped due to conflict, rejected due to format error).
 *
 * @property propositionId The ID of the affected proposition.
 * @property reason Human-readable explanation.
 */
data class PropositionImportNote(
    val propositionId: String,
    val reason: String,
)

/**
 * Immutable summary of a completed import operation.
 *
 * `imported` counts only net-new writes; replacing an existing proposition under
 * [ImportConflictPolicy.OVERWRITE] is counted separately as `overwritten` so a caller
 * can tell a fresh load from a re-load (e.g. when auditing idempotency).
 *
 * @property imported Number of net-new propositions written to the store (no prior copy existed).
 * @property overwritten Number of pre-existing propositions replaced under OVERWRITE.
 * @property skipped Number of propositions skipped (already present under SKIP_EXISTING, or a
 *   duplicate ID seen earlier in the same bundle).
 * @property rejected Number of propositions that could not be imported due to errors.
 * @property notes Per-proposition notes for overwritten, skipped, and rejected items.
 */
data class ImportResult(
    val imported: Int,
    val overwritten: Int,
    val skipped: Int,
    val rejected: Int,
    val notes: List<PropositionImportNote> = emptyList(),
) {
    /** Total number of propositions the bundle contained. */
    val total: Int get() = imported + overwritten + skipped + rejected
}

/**
 * Outcome of attempting to import a serialised bundle.
 *
 * Failure is modelled as a sealed type rather than an exception, same as `ProjectionResult` elsewhere in the library.
 */
sealed interface BundleImportOutcome {

    /**
     * The bundle was parsed and its propositions were processed according to the
     * conflict policy. Check [result] for per-proposition counts.
     *
     * @property bundle The deserialised bundle.
     * @property result Aggregate import counts.
     */
    data class Success(
        val bundle: KnowledgeBundle,
        val result: ImportResult,
    ) : BundleImportOutcome

    /**
     * The bundle's [KnowledgeBundle.formatVersion] is not recognised by this importer.
     * No propositions have been written to the store.
     *
     * @property foundVersion The version string encountered in the bundle.
     * @property supportedVersions Version strings this importer can handle.
     */
    data class UnknownFormatVersion(
        val foundVersion: String,
        val supportedVersions: Set<String>,
    ) : BundleImportOutcome {
        override fun toString(): String =
            "UnknownFormatVersion(found=$foundVersion, supported=$supportedVersions)"
    }

    /**
     * The serialised input could not be parsed (e.g., malformed JSON, missing
     * required fields). No propositions have been written to the store.
     *
     * @property reason Human-readable description of the parse failure.
     * @property cause The underlying exception, if available.
     */
    data class ParseFailure(
        val reason: String,
        val cause: Throwable? = null,
    ) : BundleImportOutcome {
        override fun toString(): String =
            "ParseFailure($reason${cause?.let { ", caused by ${it::class.simpleName}: ${it.message}" } ?: ""})"
    }
}

/**
 * SPI for deserialising a serialised [KnowledgeBundle] and loading its propositions
 * into a [PropositionStore].
 *
 * Implementations must:
 * - check [KnowledgeBundle.formatVersion] before processing any payload and return
 *   [BundleImportOutcome.UnknownFormatVersion] for unrecognised versions;
 * - apply the given [ImportConflictPolicy] when a proposition ID already exists in the store;
 * - return [BundleImportOutcome.ParseFailure] for malformed input rather than throwing.
 *
 * [ImportConflictPolicy.SKIP_EXISTING] writes through [PropositionStore.saveIfAbsent], so the skip is
 * atomic — and safe under concurrent imports into the same store — for any store that implements that
 * primitive atomically (the in-memory and Neo4j-backed stores do). Against a store that only inherits
 * the non-atomic SPI default, the skip falls back to best-effort.
 *
 * The default implementation is [support.JacksonKnowledgeBundleImporter].
 */
interface KnowledgeBundleImporter {

    /**
     * Deserialise the JSON [serialised] string and import its propositions into [store].
     *
     * @param serialised The JSON string produced by a [KnowledgeBundleExporter].
     * @param store Target store into which propositions are written.
     * @param conflictPolicy What to do when a proposition ID already exists in [store].
     * @return A [BundleImportOutcome] describing what happened; never throws.
     */
    fun importFromString(
        serialised: String,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
    ): BundleImportOutcome

    /**
     * Read a bundle from [inputStream] and import its propositions into [store].
     * The stream is NOT closed by this method.
     *
     * The default implementation buffers the stream to a string and delegates to
     * [importFromString]. Implementations may override this with a true streaming
     * path to avoid double-buffering.
     *
     * @param inputStream Source of the serialised bundle.
     * @param store Target store into which propositions are written.
     * @param conflictPolicy What to do when a proposition ID already exists in [store].
     * @return A [BundleImportOutcome] describing what happened; never throws.
     */
    fun importFromStream(
        inputStream: InputStream,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
    ): BundleImportOutcome = importFromString(
        inputStream.bufferedReader().readText(),
        store,
        conflictPolicy,
    )

    /**
     * Read a bundle from [reader] and import its propositions into [store].
     * The reader is NOT closed by this method.
     *
     * The default implementation buffers the reader to a string and delegates to
     * [importFromString]. Implementations may override this with a true streaming
     * path to avoid double-buffering.
     *
     * @param reader Source of the serialised bundle.
     * @param store Target store into which propositions are written.
     * @param conflictPolicy What to do when a proposition ID already exists in [store].
     * @return A [BundleImportOutcome] describing what happened; never throws.
     */
    fun importFromReader(
        reader: Reader,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
    ): BundleImportOutcome = importFromString(
        reader.readText(),
        store,
        conflictPolicy,
    )
}
