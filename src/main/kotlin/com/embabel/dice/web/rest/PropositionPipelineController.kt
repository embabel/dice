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

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.ingestion.HierarchicalContentReader
import com.embabel.agent.rag.ingestion.InMemoryContentChunker
import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.KnownEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.SchemaRegistry
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.revision.RevisionResult
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/**
 * REST controller for proposition extraction pipeline operations.
 * Handles text processing and proposition extraction.
 */
@RestController
@RequestMapping("/api/v1/contexts/{contextId}")
@ConditionalOnBean(PropositionPipeline::class)
class PropositionPipelineController(
    private val propositionPipeline: PropositionPipeline,
    private val propositionRepository: PropositionRepository,
    private val entityResolver: EntityResolver,
    private val schemaRegistry: SchemaRegistry,
    private val contentReader: HierarchicalContentReader = TikaHierarchicalContentReader(),
    private val contentChunker: ContentChunker = InMemoryContentChunker(
        config = ContentChunker.Config(),
        chunkTransformer = ChunkTransformer.NO_OP,
    ),
) {

    private val logger = LoggerFactory.getLogger(PropositionPipelineController::class.java)

    /**
     * Extract propositions from text chunk.
     */
    @PostMapping("/extract")
    fun extract(
        @PathVariable contextId: String,
        @RequestBody request: ExtractRequest,
    ): ResponseEntity<ExtractResponse> {
        logger.info("Extracting propositions for context: {}", contextId)

        val chunk = Chunk.create(
            text = request.text,
            parentId = request.sourceId ?: "api-request",
        )

        val context = buildContext(contextId, request.knownEntities, request.schemaName)
        val result = propositionPipeline.processChunk(chunk, context)

        // Save propositions
        result.propositions.forEach { proposition ->
            propositionRepository.save(proposition)
        }

        return ResponseEntity.ok(buildExtractResponse(chunk.id, contextId, result))
    }

    /**
     * Extract propositions from an uploaded file.
     * Supports PDF, Word, Markdown, HTML, and other formats via Apache Tika.
     */
    @PostMapping("/extract/file", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun extractFromFile(
        @PathVariable contextId: String,
        @RequestPart("file") file: MultipartFile,
        @RequestPart("sourceId", required = false) sourceId: String?,
        @RequestPart("knownEntities", required = false) knownEntitiesJson: String?,
        @RequestPart("schemaName", required = false) schemaName: String?,
    ): ResponseEntity<FileExtractResponse> {
        val filename = file.originalFilename ?: "uploaded-file"
        logger.info("Extracting propositions from file '{}' for context: {}", filename, contextId)

        // Parse file content using Tika
        val document = file.inputStream.use { inputStream ->
            contentReader.parseContent(inputStream, sourceId ?: filename)
        }

        logger.info("Parsed document '{}' with {} sections", document.title, document.leaves().count())

        // Chunk the document
        val chunks = contentChunker.chunk(document).toList()
        logger.info("Created {} chunks from document", chunks.size)

        if (chunks.isEmpty()) {
            return ResponseEntity.ok(FileExtractResponse(
                sourceId = sourceId ?: filename,
                contextId = contextId,
                filename = filename,
                chunksProcessed = 0,
                totalPropositions = 0,
                chunks = emptyList(),
                entities = EntitySummary(created = emptyList(), resolved = emptyList(), failed = emptyList()),
                revision = null,
            ))
        }

        // Process each chunk through the pipeline
        val context = buildContext(contextId, emptyList(), schemaName)
        val chunkResults = chunks.map { chunk ->
            val result = propositionPipeline.processChunk(chunk, context)

            // Save propositions
            result.propositions.forEach { proposition ->
                propositionRepository.save(proposition)
            }

            result
        }

        // Aggregate results
        val allPropositions = chunkResults.flatMap { it.propositions }
        val allRevisions = chunkResults.flatMap { it.revisionResults }
        val allResolutions = chunkResults.flatMap { it.entityResolutions.resolutions }

        val resolvedIds = allResolutions.mapNotNull { resolution ->
            when (resolution) {
                is ExistingEntity -> resolution.existing.id
                else -> null
            }
        }.distinct()

        val createdIds = allResolutions.mapNotNull { resolution ->
            when (resolution) {
                is NewEntity -> resolution.suggested.id
                else -> null
            }
        }.distinct()

        val revisionSummary = if (allRevisions.isNotEmpty()) {
            RevisionSummary(
                created = allRevisions.count { it is RevisionResult.New },
                merged = allRevisions.count { it is RevisionResult.Merged },
                reinforced = allRevisions.count { it is RevisionResult.Reinforced },
                contradicted = allRevisions.count { it is RevisionResult.Contradicted },
                generalized = allRevisions.count { it is RevisionResult.Generalized },
            )
        } else null

        val chunkSummaries = chunks.zip(chunkResults).map { (chunk, result) ->
            ChunkSummary(
                chunkId = chunk.id,
                propositionCount = result.propositions.size,
                preview = chunk.text.take(100) + if (chunk.text.length > 100) "..." else "",
            )
        }

        val response = FileExtractResponse(
            sourceId = sourceId ?: document.id,
            contextId = contextId,
            filename = filename,
            chunksProcessed = chunks.size,
            totalPropositions = allPropositions.size,
            chunks = chunkSummaries,
            entities = EntitySummary(
                created = createdIds,
                resolved = resolvedIds,
                failed = emptyList(),
            ),
            revision = revisionSummary,
        )

        logger.info(
            "Extracted {} propositions from {} chunks in file '{}'",
            allPropositions.size, chunks.size, filename
        )

        return ResponseEntity.ok(response)
    }

    private fun buildContext(
        contextId: String,
        knownEntityDtos: List<KnownEntityDto>,
        schemaName: String? = null,
    ): SourceAnalysisContext {
        val knownEntities = knownEntityDtos.map { dto ->
            val entity = SimpleNamedEntityData(
                id = dto.id,
                name = dto.name,
                description = dto.description ?: dto.name,
                labels = setOf(dto.type),
                properties = emptyMap(),
            )
            KnownEntity(entity = entity, role = dto.role)
        }

        val schema = schemaRegistry.getOrDefault(schemaName)

        return SourceAnalysisContext(
            schema = schema,
            entityResolver = entityResolver,
            contextId = ContextId(contextId),
            knownEntities = knownEntities,
        )
    }

    private fun buildExtractResponse(
        chunkId: String,
        contextId: String,
        result: ChunkPropositionResult,
    ): ExtractResponse {
        val resolvedIds = result.entityResolutions.resolutions
            .mapNotNull { resolution ->
                when (resolution) {
                    is ExistingEntity -> resolution.existing.id
                    else -> null
                }
            }

        val createdIds = result.entityResolutions.resolutions
            .mapNotNull { resolution ->
                when (resolution) {
                    is NewEntity -> resolution.suggested.id
                    else -> null
                }
            }

        val propositionDtos = if (result.revisionResults.isNotEmpty()) {
            result.propositions.zip(result.revisionResults).map { (prop, rev) ->
                PropositionDto.from(prop, rev)
            }
        } else {
            result.propositions.map { PropositionDto.from(it, "CREATED") }
        }

        val revisionSummary = if (result.revisionResults.isNotEmpty()) {
            RevisionSummary(
                created = result.revisionResults.count { it is RevisionResult.New },
                merged = result.revisionResults.count { it is RevisionResult.Merged },
                reinforced = result.revisionResults.count { it is RevisionResult.Reinforced },
                contradicted = result.revisionResults.count { it is RevisionResult.Contradicted },
                generalized = result.revisionResults.count { it is RevisionResult.Generalized },
            )
        } else null

        return ExtractResponse(
            chunkId = chunkId,
            contextId = contextId,
            propositions = propositionDtos,
            entities = EntitySummary(
                created = createdIds,
                resolved = resolvedIds,
                failed = emptyList(),
            ),
            revision = revisionSummary,
        )
    }
}
