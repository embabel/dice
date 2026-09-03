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
package com.embabel.dice.provenance

/**
 * The stable string identity of one piece of evidence. Producers encode an entry, hand the string
 * to whoever records the fold, and [matches] later checks a live entry against it.
 *
 * Fields are length-framed in a fixed order, so no value needs escaping however many colons it
 * contains. A length of `-1` stands for null, which keeps absence distinct from every string value
 * — including the string `"null"`.
 *
 * A ref that lacks the `dice-provenance:` prefix is a legacy locator key, written before revisions
 * existed. Those match revisionless evidence only, so an old trace can never remove evidence from a
 * revision it never saw. Anything that carries the prefix and then fails to parse matches nothing.
 */
internal object ProvenanceEvidenceKey {

    private const val MAGIC_PREFIX = "dice-provenance:"
    private const val VERSION_PREFIX = "${MAGIC_PREFIX}v1:"

    fun encode(entry: ProvenanceEntry): String =
        buildString(VERSION_PREFIX.length + 64) {
            append(VERSION_PREFIX)
            appendFrame(entry.locator.key())
            appendFrame(entry.sourceRevision)
            appendFrame(entry.chunkId)
            appendFrame(entry.startOffset?.toString())
            appendFrame(entry.endOffset?.toString())
            appendFrame(entry.contentHash)
        }

    fun matches(entry: ProvenanceEntry, encoded: String): Boolean {
        if (!encoded.startsWith(MAGIC_PREFIX)) {
            return entry.sourceRevision == null && encoded == entry.locator.key()
        }
        if (!encoded.startsWith(VERSION_PREFIX)) {
            return false
        }

        val matcher = FrameMatcher(encoded, VERSION_PREFIX.length)
        return matcher.matches(entry.locator.key()) &&
                matcher.matches(entry.sourceRevision) &&
                matcher.matches(entry.chunkId) &&
                matcher.matches(entry.startOffset?.toString()) &&
                matcher.matches(entry.endOffset?.toString()) &&
                matcher.matches(entry.contentHash) &&
                matcher.isExhausted()
    }

    private fun StringBuilder.appendFrame(value: String?) {
        if (value == null) {
            append("-1:")
        } else {
            append(value.length)
            append(':')
            append(value)
        }
    }

    private class FrameMatcher(
        private val encoded: String,
        private var offset: Int,
    ) {

        fun matches(value: String?): Boolean {
            val separator = encoded.indexOf(':', offset)
            if (separator < 0) {
                return false
            }
            val length = parseLength(separator) ?: return false
            offset = separator + 1
            if (length == -1) {
                return value == null
            }
            if (value == null || length != value.length || length > encoded.length - offset) {
                return false
            }
            if (!encoded.regionMatches(offset, value, 0, length)) {
                return false
            }
            offset += length
            return true
        }

        fun isExhausted(): Boolean = offset == encoded.length

        private fun parseLength(separator: Int): Int? {
            if (separator == offset) {
                return null
            }
            if (encoded[offset] == '-') {
                return if (separator == offset + 2 && encoded[offset + 1] == '1') -1 else null
            }
            if (separator > offset + 1 && encoded[offset] == '0') {
                return null
            }

            var length = 0
            for (index in offset until separator) {
                val character = encoded[index]
                if (character !in '0'..'9') {
                    return null
                }
                val digit = character - '0'
                if (length > (Int.MAX_VALUE - digit) / 10) {
                    return null
                }
                length = length * 10 + digit
            }
            return length
        }
    }
}
