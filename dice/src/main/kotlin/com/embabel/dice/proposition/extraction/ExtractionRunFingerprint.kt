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
package com.embabel.dice.proposition.extraction

import org.jetbrains.annotations.ApiStatus
import java.security.MessageDigest
import java.time.Instant

/**
 * The digest a store compares two terminal writes by.
 *
 * A run store keeps the fingerprint of the write that terminalized a run. A second write under the
 * same key either matches it — a retry, replayed as success — or does not, and is rejected. The
 * whole mechanism rests on two payloads that mean the same thing producing the same bytes, so the
 * encoding is specified here rather than left to whatever a serializer happens to emit.
 *
 * ## Why not JSON
 *
 * RFC 8785 exists because naive JSON serialization is not byte-stable. Three failure modes, all of
 * which would surface here as a correct retry being rejected:
 *
 * - **Key order.** Most serializers emit fields in declaration or reflection order, and neither is
 *   guaranteed stable across versions of the code or the library.
 * - **Number rendering.** The same value can serialize as `1`, `1.0`, `1e0` or `1.0E+0` depending
 *   on the writer, and floating-point round-tripping differs between implementations.
 * - **Insignificant text.** Whitespace, escaping choices and Unicode normalization all move the
 *   bytes without moving the meaning.
 *
 * This encoding is the one DICE already uses for `MetamodelVersion.contentHash`, applied to a
 * different payload: length-prefixed tokens, count-prefixed collections, sorted where order carries
 * no meaning, SHA-256, lowercase hex.
 *
 * ## The rules
 *
 * 1. **Every token is length-prefixed**, `<length>:<token>`. A delimiter-joined encoding lets
 *    `["a;b"]` and `["a", "b"]` hash the same, which hides a real difference. Length prefixes make
 *    that collision unreachable whatever characters a token happens to carry.
 * 2. **Every collection is preceded by its element count**, so a shorter list can never be a prefix
 *    of a longer one.
 * 3. **Fields are emitted as `(name, value)` pairs sorted by name**, so the bytes do not depend on
 *    the order the fields happen to be declared in.
 * 4. **A collection whose order carries no meaning is sorted.** The failure list is the case that
 *    matters: two coordinators that recorded the same failures in a different order made the same
 *    terminal write. The order is Kotlin's natural `String` order, which compares UTF-16 code units
 *    — not UTF-8 byte order, and not a locale collation. Any total order would do for correctness;
 *    naming this one matters because a backend re-implementing the sort in a query would pick a
 *    different one and produce a different digest. It should not re-implement it at all: the store
 *    records the string the transition already computed.
 * 5. **Instants render as `<epochSecond>.<nanos padded to 9>`.** Fixed width, and independent of
 *    `java.time`'s own formatting. `Instant.toString()` varies its precision with the value —
 *    `…:47Z` for a whole second, `…:47.500Z` for half of one — so the encoded length moves with the
 *    data and a persisted digest would depend on a formatting rule DICE does not own. Number
 *    rendering is the equivalent hazard in JSON and is most of what RFC 8785 is about.
 * 6. **Absent is its own marker.** A null renders as `-`, never as an empty string, so "the caller
 *    said nothing" and "the caller said empty" stay distinguishable.
 * 7. **The digest is SHA-256, rendered lowercase hex**, and the encoded input carries a version
 *    tag. A reader meeting a version it does not know matches nothing rather than guessing.
 *
 * ## This is a persisted format
 *
 * The digest is stored beside the run. Changing the encoding makes every recorded fingerprint
 * unmatchable, so a correct retry against an old run would be rejected as an incompatible rewrite.
 * `ExtractionRunFingerprintTest` pins the digest of a fixed payload with a literal assertion:
 * changing the encoding means changing that literal deliberately.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
object ExtractionRunFingerprint {

    /** Version tag on the terminal-write encoding. */
    const val TERMINAL_VERSION: String = "xrun-terminal:v1"

    /** What an absent value encodes as, kept distinct from any value a caller could supply. */
    private const val ABSENT = "-"

    /**
     * The digest of one terminal write: the status it asserts, when the run finished, the counts it
     * records, and the failures it records.
     *
     * The run itself is deliberately not part of this. A coordinator that records another invocation
     * between a failed terminal write and its retry made the same terminal write both times, and
     * folding the run's invocation list in would turn that correct retry into a rejected conflict.
     */
    @JvmStatic
    fun ofTerminal(
        status: ExtractionRunStatus,
        finishedAt: Instant,
        counts: ExtractionRunCounts?,
        failures: List<ExtractionFailure>?,
    ): String {
        val payload = fields(
            "counts" to (counts?.let(::encodeCounts) ?: ABSENT),
            "failures" to (failures?.let(::encodeFailures) ?: ABSENT),
            "finishedAt" to encodeInstant(finishedAt),
            "status" to status.name,
        )
        return digest(TERMINAL_VERSION + "|" + payload)
    }

    private fun encodeCounts(counts: ExtractionRunCounts): String = fields(
        "chunksProcessed" to counts.chunksProcessed.toString(),
        "entitiesResolved" to counts.entitiesResolved.toString(),
        "propositionsExtracted" to counts.propositionsExtracted.toString(),
        "propositionsPersisted" to counts.propositionsPersisted.toString(),
        "propositionsRejected" to counts.propositionsRejected.toString(),
        "sourcesRead" to counts.sourcesRead.toString(),
    )

    private fun encodeFailures(failures: List<ExtractionFailure>): String {
        val encoded = failures.map { failure ->
            fields(
                "at" to encodeInstant(failure.at),
                "code" to failure.code.name,
                "invocation" to (
                    failure.invocation
                        ?.let { "${it.invocationIndex}/${it.attempt}" }
                        ?: ABSENT
                    ),
                "measure" to (
                    failure.measure
                        ?.let { "${it.quantity.name}=${it.value}" }
                        ?: ABSENT
                    ),
                "providerStatus" to (failure.providerStatus?.toString() ?: ABSENT),
                "stage" to (failure.stage?.name ?: ABSENT),
            )
        }.sorted()
        return buildString {
            append(encoded.size).append('|')
            encoded.forEach { appendSized(it) }
        }
    }

    /**
     * Fixed-width rendering: seconds since the epoch, a dot, then nanoseconds padded to nine
     * digits. Two instants that compare equal always render identically, and no instant renders
     * two ways.
     */
    private fun encodeInstant(instant: Instant): String =
        "${instant.epochSecond}.${instant.nano.toString().padStart(9, '0')}"

    /** Emit `(name, value)` pairs sorted by name, each half length-prefixed. */
    private fun fields(vararg pairs: Pair<String, String>): String = buildString {
        append(pairs.size).append('|')
        pairs.sortedBy { it.first }.forEach { (name, value) ->
            appendSized(name)
            appendSized(value)
        }
    }

    private fun StringBuilder.appendSized(token: String) {
        append(token.length).append(':').append(token)
    }

    private fun digest(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> HEX[(byte.toInt() shr 4) and 0xF].toString() + HEX[byte.toInt() and 0xF] }

    private const val HEX = "0123456789abcdef"
}
