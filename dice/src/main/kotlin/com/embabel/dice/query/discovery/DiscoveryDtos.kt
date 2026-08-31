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
package com.embabel.dice.query.discovery

import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.projection.memory.CollectorRunResult
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.ProvenanceEvidenceKey
import com.embabel.dice.query.graph.GraphNeighborhood
import com.embabel.dice.query.graph.GraphPath
import com.embabel.dice.query.graph.PropositionLineage
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Outward-facing discovery DTOs — the trust boundary between domain internals and external callers.
 *
 * Every field here is a primitive, String, enum, or another DTO in this file. No [Proposition],
 * graph result type, RAG identifier, or store type ever crosses this boundary. The `from()`
 * mappers accept domain objects as input but emit only these shapes. A reflection-based leak-check
 * test guards against any future field accidentally reintroducing a domain type.
 */

/**
 * A leak-free summary of a single entity mention, modelled on the proven web-layer shape.
 *
 * @property name the mention span text
 * @property type the mention's entity type
 * @property resolvedId the resolved entity id, or null if unresolved
 * @property role the mention's role label
 */
data class EntityMentionSummaryDto(
    val name: String,
    val type: String,
    val resolvedId: String?,
    val role: String,
) {
    companion object {
        @JvmStatic
        fun from(mention: EntityMention): EntityMentionSummaryDto = EntityMentionSummaryDto(
            name = mention.span,
            type = mention.type,
            resolvedId = mention.resolvedId,
            role = mention.role.name,
        )
    }
}

/**
 * A lean proposition summary — the common shape every discovery result maps down to.
 *
 * Grounding is carried as opaque string chunk ids only; no RAG or store types cross this boundary.
 *
 * @property id the proposition's opaque id
 * @property text the proposition statement
 * @property confidence the proposition's confidence score
 * @property status the proposition's lifecycle status name
 * @property mentions the entity mentions in this proposition, summarized
 * @property grounding the grounding chunk ids as opaque strings
 */
data class PropositionSummaryDto(
    val id: String,
    val text: String,
    val confidence: Double,
    val status: String,
    val mentions: List<EntityMentionSummaryDto>,
    val grounding: List<String>,
) {
    companion object {
        @JvmStatic
        fun from(proposition: Proposition): PropositionSummaryDto = PropositionSummaryDto(
            id = proposition.id,
            text = proposition.text,
            confidence = proposition.confidence,
            status = proposition.status.name,
            mentions = proposition.mentions.map { EntityMentionSummaryDto.from(it) },
            grounding = proposition.grounding,
        )
    }
}

/**
 * A leak-free ordered path between two entities.
 *
 * @property entityIds the ordered entity id sequence (empty for no path)
 * @property edges the proposition summaries connecting consecutive entities
 */
data class PathDto(
    val entityIds: List<String>,
    val edges: List<PropositionSummaryDto>,
) {
    companion object {
        @JvmStatic
        fun from(path: GraphPath): PathDto = PathDto(
            entityIds = path.entityIds,
            edges = path.edges.map { PropositionSummaryDto.from(it) },
        )
    }
}

/**
 * A leak-free summary of an entity neighbourhood: the edge propositions reachable from the centre.
 *
 * @property centerEntityId the entity the neighbourhood was computed for
 * @property via the proposition summaries on the edges into the neighbourhood
 */
data class NeighborhoodDto(
    val centerEntityId: String,
    val via: List<PropositionSummaryDto>,
) {
    companion object {
        @JvmStatic
        fun from(neighborhood: GraphNeighborhood): NeighborhoodDto = NeighborhoodDto(
            centerEntityId = neighborhood.entityId,
            via = neighborhood.neighbours
                .flatMap { it.via }
                .distinctBy { it.id }
                .map { PropositionSummaryDto.from(it) },
        )
    }
}

/**
 * Primitive-only provenance attached to a discovery explanation.
 *
 * The locator arrives as its stable key string rather than as a `SourceLocator`, so no domain
 * type crosses the trust boundary. Null scalars drop out of the JSON, so a revisionless entry
 * serializes the way it always did.
 *
 * @property locator stable key of the source reference
 * @property sourceRevision the provider's opaque revision of that source, when known
 * @property chunkId the chunk this proposition was extracted from, when known
 * @property startOffset character offset where the supporting span begins, when known
 * @property endOffset character offset where the supporting span ends, when known
 * @property contentHash hash of the source content, when known
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DiscoveryProvenanceDto(
    val locator: String,
    val sourceRevision: String? = null,
    val chunkId: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val contentHash: String? = null,
) {
    companion object {
        @JvmStatic
        fun from(entry: ProvenanceEntry): DiscoveryProvenanceDto = DiscoveryProvenanceDto(
            locator = entry.locator.key(),
            sourceRevision = entry.sourceRevision,
            chunkId = entry.chunkId,
            startOffset = entry.startOffset,
            endOffset = entry.endOffset,
            contentHash = entry.contentHash,
        )
    }
}

/**
 * A leak-free lineage summary — the "why" behind a stored fact.
 *
 * @property propositionId the explained proposition's id
 * @property text the proposition statement
 * @property status the lifecycle status name
 * @property reinforceCount how many times the proposition has been reinforced
 * @property groundingChunkIds the grounding chunk ids as opaque strings
 * @property sourceSummaries the source proposition statements this one was abstracted from
 * @property provenance the proposition's source evidence, ordered by evidence key
 */
data class LineageDto @JvmOverloads constructor(
    val propositionId: String,
    val text: String,
    val status: String,
    val reinforceCount: Int,
    val groundingChunkIds: List<String>,
    val sourceSummaries: List<String>,
    val provenance: List<DiscoveryProvenanceDto> = emptyList(),
) {
    companion object {
        @JvmStatic
        fun from(lineage: PropositionLineage): LineageDto = LineageDto(
            propositionId = lineage.proposition.id,
            text = lineage.proposition.text,
            status = lineage.status.name,
            reinforceCount = lineage.reinforceCount,
            groundingChunkIds = lineage.groundingChunkIds,
            sourceSummaries = lineage.sources.map { it.text },
            // The lineage's own entries, not the nested proposition's: a lean read can hand back
            // a proposition with no evidence on it while the lineage carries the full set.
            //
            // Sorted by evidence key, because the graph read returns edges in whatever order the
            // planner produced and the same proposition would otherwise serialize its evidence in a
            // different order run to run. The key is the durable identity of the entry, so the order
            // is stable across backends and across reads, and it sorts a source's revisions among
            // themselves once the locator prefix matches.
            provenance = lineage.provenanceEntries
                .sortedBy { ProvenanceEvidenceKey.encode(it) }
                .map { DiscoveryProvenanceDto.from(it) },
        )
    }
}

/**
 * Per-target projection counts by lifecycle.
 *
 * @property target the projection target name (e.g. "neo4j", "prolog", "report")
 * @property projected count of newly created artifacts
 * @property adopted count of aligned/adopted artifacts
 * @property skipped count of intentionally un-projected propositions
 * @property failed count of failed projections
 * @property stale count of out-of-date projections
 */
data class TargetHealthDto(
    val target: String,
    val projected: Int,
    val adopted: Int,
    val skipped: Int,
    val failed: Int,
    val stale: Int,
)

/**
 * Projection health: lifecycle counts aggregated per target.
 *
 * @property perTarget the per-target lifecycle counts
 */
data class ProjectionHealthDto(
    val perTarget: List<TargetHealthDto>,
) {
    companion object {
        @JvmStatic
        fun from(records: List<ProjectionRecord>): ProjectionHealthDto = ProjectionHealthDto(
            perTarget = records
                .groupBy { it.target }
                .toSortedMap()
                .map { (target, group) ->
                    TargetHealthDto(
                        target = target,
                        projected = group.count { it.lifecycle == ProjectionLifecycle.PROJECTED },
                        adopted = group.count { it.lifecycle == ProjectionLifecycle.ADOPTED },
                        skipped = group.count { it.lifecycle == ProjectionLifecycle.SKIPPED },
                        failed = group.count { it.lifecycle == ProjectionLifecycle.FAILED },
                        stale = group.count { it.lifecycle == ProjectionLifecycle.STALE },
                    )
                },
        )
    }
}

/**
 * A leak-free summary of a single collector mark.
 *
 * @property propositionId the marked proposition's id
 * @property reason the stable machine reason key
 * @property strategyName the strategy that produced the mark
 */
data class MarkDto(
    val propositionId: String,
    val reason: String,
    val strategyName: String,
)

/**
 * A leak-free summary of a collector dry-run preview. Exposes counts derived from the run's
 * list fields, plus the individual marks.
 *
 * @property runId the run identifier
 * @property dryRun whether the run was a non-mutating preview
 * @property applied count of marks whose proposition was transitioned (zero on a dry run)
 * @property skipped count of marks intentionally left untouched
 * @property hardDeleted count of propositions permanently removed
 * @property marks the individual marks produced by the run
 */
data class CollectorDryRunDto(
    val runId: String,
    val dryRun: Boolean,
    val applied: Int,
    val skipped: Int,
    val hardDeleted: Int,
    val marks: List<MarkDto>,
) {
    companion object {
        @JvmStatic
        fun from(result: CollectorRunResult): CollectorDryRunDto = CollectorDryRunDto(
            runId = result.runId,
            dryRun = result.dryRun,
            applied = result.applied.size,
            skipped = result.skipped.size,
            hardDeleted = result.hardDeleted.size,
            marks = result.marks.map {
                MarkDto(
                    propositionId = it.propositionId,
                    reason = it.reason.key,
                    strategyName = it.strategyName,
                )
            },
        )
    }
}

/**
 * The result of a discovery retrieval.
 *
 * @property mode the mode that was routed
 * @property supported whether the backing fragment was present; false signals graceful degradation
 *   (a typed-empty result from an absent fragment) rather than a genuinely empty result set
 * @property propositions the leak-free proposition summaries
 */
data class DiscoveryResult(
    val mode: RetrievalMode,
    val supported: Boolean,
    val propositions: List<PropositionSummaryDto>,
)
