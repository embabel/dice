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
package com.embabel.dice.ingestion

import com.embabel.dice.spi.AuthorityTier
import com.embabel.dice.provenance.SourceLocator
import java.time.Instant

/**
 * A normalized unit of source material handed to DICE at the front door.
 *
 * Adapters parse their native formats (documents, web pages, connector payloads)
 * into already-extracted [text] *before* constructing an artifact — core never
 * parses. The artifact carries the source identity, a [SourceLocator] for
 * provenance, an optional caller-supplied [contentHash] used as the
 * deduplication key, trust metadata, and optional timestamps.
 *
 * The [locator] and [trust] fields are caller-asserted claims about the source,
 * not proofs DICE can independently verify; downstream authority resolution
 * derives tiers structurally from the locator kind.
 *
 * @property sourceId Stable source key used as the chunk parent identity and the
 *   per-artifact deduplication record key. Must not be blank.
 * @property locator Provenance reference describing where the material lives.
 * @property text Already-extracted text content. Must not be blank.
 * @property contentHash Optional caller-supplied deduplication key. When present
 *   it is authoritative for dedup; when absent the consuming handler computes one.
 * @property trust Caller-asserted authority of the source; defaults to
 *   [AuthorityTier.UNKNOWN], the fail-safe lowest authority.
 * @property createdAt Optional timestamp for when the source material was created.
 * @property ingestedAt Optional timestamp for when the material was ingested.
 */
data class IngestedArtifact @JvmOverloads constructor(
    val sourceId: String,
    val locator: SourceLocator,
    val text: String,
    val contentHash: String? = null,
    val trust: AuthorityTier = AuthorityTier.UNKNOWN,
    val createdAt: Instant? = null,
    val ingestedAt: Instant? = null,
) {

    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(text.isNotBlank()) { "text must not be blank" }
        require(contentHash == null || contentHash.isNotBlank()) { "contentHash must not be blank when present" }
    }

    companion object {
        /**
         * Start building an artifact with its source identity.
         * Entry point for the strongly-typed builder used from Java:
         *
         * ```java
         * IngestedArtifact artifact = IngestedArtifact
         *     .withSourceId("doc-1")
         *     .withLocator(new UriLocator("https://example.com/doc"))
         *     .withText("extracted text")
         *     .withTrust(AuthorityTier.SECONDARY); // optional
         * ```
         *
         * @param sourceId The stable source key for this artifact
         * @return Builder step requiring a locator
         */
        @JvmStatic
        fun withSourceId(sourceId: String): WithSourceId = WithSourceId(sourceId)
    }

    /** Builder step: has source id, needs a locator. */
    class WithSourceId internal constructor(private val sourceId: String) {
        /**
         * Set the provenance locator for the source material.
         * @param locator The locator referencing where the material lives
         * @return Builder step requiring text
         */
        fun withLocator(locator: SourceLocator): WithLocator = WithLocator(sourceId, locator)
    }

    /** Builder step: has source id and locator, needs text; yields a complete artifact. */
    class WithLocator internal constructor(
        private val sourceId: String,
        private val locator: SourceLocator,
    ) {
        /**
         * Set the already-extracted text, completing a minimal artifact.
         * @param text The extracted text content
         * @return A complete [IngestedArtifact]
         */
        fun withText(text: String): IngestedArtifact =
            IngestedArtifact(sourceId = sourceId, locator = locator, text = text)
    }

    /** Returns a copy with the deduplication content hash set. */
    fun withContentHash(contentHash: String): IngestedArtifact = copy(contentHash = contentHash)

    /** Returns a copy with the trust tier set. */
    fun withTrust(trust: AuthorityTier): IngestedArtifact = copy(trust = trust)

    /** Returns a copy with the created and ingested timestamps set. */
    @JvmOverloads
    fun withTimestamps(createdAt: Instant? = null, ingestedAt: Instant? = null): IngestedArtifact =
        copy(createdAt = createdAt, ingestedAt = ingestedAt)
}
