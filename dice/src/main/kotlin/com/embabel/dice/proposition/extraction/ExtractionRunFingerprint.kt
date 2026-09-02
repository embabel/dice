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
 * ## What the digest covers
 *
 * The transition's identity: the terminal status, and when the run finished. Two writes that agree
 * on those two things are the same terminal write, and the second replays.
 *
 * The counts and failures a transition carries are data it delivers, and they stay outside the
 * digest. A run's outcome is written once — the first accepted terminal write wins, and a retry
 * carrying different numbers replays against what is already stored. Keeping them out of the digest
 * is what lets the outcome payload grow: DICE #69 adds typed product outcomes to what a terminal
 * write reports, and no field it adds can move a digest already recorded beside a run.
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
 * different payload: length-prefixed tokens, count-prefixed field sets, SHA-256, lowercase hex.
 *
 * ## The rules
 *
 * 1. **Every token is length-prefixed**, `<length>:<token>`. A delimiter-joined encoding lets
 *    `["a;b"]` and `["a", "b"]` hash the same, which hides a real difference. Length prefixes make
 *    that collision unreachable whatever characters a token happens to carry.
 * 2. **A field set is preceded by how many fields it holds**, so a shorter one can never be a
 *    prefix of a longer one.
 * 3. **Fields are emitted as `(name, value)` pairs sorted by name**, so the bytes do not depend on
 *    the order the fields happen to be declared in.
 * 4. **Instants render as `<epochSecond>.<nanos padded to 9>`.** Fixed width, and independent of
 *    `java.time`'s own formatting. `Instant.toString()` varies its precision with the value —
 *    `…:47Z` for a whole second, `…:47.500Z` for half of one — so the encoded length moves with the
 *    data and a persisted digest would depend on a formatting rule DICE does not own. Number
 *    rendering is the equivalent hazard in JSON and is most of what RFC 8785 is about.
 * 5. **The digest is SHA-256, rendered lowercase hex**, and the encoded input carries a version
 *    tag. A reader meeting a version it does not know matches nothing rather than guessing.
 *
 * ## This is a persisted format
 *
 * The digest is stored beside the run. Changing the encoding makes every recorded fingerprint
 * unmatchable, so a correct retry against an old run would be rejected as an incompatible rewrite.
 * That is what [TERMINAL_VERSION] is for, and it is why the payload is the transition's identity
 * alone: a field added to what a terminal write reports never reaches these bytes.
 * `ExtractionRunFingerprintTest` pins the digest of a fixed payload with a literal assertion:
 * changing the encoding means changing that literal deliberately.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
object ExtractionRunFingerprint {

    /**
     * Version tag on the terminal-write encoding.
     *
     * `v2` narrowed the payload to the transition's identity. `v1` also folded in the counts and
     * the failures a transition carried, which made the digest move whenever the outcome payload
     * gained a field.
     */
    const val TERMINAL_VERSION: String = "xrun-terminal:v2"

    /**
     * The digest of one terminal write: the status it asserts, and when the run finished.
     *
     * Two things stay out of it deliberately. The run, because a coordinator that records another
     * invocation between a terminal write and its retry made the same terminal write both times,
     * and folding the run's invocation list in would turn that correct retry into a rejected
     * conflict. And the counts and failures the transition carries, because those are the outcome
     * a run reports, while the digest names which terminal write this is — see the class doc.
     */
    @JvmStatic
    fun ofTerminal(status: ExtractionRunStatus, finishedAt: Instant): String {
        val payload = fields(
            "finishedAt" to encodeInstant(finishedAt),
            "status" to status.name,
        )
        return digest(TERMINAL_VERSION + "|" + payload)
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
