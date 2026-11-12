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
package com.embabel.dice.evidence

import com.embabel.agent.core.ContextId
import com.embabel.dice.provenance.ProvenanceEntry

/**
 * Identifies a stored claim, the context it lives in, and the source material it was extracted from.
 *
 * [chunkIds], [provenance] and [uri] are optional. Each `with` method returns a copy, and
 * the lists are copied on the way in.
 *
 * ```java
 * Citation citation = Citation.of("prop-42", "ctx-main")
 *     .withChunkIds(List.of("chunk-1", "chunk-2"))
 *     .withUri("https://example.com/notes");
 * ```
 *
 * ```kotlin
 * val citation = Citation.of("prop-42", ContextId("ctx-main"))
 * ```
 */
class Citation private constructor(
    /** Id of the stored claim. */
    val propositionId: String,
    /** The context the claim belongs to. */
    val contextId: ContextId,
    chunkIds: List<String>,
    provenance: List<ProvenanceEntry>,
    /** Link to the source material, for display to a person. Null when none is set. */
    val uri: String?,
) {

    /** The value of [contextId], for Java callers. */
    val contextIdValue: String = contextId.value

    /** Ids of the source chunks the claim was extracted from. */
    val chunkIds: List<String> = java.util.List.copyOf(chunkIds)

    /** Typed pointers into the source material. */
    val provenance: List<ProvenanceEntry> = java.util.List.copyOf(provenance)

    init {
        require(propositionId.isNotBlank()) { "propositionId must not be blank" }
        require(this.chunkIds.all { it.isNotBlank() }) { "chunk ids must not be blank" }
        require(uri == null || uri.isNotBlank()) { "uri must not be blank" }
    }

    /**
     * Returns a copy with [chunkIds] replaced.
     *
     * @param chunkIds ids of the source chunks
     * @return the updated citation
     * @throws IllegalArgumentException if any chunk id is blank
     */
    fun withChunkIds(chunkIds: List<String>): Citation =
        Citation(propositionId, contextId, chunkIds, provenance, uri)

    /**
     * Returns a copy with [provenance] replaced.
     *
     * @param provenance typed pointers into the source material
     * @return the updated citation
     */
    fun withProvenance(provenance: List<ProvenanceEntry>): Citation =
        Citation(propositionId, contextId, chunkIds, provenance, uri)

    /**
     * Returns a copy with [uri] set.
     *
     * @param uri link to the source material
     * @return the updated citation
     * @throws IllegalArgumentException if [uri] is blank
     */
    fun withUri(uri: String): Citation =
        Citation(propositionId, contextId, chunkIds, provenance, uri)

    override fun equals(other: Any?): Boolean =
        other is Citation && propositionId == other.propositionId &&
                contextId == other.contextId && chunkIds == other.chunkIds &&
                provenance == other.provenance && uri == other.uri

    override fun hashCode(): Int =
        java.util.Objects.hash(propositionId, contextId, chunkIds, provenance, uri)

    override fun toString(): String =
        "Citation(propositionId=$propositionId, contextId=${contextId.value}, uri=$uri)"

    companion object {

        /**
         * Creates a citation for a claim in a context, using the [ContextId] type.
         *
         * @param propositionId id of the stored claim
         * @param contextId the context the claim belongs to
         * @return the citation
         * @throws IllegalArgumentException if [propositionId] is blank
         */
        fun of(propositionId: String, contextId: ContextId): Citation =
            Citation(propositionId, contextId, emptyList(), emptyList(), null)

        /**
         * Creates a citation for a claim in a context, using the context id as a string.
         *
         * @param propositionId id of the stored claim
         * @param contextId the context id. Blank values are rejected.
         * @return the citation
         * @throws IllegalArgumentException if [propositionId] or [contextId] is blank
         */
        @JvmStatic
        fun of(propositionId: String, contextId: String): Citation =
            of(propositionId, ContextId(contextId))
    }
}
