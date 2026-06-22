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

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext

/**
 * Builds [ProvenanceEntry] values from extraction inputs.
 *
 * The pipeline calls this once per processed [Chunk] so extracted propositions
 * carry rich grounding (locator + chunk id + optional content hash) alongside
 * the legacy [com.embabel.dice.proposition.Proposition.grounding] chunk-id list.
 *
 * Locator resolution order:
 * 1. [SourceAnalysisContext.defaultSourceLocator] when the run has a known source
 * 2. Chunk metadata (`source_uri`, `source_path`, `connector_id` + `external_id`)
 * 3. Parsed chunk / parent id (`https://...`, `email:thread`, `file:path`, …)
 * 4. [ConnectorRef] fallback using the chunk id as the external reference
 */
object ChunkProvenanceFactory {

    fun entryFor(
        chunk: Chunk,
        context: SourceAnalysisContext,
        contentHash: String? = null,
    ): ProvenanceEntry =
        ProvenanceEntry(
            locator = resolveLocator(chunk, context),
            chunkId = chunk.id,
            contentHash = contentHash,
        )

    internal fun resolveLocator(chunk: Chunk, context: SourceAnalysisContext): SourceLocator {
        context.defaultSourceLocator?.let { return it }

        val metadata = chunk.metadata
        stringMetadata(metadata, "source_uri")?.let { return UriLocator(it) }
        stringMetadata(metadata, "source_path")?.let { return FileLocator(it) }
        val connectorId = stringMetadata(metadata, "connector_id")
        val externalId = stringMetadata(metadata, "external_id")
        if (connectorId != null && externalId != null) {
            return ConnectorRef(connectorId, externalId)
        }

        sequenceOf(chunk.id, chunk.parentId)
            .filter { it.isNotBlank() }
            .mapNotNull(::parseSourceKey)
            .firstOrNull()
            ?.let { return it }

        return ConnectorRef(connectorId = "source", externalId = chunk.id)
    }

    private fun parseSourceKey(key: String): SourceLocator? = when {
        key.startsWith("http://") || key.startsWith("https://") -> UriLocator(key)
        key.startsWith("file:") -> FileLocator(key.removePrefix("file:"))
        key.startsWith("url:") -> {
            val rest = key.removePrefix("url:")
            if (rest.startsWith("http://") || rest.startsWith("https://")) UriLocator(rest)
            else ConnectorRef("url", rest)
        }
        ':' in key -> {
            val separator = key.indexOf(':')
            val prefix = key.substring(0, separator)
            val rest = key.substring(separator + 1)
            if (prefix.isNotBlank() && rest.isNotBlank()) ConnectorRef(prefix, rest) else null
        }
        else -> null
    }

    private fun stringMetadata(metadata: Any?, key: String): String? {
        if (metadata !is Map<*, *>) return null
        return (metadata[key] as? String)?.takeIf { it.isNotBlank() }
    }
}
