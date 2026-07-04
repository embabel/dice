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
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.Reader

/**
 * Jackson-backed implementation of [KnowledgeBundleImporter].
 *
 * Uses `jacksonObjectMapper().findAndRegisterModules()` — the same configuration used
 * throughout the library — so that `Instant` fields on propositions deserialise correctly.
 *
 * Format-version checking happens before the payload is bound to the model or written to the
 * store, so an unrecognised [KnowledgeBundle.formatVersion] is always a clean no-op — even when a
 * newer version also changed the payload shape. The check is an exact match against
 * [supportedVersions]: there's no coercion of a missing or absent `formatVersion` to "the current
 * version" — a bundle that doesn't name its version explicitly is rejected the same as one naming
 * the wrong version. There's no installed base predating this library, so there's no older shape
 * to bridge to.
 *
 * Each proposition must belong to the bundle's own context; one carrying a different `contextId` is
 * refused (counted as rejected, with a note) rather than imported, so a bundle can't leak facts
 * across the context boundary.
 *
 * Bundles larger than [maxBundleBytes] are rejected to guard against resource exhaustion from
 * untrusted input; the default cap is 50 MB. [importFromString] checks the UTF-8 byte length up
 * front, while [importFromStream] and [importFromReader] cap the bytes/characters they read so an
 * oversized stream is aborted mid-parse rather than fully materialised. All three return
 * [BundleImportOutcome.ParseFailure] when the limit is exceeded.
 *
 * @param supportedVersions The set of [KnowledgeBundle.formatVersion] strings this
 *   instance accepts. Defaults to the single current version ([KnowledgeBundle.FORMAT_VERSION]).
 * @param maxBundleBytes Maximum serialized bundle size accepted across all import paths.
 *   Bundles exceeding this limit return [BundleImportOutcome.ParseFailure]. Defaults to
 *   [DEFAULT_MAX_BUNDLE_BYTES] (50 MB).
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
        /** Default upper bound on accepted bundle size across all import paths: 50 MB. */
        const val DEFAULT_MAX_BUNDLE_BYTES: Int = 50 * 1024 * 1024
    }

    override fun importFromString(
        serialised: String,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy,
    ): BundleImportOutcome {
        // Measure the actual UTF-8 byte size, not String.length (which counts UTF-16 code units and
        // would under-count multi-byte characters, letting an over-limit bundle slip past the guard).
        val byteLength = serialised.toByteArray(Charsets.UTF_8).size
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

        val tree = try {
            mapper.readTree(serialised)
        } catch (ex: Exception) {
            logger.debug("Failed to parse bundle: {}", ex.message)
            return BundleImportOutcome.ParseFailure(
                reason = ex.message ?: "unknown parse error",
                cause = ex,
            )
        }

        return importFromTree(tree, store, conflictPolicy)
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
        val tree = try {
            // Cap the bytes read so an untrusted stream can't exhaust memory; matches the byte
            // limit importFromString enforces up front.
            mapper.readTree(BoundedInputStream(inputStream, maxBundleBytes))
        } catch (ex: Exception) {
            logger.debug("Failed to parse bundle from stream: {}", ex.message)
            return BundleImportOutcome.ParseFailure(
                reason = ex.message ?: "unknown parse error",
                cause = ex,
            )
        }
        return importFromTree(tree, store, conflictPolicy)
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
        val tree = try {
            // Cap the UTF-8 bytes the characters would encode to, so this path enforces the same
            // byte limit as the string and stream paths instead of letting multibyte content through.
            mapper.readTree(BoundedReader(reader, maxBundleBytes))
        } catch (ex: Exception) {
            logger.debug("Failed to parse bundle from reader: {}", ex.message)
            return BundleImportOutcome.ParseFailure(
                reason = ex.message ?: "unknown parse error",
                cause = ex,
            )
        }
        return importFromTree(tree, store, conflictPolicy)
    }

    /**
     * Reject anything but an exact [supportedVersions] match before binding the payload to the model.
     *
     * The JSON is read into a generic tree first (schema-agnostic), `formatVersion` is checked, and
     * only a supported version is then mapped to a [KnowledgeBundle]. So a newer bundle whose payload
     * shape has changed is rejected cleanly as [BundleImportOutcome.UnknownFormatVersion] rather than
     * failing as a [BundleImportOutcome.ParseFailure] on the unfamiliar payload — the store is never
     * touched for a version we don't understand.
     */
    private fun importFromTree(
        tree: JsonNode,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy,
    ): BundleImportOutcome {
        // Empty or null input (an empty file, an empty HTTP body, or the literal "null") parses to a
        // missing/null node rather than throwing. Reject it cleanly here: otherwise it slips through the
        // version gate as "absent" and binds to a null bundle, which NPEs in the import loop.
        if (tree.isMissingNode || tree.isNull) {
            logger.debug("Rejecting empty or null bundle content")
            return BundleImportOutcome.ParseFailure(reason = "bundle content is empty")
        }

        // Single-version, exact-match gate: a bundle must name its formatVersion explicitly and it
        // must be one this importer supports. There is no defaulting of an absent field to "the
        // current version" and no coercion of a non-textual value (e.g. a numeric 2 from a future
        // producer) — every value other than an exact string match is rejected the same way, so a
        // bundle that doesn't declare its version is treated no differently from one declaring the
        // wrong one.
        val versionNode = tree.get("formatVersion")
        val version = if (versionNode == null || versionNode.isNull) null else versionNode.asText()
        if (version == null || version !in supportedVersions) {
            logger.debug(
                "Rejecting bundle with unrecognised formatVersion '{}'; supported: {}",
                version,
                supportedVersions,
            )
            return BundleImportOutcome.UnknownFormatVersion(
                foundVersion = version ?: "",
                supportedVersions = supportedVersions,
            )
        }

        val bundle: KnowledgeBundle? = try {
            mapper.treeToValue(tree, KnowledgeBundle::class.java)
        } catch (ex: Exception) {
            logger.debug("Failed to bind bundle payload: {}", ex.message)
            return BundleImportOutcome.ParseFailure(
                reason = ex.message ?: "unknown parse error",
                cause = ex,
            )
        }
        // treeToValue can return null (e.g. a JSON null that didn't trip the guard above) without
        // throwing; guard it so the never-throw contract holds instead of NPE-ing downstream.
        if (bundle == null) {
            logger.debug("Bundle payload bound to null")
            return BundleImportOutcome.ParseFailure(reason = "bundle content did not bind to a KnowledgeBundle")
        }

        return importParsedBundle(bundle, store, conflictPolicy)
    }

    /**
     * Runs the proposition import loop on a parsed, version-checked bundle.
     */
    private fun importParsedBundle(
        bundle: KnowledgeBundle,
        store: PropositionStore,
        conflictPolicy: ImportConflictPolicy,
    ): BundleImportOutcome {
        var imported = 0
        var overwritten = 0
        var skipped = 0
        var rejected = 0
        val notes = mutableListOf<PropositionImportNote>()
        // IDs already processed in THIS bundle. A bundle may legally carry the same ID twice
        // (KnowledgeBundle.from leaves dedup to the caller). Without this guard the repeat would
        // be written — and counted — a second time under OVERWRITE; instead we skip it with a note
        // so the counts reflect distinct propositions, not raw entries.
        val seenInBundle = mutableSetOf<String>()

        for (proposition in bundle.propositions) {
            // Enforce the context boundary on the way in. A bundle is scoped to one context; a
            // proposition carrying a different contextId would otherwise be saved under its own
            // context, leaking facts across the boundary. Refuse it rather than import it.
            if (proposition.contextId != bundle.contextId) {
                rejected++
                notes += PropositionImportNote(
                    propositionId = proposition.id,
                    reason = "proposition belongs to context '${proposition.contextId.value}', not the " +
                        "bundle's context '${bundle.contextId.value}'; refused to cross the context boundary",
                )
                continue
            }

            if (!seenInBundle.add(proposition.id)) {
                skipped++
                notes += PropositionImportNote(
                    propositionId = proposition.id,
                    reason = "duplicate ID within the same bundle; only the first occurrence is imported",
                )
                continue
            }

            try {
                when (conflictPolicy) {
                    ImportConflictPolicy.SKIP_EXISTING -> {
                        // Atomic insert-once: the store writes only if the id was absent, so the
                        // skip can't be lost to a concurrent insert between a check and a write.
                        if (store.saveIfAbsent(proposition) != null) {
                            imported++
                        } else {
                            skipped++
                            notes += PropositionImportNote(
                                propositionId = proposition.id,
                                reason = "proposition already exists in store; skipped per $conflictPolicy policy",
                            )
                        }
                    }

                    ImportConflictPolicy.OVERWRITE -> {
                        val existing = store.findById(proposition.id)
                        store.save(proposition)
                        if (existing != null) {
                            // Pre-existing copy replaced — a destructive write, so it leaves its own
                            // note and count instead of hiding among the net-new imports.
                            overwritten++
                            notes += PropositionImportNote(
                                propositionId = proposition.id,
                                reason = "replaced existing proposition per $conflictPolicy policy",
                            )
                        } else {
                            imported++
                        }
                    }
                }
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
            "Bundle import complete: {} imported, {} overwritten, {} skipped, {} rejected, context={}",
            imported,
            overwritten,
            skipped,
            rejected,
            bundle.contextId,
        )

        return BundleImportOutcome.Success(
            bundle = bundle,
            result = ImportResult(
                imported = imported,
                overwritten = overwritten,
                skipped = skipped,
                rejected = rejected,
                notes = notes,
            ),
        )
    }
}

/** Raised by the bounded stream/reader wrappers once input passes the size cap. */
private class BundleSizeExceededException(limit: Int) :
    IOException("bundle size exceeds maximum allowed $limit bytes")

/**
 * Wraps an input stream and aborts the read once more than [limit] bytes have been consumed,
 * so a huge untrusted stream can't be fully materialised into a JSON tree.
 */
private class BoundedInputStream(
    private val delegate: InputStream,
    private val limit: Int,
) : InputStream() {
    private var count = 0L

    private fun track(read: Int) {
        if (read > 0) {
            count += read
            if (count > limit) throw BundleSizeExceededException(limit)
        }
    }

    override fun read(): Int = delegate.read().also { if (it >= 0) track(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len).also { track(it) }

    override fun close() = delegate.close()
}

/**
 * Wraps a reader and aborts once the characters consumed would encode to more than [limit] UTF-8
 * bytes. A character can be up to several bytes, so counting characters would let a multibyte
 * payload sail past a byte cap; instead we sum each character's UTF-8 byte length (surrogate halves
 * counted as 3 each — a safe over-estimate) so the reader path enforces the same byte limit as the
 * string and stream paths.
 */
private class BoundedReader(
    private val delegate: Reader,
    private val limit: Int,
) : Reader() {
    private var byteCount = 0L

    override fun read(cbuf: CharArray, off: Int, len: Int): Int = delegate.read(cbuf, off, len).also { read ->
        if (read > 0) {
            for (i in off until off + read) {
                byteCount += utf8ByteLength(cbuf[i])
            }
            if (byteCount > limit) throw BundleSizeExceededException(limit)
        }
    }

    override fun close() = delegate.close()

    /** Conservative UTF-8 byte length of a single UTF-16 code unit; surrogate halves count as 3. */
    private fun utf8ByteLength(ch: Char): Int = when {
        ch.code < 0x80 -> 1
        ch.code < 0x800 -> 2
        else -> 3
    }
}
