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
package com.embabel.dice.web.rest

import com.embabel.dice.common.SchemaAdherence
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.revision.RevisionResult
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

// ============================================================================
// Request DTOs
// ============================================================================

/**
 * Request to extract propositions from text.
 * @param text The text to extract propositions from
 * @param sourceId Optional source identifier for provenance
 * @param knownEntities Entities to associate with extracted propositions (e.g., the user)
 * @param schemaName Optional schema name for extraction. Uses default if not specified.
 * @param options Extraction options
 */
data class ExtractRequest(
    val text: String,
    val sourceId: String? = null,
    val knownEntities: List<KnownEntityDto> = emptyList(),
    val schemaName: String? = null,
    val options: ExtractOptions = ExtractOptions(),
)

data class ExtractOptions(
    val schemaAdherence: SchemaAdherenceDto = SchemaAdherenceDto.DEFAULT,
)

enum class SchemaAdherenceDto {
    STRICT, DEFAULT, RELAXED;

    fun toSchemaAdherence(): SchemaAdherence = when (this) {
        STRICT -> SchemaAdherence.STRICT
        DEFAULT -> SchemaAdherence.DEFAULT
        RELAXED -> SchemaAdherence.RELAXED
    }
}

data class KnownEntityDto(
    val id: String,
    val name: String,
    val type: String,
    val description: String? = null,
    val role: String = "A referenced entity",
)

/**
 * Request to search memory by similarity.
 */
data class MemorySearchRequest(
    val query: String,
    val topK: Int = 10,
    val similarityThreshold: Double = 0.7,
    val filters: MemorySearchFilters = MemorySearchFilters(),
)

data class MemorySearchFilters(
    val status: List<PropositionStatus>? = null,
    val mentionTypes: List<String>? = null,
    val minConfidence: Double? = null,
)

/**
 * Request to create a proposition directly.
 */
data class CreatePropositionRequest(
    val text: String,
    val mentions: List<MentionDto> = emptyList(),
    val confidence: Double = 0.9,
    val decay: Double = 0.0,
    val reasoning: String? = null,
)

data class MentionDto(
    val name: String,
    val type: String,
    val suggestedId: String? = null,
    val role: String = "OTHER",
) {
    fun toEntityMention(resolvedId: String? = suggestedId): EntityMention = EntityMention(
        span = name,
        type = type,
        resolvedId = resolvedId,
        role = try { MentionRole.valueOf(role.uppercase()) } catch (e: Exception) { MentionRole.OTHER },
    )
}

// ============================================================================
// Response DTOs
// ============================================================================

/**
 * Response from extraction endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExtractResponse(
    val chunkId: String,
    val contextId: String,
    val propositions: List<PropositionDto>,
    val entities: EntitySummary,
    val revision: RevisionSummary?,
)

/**
 * Response from file extraction endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FileExtractResponse(
    val sourceId: String,
    val contextId: String,
    val filename: String,
    val chunksProcessed: Int,
    val totalPropositions: Int,
    val chunks: List<ChunkSummary>,
    val entities: EntitySummary,
    val revision: RevisionSummary?,
)

/**
 * Summary of a processed chunk.
 */
data class ChunkSummary(
    val chunkId: String,
    val propositionCount: Int,
    val preview: String,
)

data class EntitySummary(
    val created: List<String>,
    val resolved: List<String>,
    val failed: List<String>,
)

data class RevisionSummary(
    val created: Int,
    val merged: Int,
    val reinforced: Int,
    val contradicted: Int,
    val generalized: Int,
)

/**
 * Proposition DTO for API responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PropositionDto(
    val id: String,
    val contextId: String,
    val text: String,
    val mentions: List<EntityMentionDto>,
    val confidence: Double,
    val decay: Double,
    val reasoning: String?,
    val grounding: List<String>,
    val created: Instant,
    val revised: Instant,
    val status: PropositionStatus,
    val action: String? = null,
) {
    companion object {
        fun from(proposition: Proposition, action: String? = null): PropositionDto = PropositionDto(
            id = proposition.id,
            contextId = proposition.contextIdValue,
            text = proposition.text,
            mentions = proposition.mentions.map { EntityMentionDto.from(it) },
            confidence = proposition.confidence,
            decay = proposition.decay,
            reasoning = proposition.reasoning,
            grounding = proposition.grounding,
            created = proposition.created,
            revised = proposition.revised,
            status = proposition.status,
            action = action,
        )

        fun from(proposition: Proposition, revisionResult: RevisionResult): PropositionDto =
            from(proposition, revisionResult.toAction())
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EntityMentionDto(
    val name: String,
    val type: String,
    val resolvedId: String?,
    val role: String,
) {
    companion object {
        fun from(mention: EntityMention): EntityMentionDto = EntityMentionDto(
            name = mention.span,
            type = mention.type,
            resolvedId = mention.resolvedId,
            role = mention.role.name,
        )
    }
}

/**
 * Response for memory retrieval.
 */
data class MemoryResponse(
    val contextId: String,
    val count: Int,
    val propositions: List<PropositionDto>,
)

/**
 * Response for memory search.
 */
data class MemorySearchResponse(
    val contextId: String,
    val query: String,
    val results: List<SimilarityResultDto>,
)

data class SimilarityResultDto(
    val proposition: PropositionDto,
    val score: Double,
)

/**
 * Response for memory by entity.
 */
data class EntityMemoryResponse(
    val entity: EntityReference,
    val propositions: List<PropositionDto>,
)

data class EntityReference(
    val id: String,
    val type: String,
    val name: String?,
)

/**
 * Response for delete operation.
 */
data class DeleteResponse(
    val id: String,
    val status: PropositionStatus,
    val previousStatus: PropositionStatus?,
)

// ============================================================================
// Helpers
// ============================================================================

private fun RevisionResult.toAction(): String = when (this) {
    is RevisionResult.New -> "CREATED"
    is RevisionResult.Merged -> "MERGED"
    is RevisionResult.Reinforced -> "REINFORCED"
    is RevisionResult.Contradicted -> "CONTRADICTED"
    is RevisionResult.Generalized -> "GENERALIZED"
}
