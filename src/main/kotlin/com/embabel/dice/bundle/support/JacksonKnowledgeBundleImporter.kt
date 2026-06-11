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
package com.embabel.dice.bundle.support

import com.embabel.dice.bundle.BundleImportOutcome
import com.embabel.dice.bundle.ImportConflictPolicy
import com.embabel.dice.bundle.ImportResult
import com.embabel.dice.bundle.KnowledgeBundle
import com.embabel.dice.bundle.KnowledgeBundleImporter
import com.embabel.dice.bundle.PropositionImportNote
import com.embabel.dice.proposition.PropositionStore
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.Reader

/**
 * Jackson-backed implementation of [KnowledgeBundleImporter].
 *
 * Uses `jacksonObjectMapper().findAndRegisterModules()` — the same configuration used
 * throughout the library — so that `Instant` fields on propositions deserialise correctly.
 *
 * Format-version checking happens before any payload is written to the store, so
 * an unrecognised [KnowledgeBundle.formatVersion] is always a clean no-op.
 *
 * Bundles larger than [maxBundleBytes] are rejected before deserialization to guard
 * against resource exhaustion from untrusted input. The default cap is 50 MB. This
 * guard applies to [importFromString] (checked by string length). The [importFromStream]
 * and [importFromReader] overrides use Jackson's native streaming path without
 * buffering into a String; callers passing untrusted streams should apply their own
 * length limit at the transport layer or use [importFromString] with a pre-validated string.
 *
 * @param supportedVersions The set of [KnowledgeBundle.formatVersion] strings this
 *   instance accepts. Defaults to the single current version ([KnowledgeBundle.FORMAT_VERSION]).
 * @param maxBundleBytes Maximum serialized bundle size in bytes accepted for
 *   [importFromString]. Bundles exceeding this limit return [BundleImportOutcome.ParseFailure]
 *   without parsing. Defaults to [DEFAULT_MAX_BUNDLE_BYTES] (50 MB).
 */
class JacksonKnowledgeBundleImporter @JvmOverloads constructor(
    private val supportedVersions: Set<String> = setOf(KnowledgeBundle.FORMAT_VERSION),
    private val maxBundleBytes: Int = DEFAULT_MAX_BUNDLE_BYTES,
) : KnowledgeBundleImporter {

    private val logger = LoggerFactory.getLogger(JacksonKnowledgeBundleImporter::class.java)

    // Tolerate properties the model doesn't recognise on the way in. This covers two cases:
    // bundles written by a newer library version that added fields, and computed read-only
    // getters (e.g. a fact's "current" flag) that Jackson emits but no constructor accepts.
    // An import SPI should accept an evolving format, not break on the first extra field.
    private val mapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    companion object {
        /** Default upper bound on accepted bundle size for string-based import: 50 MB. */
        const val DEFAULT_MAX_BUNDLE_BYTES: Int = 50 * 1024 * 1024
    }

    override fun importFromString(
        serialised: String,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy,
    ): BundleImportOutcome {
        val byteLength = serialised.length
        if (byteLength > maxBundleBytes) {
            logger.warn(
                "Rejecting bundle: serialized size {} bytes exceeds limit of {} bytes",
                byteLength,
                maxBundleBytes,
            )
            return BundleImportOutcome.ParseFailure(
                reason = "bundle size $byteLength bytes exceeds maximum allowed $maxBundleBytes bytes",
            )
        }

        val bundle = try {
            mapper.readValue(serialised, KnowledgeBundle::class.java)
        } catch (ex: Exception) {
            logger.debug("Failed to parse bundle: {}", ex.message)
            return BundleImportOutcome.ParseFailure(
                reason = ex.message ?: "unknown parse error",
                cause = ex,
            )
        }

        return importParsedBundle(bundle, store, conflictPolicy)
    }

    /**
     * Reads directly from [inputStream] via Jackson without buffering the whole payload
     * into a String. Use this path when the bundle arrives as an [InputStream] (file,
     * HTTP response, message broker) to avoid double-buffering overhead. The stream is
     * NOT closed by this method.
     */
    override fun importFromStream(
        inputStream: InputStream,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy,
    ): BundleImportOutcome {
        val bundle = try {
            mapper.readValue(inputStream, KnowledgeBundle::class.java)
        } catch (ex: Exception) {
            logger.debug("Failed to parse bundle from stream: {}", ex.message)
            return BundleImportOutcome.ParseFailure(
                reason = ex.message ?: "unknown parse error",
                cause = ex,
            )
        }
        return importParsedBundle(bundle, store, conflictPolicy)
    }

    /**
     * Reads directly from [reader] via Jackson without buffering the whole payload into
     * a String. The reader is NOT closed by this method.
     */
    override fun importFromReader(
        reader: Reader,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy,
    ): BundleImportOutcome {
        val bundle = try {
            mapper.readValue(reader, KnowledgeBundle::class.java)
        } catch (ex: Exception) {
            logger.debug("Failed to parse bundle from reader: {}", ex.message)
            return BundleImportOutcome.ParseFailure(
                reason = ex.message ?: "unknown parse error",
                cause = ex,
            )
        }
        return importParsedBundle(bundle, store, conflictPolicy)
    }

    /**
     * Applies the version gate and then runs the proposition import loop on an already-parsed bundle.
     */
    private fun importParsedBundle(
        bundle: KnowledgeBundle,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy,
    ): BundleImportOutcome {
        if (bundle.formatVersion !in supportedVersions) {
            logger.debug(
                "Rejecting bundle with unrecognised formatVersion '{}'; supported: {}",
                bundle.formatVersion,
                supportedVersions,
            )
            return BundleImportOutcome.UnknownFormatVersion(
                foundVersion = bundle.formatVersion,
                supportedVersions = supportedVersions,
            )
        }

        var imported = 0
        var skipped = 0
        var rejected = 0
        val notes = mutableListOf<PropositionImportNote>()

        for (proposition in bundle.propositions) {
            val existing = store.findById(proposition.id)
            if (existing != null && conflictPolicy == ImportConflictPolicy.SKIP_EXISTING) {
                skipped++
                notes += PropositionImportNote(
                    propositionId = proposition.id,
                    reason = "proposition already exists in store; skipped per $conflictPolicy policy",
                )
                continue
            }
            try {
                store.save(proposition)
                imported++
            } catch (ex: Exception) {
                logger.warn("Failed to save proposition {}: {}", proposition.id, ex.message)
                rejected++
                notes += PropositionImportNote(
                    propositionId = proposition.id,
                    reason = "save failed: ${ex.message ?: "unknown error"}",
                )
            }
        }

        logger.debug(
            "Bundle import complete: {} imported, {} skipped, {} rejected, context={}",
            imported,
            skipped,
            rejected,
            bundle.contextId,
        )

        return BundleImportOutcome.Success(
            bundle = bundle,
            result = ImportResult(
                imported = imported,
                skipped = skipped,
                rejected = rejected,
                notes = notes,
            ),
        )
    }
}
