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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/**
 * REST controller that runs the proposition extraction pipeline over text or uploaded files.
 *
 * Exposes two endpoints under `/api/v1/contexts/{contextId}`:
 * - `POST /extract` — send raw text, get back propositions and entity resolutions
 * - `POST /extract/file` — upload a document (PDF, Word, Markdown, HTML, etc.) and get back
 *   per-chunk results aggregated into a single summary
 *
 * Not component-scanned: activate via [DiceRestConfiguration]. Requires a [PropositionPipeline]
 * bean to be present. The context id comes exclusively from the path variable; it is never read
 * from the request body.
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
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {

    private val logger = LoggerFactory.getLogger(PropositionPipelineController::class.java)

    /**
     * Extract propositions from a single text chunk.
     *
     * Runs the extraction pipeline on the supplied text, persists the resulting propositions,
     * and returns them together with entity resolution and revision summaries.
     */
    @PostMapping("/extract")
    fun extract(
        @PathVariable contextId: String,
        @RequestBody request: ExtractRequest,
    ): ResponseEntity<ExtractResponse> {
        logger.info("Extracting propositions for context: {}", contextId)

        if (request.text.isBlank()) {
            logger.warn("Rejecting extract request for context {}: blank text", contextId)
            return ResponseEntity.badRequest().build()
        }

        val chunk = Chunk.create(
            text = request.text,
            parentId = request.sourceId ?: "api-request",
        )

        val context = buildContext(contextId, request.knownEntities, request.schemaName)
        val result = propositionPipeline.processChunk(chunk, context)

        // Persist what revision says to keep — both the freshly extracted propositions and any
        // revised originals (e.g. an existing proposition retired to CONTRADICTED), not just the new ones.
        result.propositionsToPersist().forEach { proposition ->
            propositionRepository.save(proposition)
        }

        return ResponseEntity.ok(buildExtractResponse(chunk.id, contextId, result))
    }

    /**
     * Extract propositions from an uploaded document.
     *
     * Parses the file with Apache Tika (PDF, Word, Markdown, HTML, and more), chunks it, runs
     * each chunk through the extraction pipeline, persists the propositions, and returns an
     * aggregated summary across all chunks.
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

        // Process all chunks through the pipeline's batch entry point. process() isolates a failing
        // chunk into a typed Failed result (so one bad chunk yields partial results instead of 500ing
        // the whole upload), shares entity identity across chunks, and is the only path that honors
        // the configured extraction execution strategy (Serial/Parallel/Batched). processChunk(), by
        // contrast, propagates failures and runs one chunk in isolation.
        val context = buildContext(contextId, parseKnownEntities(knownEntitiesJson), schemaName)
        val processResult = propositionPipeline.process(chunks, context)
        val chunkResults = processResult.chunkResults

        // Persist what revision says to keep across the whole batch — revised originals (e.g. a
        // CONTRADICTED original) as well as the new propositions, not just the new ones.
        processResult.propositionsToPersist().forEach { proposition ->
            propositionRepository.save(proposition)
        }

        // Aggregate results
        val allPropositions = chunkResults.flatMap { it.propositions }
        val allRevisions = chunkResults.flatMap { it.revisionResults }
        // Failed chunks contribute zero resolutions to the aggregate response:
        // entityResolutions is a Success-only field, and Failed returns empty propositions/
        // revisionResults via the interface, so the flat-maps above already exclude them.
        val allResolutions = chunkResults
            .filterIsInstance<ChunkPropositionResult.Success>()
            .flatMap { it.entityResolutions.resolutions }

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
                failed = chunkResults.filterIsInstance<ChunkPropositionResult.Failed>().map { it.chunkId },
            ),
            revision = revisionSummary,
        )

        logger.info(
            "Extracted {} propositions from {} chunks in file '{}'",
            allPropositions.size, chunks.size, filename
        )

        return ResponseEntity.ok(response)
    }

    /**
     * Parse the optional `knownEntities` multipart part — a JSON array of [KnownEntityDto] — into a
     * list. A blank or absent part yields an empty list. This mirrors the JSON `/extract` endpoint,
     * which binds `knownEntities` directly from the request body.
     */
    private fun parseKnownEntities(json: String?): List<KnownEntityDto> =
        if (json.isNullOrBlank()) emptyList() else objectMapper.readValue(json)

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
        // entityResolutions is a Success-only field; a Failed chunk yields an empty response
        // carrying the failed chunkId.
        if (result !is ChunkPropositionResult.Success) {
            return ExtractResponse(
                chunkId = chunkId,
                contextId = contextId,
                propositions = emptyList(),
                entities = EntitySummary(
                    created = emptyList(),
                    resolved = emptyList(),
                    failed = listOf(result.chunkId),
                ),
                revision = null,
            )
        }
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
