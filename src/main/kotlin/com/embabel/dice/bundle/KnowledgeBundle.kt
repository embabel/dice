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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

/**
 * A versioned, serializable envelope carrying a set of propositions from one context,
 * together with enough metadata to re-import them faithfully into another store.
 *
 * Provenance (grounding entries, chunk IDs) is embedded directly in each [Proposition]
 * and travels with it; no additional provenance wrappers are required.
 *
 * Use [from] to construct a bundle. The bundle is immutable; all fields are read-only.
 *
 * Unknown JSON fields are ignored on deserialization so that a consumer running an older
 * version of this library can still import bundles produced by a newer version that adds
 * fields. Two bundles assembled from the same propositions at different times will NOT be
 * `==` because [createdAt] is part of structural equality.
 *
 * @property formatVersion Schema version of this bundle format. Importers must reject
 *   bundles with an unrecognised version before touching the store.
 * @property contextId The context from which these propositions were exported.
 * @property propositions The full [Proposition] objects being transported.
 * @property createdAt When this bundle was assembled. Included in structural equality —
 *   two bundles from the same data assembled at different times will not be `==`.
 * @property metadata Optional free-form string metadata (e.g. author, description, tags).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KnowledgeBundle(
    val formatVersion: String = FORMAT_VERSION,
    val contextId: ContextId,
    val propositions: List<Proposition>,
    val createdAt: Instant = Instant.now(),
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {

        /**
         * Current bundle format version. Increment this constant when the serialised
         * shape of [KnowledgeBundle] or its payload changes in a backward-incompatible way.
         */
        const val FORMAT_VERSION: String = "1.0"

        /**
         * Assemble a bundle from a context and a collection of propositions.
         *
         * Two bundles assembled from the same propositions at different times will NOT be
         * `==` unless you supply the same [createdAt] value to both — [KnowledgeBundle] is
         * a data class and [createdAt] participates in structural equality.
         *
         * @param contextId The source context.
         * @param propositions The propositions to include; duplicates (by ID) are preserved
         *   as supplied — deduplication is the caller's responsibility.
         * @param metadata Optional free-form metadata entries.
         * @param createdAt Assembly timestamp; defaults to now. Supply an explicit value
         *   when deterministic equality across two bundles from the same data is required
         *   (e.g. caching or idempotency checks).
         * @return A new [KnowledgeBundle] stamped with [createdAt].
         */
        @JvmStatic
        @JvmOverloads
        fun from(
            contextId: ContextId,
            propositions: List<Proposition>,
            metadata: Map<String, String> = emptyMap(),
            createdAt: Instant = Instant.now(),
        ): KnowledgeBundle = KnowledgeBundle(
            contextId = contextId,
            propositions = propositions,
            metadata = metadata,
            createdAt = createdAt,
        )

        /**
         * Java-friendly overload accepting the context ID as a plain string.
         *
         * @param contextIdValue The source context ID value.
         * @param propositions The propositions to include.
         * @param metadata Optional free-form metadata entries.
         * @param createdAt Assembly timestamp; defaults to now.
         */
        @JvmStatic
        @JvmOverloads
        fun from(
            contextIdValue: String,
            propositions: List<Proposition>,
            metadata: Map<String, String> = emptyMap(),
            createdAt: Instant = Instant.now(),
        ): KnowledgeBundle = from(ContextId(contextIdValue), propositions, metadata, createdAt)
    }
}
