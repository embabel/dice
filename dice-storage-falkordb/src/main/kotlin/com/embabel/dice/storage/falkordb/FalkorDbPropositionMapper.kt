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
package com.embabel.dice.storage.falkordb

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.FileLocator
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.storage.falkordb.model.FalkorDbPropositionNode
import com.embabel.dice.temporal.TemporalMetadata
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant

/** Converts between a [Proposition] and the node FalkorDB stores for it. */
internal object FalkorDbPropositionMapper {

    private val json = jacksonObjectMapper()

    /** One entity mention as JSON on the node. */
    private data class StoredMention(
        val span: String,
        val type: String,
        val resolvedId: String? = null,
        val role: String,
        val hints: Map<String, Any> = emptyMap(),
    )

    /**
     * One piece of evidence as JSON on the node. [kind] says which locator fields are set: `uri`,
     * `file`, `content` or `connector`. Spelled out rather than polymorphic so the JSON stays plain.
     */
    private data class StoredProvenance(
        val kind: String,
        val uri: String? = null,
        val path: String? = null,
        val sourceHash: String? = null,
        val connectorId: String? = null,
        val externalId: String? = null,
        val display: String? = null,
        val chunkId: String? = null,
        val startOffset: Int? = null,
        val endOffset: Int? = null,
        val contentHash: String? = null,
        val sourceRevision: String? = null,
    )

    /**
     * @param embedding the vector to store with the node, if any
     * @param embeddingDimensions null marks a stored vector as no longer matching the text
     */
    fun toNode(
        p: Proposition,
        embedding: List<Float>? = null,
        embeddingDimensions: Int? = embedding?.size,
    ): FalkorDbPropositionNode {
        val t = p.temporal
        return FalkorDbPropositionNode(
            id = p.id,
            contextId = p.contextId.value,
            text = p.text,
            confidence = p.confidence,
            decay = p.decay,
            importance = p.importance,
            reasoning = p.reasoning,
            status = p.status.name,
            level = p.level,
            reinforceCount = p.reinforceCount,
            pinned = p.pinned,
            grounding = p.grounding,
            sourceIds = p.sourceIds,
            entityIds = p.mentions.mapNotNull { it.resolvedId }.distinct(),
            mentionsJson = json.writeValueAsString(
                p.mentions.map { StoredMention(it.span, it.type, it.resolvedId, it.role.name, it.hints) },
            ),
            provenanceJson = json.writeValueAsString(p.provenanceEntries.map(::toStored)),
            metadataJson = json.writeValueAsString(p.metadata),
            trustScore = (p.metadata[DiceMetadataKeys.TRUST_SCORE] as? Number)?.toDouble()?.takeIf { it.isFinite() },
            uri = p.uri,
            created = p.created,
            contentRevised = p.contentRevised,
            metadataRevised = p.metadataRevised,
            lastTouched = p.lastTouched,
            lastAccessed = p.lastAccessed,
            observedAt = t?.observedAt,
            validFrom = t?.validFrom,
            validTo = t?.validTo,
            invalidatedAt = t?.invalidatedAt,
            supersedes = t?.supersedes ?: emptyList(),
            contradicts = t?.contradicts ?: emptyList(),
            contentRevisedNanos = epochNanos(p.contentRevised),
            lastAccessedNanos = epochNanos(p.lastAccessed),
            validFromNanos = t?.validFrom?.let(::epochNanos),
            embedding = embedding,
            embeddingDimensions = embeddingDimensions,
        )
    }

    fun toProposition(n: FalkorDbPropositionNode): Proposition = Proposition(
        id = n.id,
        contextId = ContextId(n.contextId),
        text = n.text,
        mentions = json.readValue<List<StoredMention>>(n.mentionsJson).map {
            EntityMention(it.span, it.type, it.resolvedId, MentionRole.valueOf(it.role), it.hints)
        },
        confidence = n.confidence,
        decay = n.decay,
        importance = n.importance,
        reasoning = n.reasoning,
        grounding = n.grounding,
        created = n.created,
        contentRevised = n.contentRevised,
        metadataRevised = n.metadataRevised,
        pinned = n.pinned,
        lastAccessed = n.lastAccessed,
        status = PropositionStatus.valueOf(n.status),
        level = n.level,
        sourceIds = n.sourceIds,
        reinforceCount = n.reinforceCount,
        metadata = json.readValue<Map<String, Any>>(n.metadataJson),
        uri = n.uri,
        temporal = temporal(n),
        provenanceEntries = json.readValue<List<StoredProvenance>>(n.provenanceJson).map(::toEntry),
    )

    /** No temporal fields at all reads back as no temporal metadata, as the graph store does. */
    private fun temporal(n: FalkorDbPropositionNode): TemporalMetadata? {
        val empty = n.observedAt == null && n.validFrom == null && n.validTo == null && n.invalidatedAt == null &&
            n.supersedes.isEmpty() && n.contradicts.isEmpty()
        return if (empty) null else TemporalMetadata(
            n.observedAt, n.validFrom, n.validTo, n.invalidatedAt, n.supersedes, n.contradicts,
        )
    }

    private fun toStored(entry: ProvenanceEntry): StoredProvenance {
        val stored = StoredProvenance(
            kind = "",
            display = entry.locator.display,
            chunkId = entry.chunkId,
            startOffset = entry.startOffset,
            endOffset = entry.endOffset,
            contentHash = entry.contentHash,
            sourceRevision = entry.sourceRevision,
        )
        return when (val locator = entry.locator) {
            is UriLocator -> stored.copy(kind = "uri", uri = locator.uri)
            is FileLocator -> stored.copy(kind = "file", path = locator.path)
            is ContentAddressedLocator -> stored.copy(kind = "content", sourceHash = locator.contentHash)
            is ConnectorRef -> stored.copy(kind = "connector", connectorId = locator.connectorId, externalId = locator.externalId)
        }
    }

    private fun toEntry(stored: StoredProvenance): ProvenanceEntry {
        val locator = when (stored.kind) {
            "uri" -> UriLocator(stored.uri!!, stored.display)
            "file" -> FileLocator(stored.path!!, stored.display)
            "content" -> ContentAddressedLocator(stored.sourceHash!!, stored.display)
            "connector" -> ConnectorRef(stored.connectorId!!, stored.externalId!!, stored.display)
            else -> error("Unknown provenance locator kind '${stored.kind}'")
        }
        return ProvenanceEntry(
            locator, stored.chunkId, stored.startOffset, stored.endOffset, stored.contentHash, stored.sourceRevision,
        )
    }

    /**
     * Nanoseconds since the epoch. A Long holds the years 1677 to 2262; a time outside them is held
     * at the nearest end, which only changes decay for facts dated centuries away.
     */
    fun epochNanos(instant: Instant): Long = try {
        Math.addExact(Math.multiplyExact(instant.epochSecond, NANOS_PER_SECOND), instant.nano.toLong())
    } catch (_: ArithmeticException) {
        if (instant.epochSecond < 0) Long.MIN_VALUE else Long.MAX_VALUE
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
}
