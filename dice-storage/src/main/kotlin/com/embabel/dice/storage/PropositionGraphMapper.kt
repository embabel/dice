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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.FileLocator
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.storage.model.DerivedFrom
import com.embabel.dice.storage.model.Mention
import com.embabel.dice.storage.model.PropositionNode
import com.embabel.dice.storage.model.PropositionView
import com.embabel.dice.storage.model.PropositionWithProvenanceView
import com.embabel.dice.storage.model.SourceNode
import com.embabel.dice.temporal.TemporalMetadata

/**
 * Converts between the dice [Proposition] (system of record) and its Drivine graph projections.
 * Enums are stored as their `name`; [TemporalMetadata] is flattened to scalar node properties;
 * `metadata` rides along as a Drivine `@PropertyBag`.
 *
 * Two views over the same `:Proposition` node:
 * - [PropositionView] — lean (mentions only); the bulk/query/vector workhorse. [toProposition] from it
 *   returns a [Proposition] with **empty** `provenanceEntries` (provenance isn't projected).
 * - [PropositionWithProvenanceView] — adds `DERIVED_FROM` → shared [SourceNode]; used by `save` and
 *   `findById`. Sources are MERGEd by `SourceLocator.key()`, so a source cited by many propositions is
 *   one node.
 *
 * The graph carries an `embedding` that is *not* part of [Proposition]; the repository owns it and
 * passes it in. `EntityMention.hints` is not yet persisted.
 */
object PropositionGraphMapper {

    /** Lean view: mentions only. */
    fun toView(p: Proposition, embedding: List<Float>? = null): PropositionView =
        PropositionView(proposition = nodeOf(p, embedding), mentions = mentionsOf(p))

    /** Full view: mentions + provenance (`DERIVED_FROM` → shared [SourceNode]). */
    fun toProvenanceView(p: Proposition, embedding: List<Float>? = null): PropositionWithProvenanceView =
        PropositionWithProvenanceView(
            proposition = nodeOf(p, embedding),
            mentions = mentionsOf(p),
            provenance = p.provenanceEntries.map(::toDerivedFrom),
        )

    fun toProposition(view: PropositionView): Proposition =
        proposition(view.proposition, view.mentions, emptyList())

    fun toProposition(view: PropositionWithProvenanceView): Proposition =
        proposition(view.proposition, view.mentions, view.provenance.map(::toProvenanceEntry))

    // ---- Proposition node <-> dice ----

    private fun nodeOf(p: Proposition, embedding: List<Float>?): PropositionNode {
        val t = p.temporal
        return PropositionNode(
            id = p.id,
            contextId = p.contextId.value,
            text = p.text,
            confidence = p.confidence,
            decay = p.decay,
            importance = p.importance,
            reasoning = p.reasoning,
            grounding = p.grounding,
            sourceIds = p.sourceIds,
            uri = p.uri,
            created = p.created,
            contentRevised = p.contentRevised,
            metadataRevised = p.metadataRevised,
            lastAccessed = p.lastAccessed,
            status = p.status.name,
            pinned = p.pinned,
            level = p.level,
            reinforceCount = p.reinforceCount,
            embedding = embedding,
            // Compute-on-write: seed effectiveConfidence with the decayed value as of now, so
            // `minEffectiveConfidence` filtering and EFFECTIVE_CONFIDENCE_DESC ordering are meaningful
            // immediately — BEFORE the periodic sweep runs (without it, freshly-saved propositions have
            // a null effectiveConfidence and are silently excluded by any minEffectiveConfidence
            // predicate, e.g. the Memory tool's 0.5 floor). The sweep keeps it current as time passes;
            // decayUpdatedAt stays null until the first sweep so it can tell it has not run yet.
            effectiveConfidence = p.effectiveConfidence(),
            validFrom = t?.validFrom,
            validTo = t?.validTo,
            invalidatedAt = t?.invalidatedAt,
            observedAt = t?.observedAt,
            supersedes = t?.supersedes ?: emptyList(),
            contradicts = t?.contradicts ?: emptyList(),
            metadata = p.metadata,
        )
    }

    private fun proposition(
        n: PropositionNode,
        mentions: List<Mention>,
        provenance: List<ProvenanceEntry>,
    ): Proposition = Proposition(
        id = n.id,
        contextId = ContextId(n.contextId),
        text = n.text,
        mentions = mentions.map(::toEntityMention),
        confidence = n.confidence,
        decay = n.decay,
        importance = n.importance,
        reasoning = n.reasoning,
        grounding = n.grounding,
        created = n.created,
        contentRevised = n.contentRevised,
        metadataRevised = n.metadataRevised,
        lastAccessed = n.lastAccessed,
        status = PropositionStatus.valueOf(n.status),
        pinned = n.pinned,
        level = n.level,
        sourceIds = n.sourceIds,
        reinforceCount = n.reinforceCount,
        metadata = n.metadata.filterValues { it != null }.mapValues { it.value!! },
        uri = n.uri,
        temporal = toTemporal(n),
        provenanceEntries = provenance,
    )

    // ---- Mentions ----

    private fun mentionsOf(p: Proposition): List<Mention> =
        p.mentions.mapIndexed { i, m -> toMention(p.id, i, m) }

    /**
     * Stable, content-derived mention id so re-saving an unchanged proposition MERGEs the same
     * Mention nodes instead of accumulating duplicates.
     */
    private fun toMention(propositionId: String, index: Int, m: EntityMention): Mention =
        Mention(
            id = "$propositionId#$index:${m.span}|${m.type}|${m.role.name}|${m.resolvedId ?: ""}",
            resolvedId = m.resolvedId,
            span = m.span,
            type = m.type,
            role = m.role.name,
        )

    private fun toEntityMention(m: Mention): EntityMention =
        EntityMention(
            span = m.span,
            type = m.type,
            resolvedId = m.resolvedId,
            role = MentionRole.valueOf(m.role),
        )

    // ---- Provenance: ProvenanceEntry <-> (DERIVED_FROM edge + shared Source node) ----

    private fun toDerivedFrom(e: ProvenanceEntry): DerivedFrom =
        DerivedFrom(
            chunkId = e.chunkId,
            startOffset = e.startOffset,
            endOffset = e.endOffset,
            contentHash = e.contentHash,
            source = toSourceNode(e.locator),
        )

    private fun toProvenanceEntry(df: DerivedFrom): ProvenanceEntry =
        ProvenanceEntry(
            locator = toLocator(df.source),
            chunkId = df.chunkId,
            startOffset = df.startOffset,
            endOffset = df.endOffset,
            contentHash = df.contentHash,
        )

    private fun toSourceNode(loc: SourceLocator): SourceNode =
        SourceNode(
            key = loc.key(),
            kind = kindOf(loc),
            display = loc.display,
            uri = (loc as? UriLocator)?.uri,
            path = (loc as? FileLocator)?.path,
            contentHash = (loc as? ContentAddressedLocator)?.contentHash,
            connectorId = (loc as? ConnectorRef)?.connectorId,
            externalId = (loc as? ConnectorRef)?.externalId,
        )

    private fun kindOf(loc: SourceLocator): String = when (loc) {
        is UriLocator -> "uri"
        is FileLocator -> "file"
        is ContentAddressedLocator -> "content"
        is ConnectorRef -> "connector"
    }

    private fun toLocator(s: SourceNode): SourceLocator = when (s.kind) {
        "uri" -> UriLocator(s.uri!!, s.display)
        "file" -> FileLocator(s.path!!, s.display)
        "content" -> ContentAddressedLocator(s.contentHash!!, s.display)
        "connector" -> ConnectorRef(s.connectorId!!, s.externalId!!, s.display)
        else -> error("Unknown source locator kind: ${s.kind}")
    }

    // ---- Temporal ----

    private fun toTemporal(n: PropositionNode): TemporalMetadata? {
        val hasAny = n.validFrom != null || n.validTo != null || n.invalidatedAt != null ||
            n.observedAt != null || n.supersedes.isNotEmpty() || n.contradicts.isNotEmpty()
        return if (!hasAny) null else TemporalMetadata(
            observedAt = n.observedAt,
            validFrom = n.validFrom,
            validTo = n.validTo,
            invalidatedAt = n.invalidatedAt,
            supersedes = n.supersedes,
            contradicts = n.contradicts,
        )
    }
}
