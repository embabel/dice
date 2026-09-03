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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProvenanceEvidenceKeyTest {

    private val locator = UriLocator("https://example.com/source")

    /**
     * The exact bytes of `v1`, written out. Every other test here checks `encode` against `matches`
     * from the same build, so a coordinated edit to both would keep them all green while orphaning
     * every `entryKey` already stored on a `DERIVED_FROM` edge and every reference already recorded
     * with a fold. This is the only assertion that fails when the format moves under a `v1` label.
     *
     * Changing the format means a new version prefix and a story for the keys already written, not an
     * edit to this string. The same worked example appears in `docs/design/source-revisions.md`.
     */
    @Test
    fun `v1 encodes to exactly these bytes`() {
        val entry = ProvenanceEntry(
            locator = UriLocator("https://a"),
            sourceRevision = "r1",
            chunkId = "c",
            startOffset = 1,
            endOffset = 2,
            contentHash = "h",
        )

        assertThat(ProvenanceEvidenceKey.encode(entry))
            .isEqualTo("dice-provenance:v1:13:uri:https://a2:r11:c1:11:21:h")
    }

    @Test
    fun `full evidence identity participates in encoding`() {
        val entry = ProvenanceEntry(
            locator = locator,
            sourceRevision = "r1",
            chunkId = "chunk",
            startOffset = 3,
            endOffset = 17,
            contentHash = "sha256:abc",
        )
        val variants = listOf(
            entry.copy(locator = UriLocator("https://example.com/other")),
            entry.copy(sourceRevision = "r2"),
            entry.copy(chunkId = "other"),
            entry.copy(startOffset = 4),
            entry.copy(endOffset = 18),
            entry.copy(contentHash = "sha256:def"),
        )
        val encoded = ProvenanceEvidenceKey.encode(entry)

        assertThat(ProvenanceEvidenceKey.matches(entry, encoded)).isTrue()
        assertThat(variants.map(ProvenanceEvidenceKey::encode) + encoded)
            .doesNotHaveDuplicates()
        variants.forEach {
            assertThat(ProvenanceEvidenceKey.matches(it, encoded)).isFalse()
        }
    }

    @Test
    fun `null and literal null remain distinct without delimiter or unicode collisions`() {
        val revisionless = ProvenanceEntry(
            locator = UriLocator("https://example.com/a:|💾"),
            chunkId = "null:|雪",
            contentHash = "",
        )
        val literalNull = revisionless.copy(sourceRevision = "null")
        val revisionlessKey = ProvenanceEvidenceKey.encode(revisionless)
        val literalNullKey = ProvenanceEvidenceKey.encode(literalNull)

        assertThat(revisionlessKey).isNotEqualTo(literalNullKey)
        assertThat(ProvenanceEvidenceKey.matches(revisionless, revisionlessKey)).isTrue()
        assertThat(ProvenanceEvidenceKey.matches(literalNull, literalNullKey)).isTrue()
        assertThat(ProvenanceEvidenceKey.matches(revisionless, literalNullKey)).isFalse()
        assertThat(ProvenanceEvidenceKey.matches(literalNull, revisionlessKey)).isFalse()
    }

    @Test
    fun `revision values and legacy raw keys match conservatively`() {
        val revisionless = ProvenanceEntry(locator = locator)
        val revisionOne = revisionless.copy(sourceRevision = "r1")
        val revisionTwo = revisionless.copy(sourceRevision = "r2")

        assertThat(ProvenanceEvidenceKey.matches(revisionless, locator.key())).isTrue()
        assertThat(ProvenanceEvidenceKey.matches(revisionOne, locator.key())).isFalse()
        assertThat(ProvenanceEvidenceKey.matches(revisionTwo, locator.key())).isFalse()
        assertThat(
            ProvenanceEvidenceKey.matches(
                revisionOne,
                ProvenanceEvidenceKey.encode(revisionTwo),
            )
        ).isFalse()
    }

    @Test
    fun `truncated malformed and unknown version keys fail closed`() {
        val entry = ProvenanceEntry(
            locator = locator,
            sourceRevision = "r1",
            chunkId = "chunk",
        )
        val encoded = ProvenanceEvidenceKey.encode(entry)
        val malformed = listOf(
            "dice-provenance:v1:",
            "dice-provenance:v1::",
            "dice-provenance:v1:-2:",
            "dice-provenance:v1:01:x",
            "dice-provenance:v1:１:x",
            "dice-provenance:v1:999999999999999999999:",
            "$encoded!",
        )

        for (end in "dice-provenance:v1:".length until encoded.length) {
            assertThat(ProvenanceEvidenceKey.matches(entry, encoded.substring(0, end))).isFalse()
        }
        malformed.forEach {
            assertThat(ProvenanceEvidenceKey.matches(entry, it)).isFalse()
        }
        assertThat(
            ProvenanceEvidenceKey.matches(
                entry.copy(
                    locator = UriLocator("dice-provenance:v2:legacy-looking"),
                    sourceRevision = null,
                ),
                "dice-provenance:v2:legacy-looking",
            )
        ).isFalse()
    }
}
