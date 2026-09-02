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
import com.embabel.dice.common.support.Sha256ContentHasher
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.FileLocator
import com.embabel.dice.provenance.SourceIdentityBounds
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.provenance.UriLocator
import com.fasterxml.jackson.core.JsonProcessingException
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
     * and returns them together with entity resolution and revision summaries. The propositions
     * that come back are the ones the store now holds, so every id in the response can be fetched
     * again by id.
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

        val sourceProvenance = try {
            resolveSourceProvenance(request.sourceLocator, request.sourceRevision)
        } catch (e: IllegalArgumentException) {
            logger.warn("Rejecting extract request for context {}: {}", contextId, e.message)
            throw InvalidSourceProvenanceException(e.message ?: DEFAULT_REJECTION_REASON)
        }
        val context = buildContext(
            contextId = contextId,
            knownEntityDtos = request.knownEntities,
            schemaName = request.schemaName,
            sourceLocator = sourceProvenance.first,
            sourceRevision = sourceProvenance.second,
        )

        val chunk = revisionStableChunk(
            chunk = Chunk.create(
                text = request.text,
                parentId = request.sourceId ?: "api-request",
            ),
            contextId = contextId,
            sourceRevision = sourceProvenance.second,
            ordinal = 0,
        )
        val result = propositionPipeline.processChunk(chunk, context)

        // Persist what revision says to keep — both the freshly extracted propositions and any
        // revised originals (e.g. an existing proposition retired to CONTRADICTED), not just the new ones.
        //
        // save() is the store's answer about what it now holds, and that can be a different row from
        // the one offered: exact-text dedup hands back the existing canonical proposition under its
        // own id. Keep each answer against the id we offered, so the response can name what a caller
        // will actually find on a later read.
        val storedByOfferedId = result.propositionsToPersist().associate { proposition ->
            proposition.id to propositionRepository.save(proposition)
        }

        return ResponseEntity.ok(buildExtractResponse(chunk.id, contextId, result, storedByOfferedId))
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
        @RequestPart("sourceLocator", required = false) sourceLocatorJson: String?,
        @RequestPart("sourceRevision", required = false) sourceRevision: String?,
    ): ResponseEntity<FileExtractResponse> {
        val filename = file.originalFilename ?: "uploaded-file"
        logger.info("Extracting propositions from file '{}' for context: {}", filename, contextId)

        val sourceProvenance = try {
            resolveSourceProvenance(parseSourceLocator(sourceLocatorJson), sourceRevision)
        } catch (e: JsonProcessingException) {
            logger.warn("Rejecting file extract request for context {}: invalid sourceLocator JSON", contextId)
            return ResponseEntity.badRequest().build()
        } catch (e: IllegalArgumentException) {
            logger.warn("Rejecting file extract request for context {}: {}", contextId, e.message)
            throw InvalidSourceProvenanceException(e.message ?: DEFAULT_REJECTION_REASON)
        }

        // Parse file content using Tika
        val document = file.inputStream.use { inputStream ->
            contentReader.parseContent(inputStream, sourceId ?: filename)
        }

        logger.info("Parsed document '{}' with {} sections", document.title, document.leaves().count())

        // Chunk the document
        val chunks = contentChunker.chunk(document)
            .mapIndexed { ordinal, chunk ->
                revisionStableChunk(chunk, contextId, sourceProvenance.second, ordinal)
            }
            .toList()
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
        val context = buildContext(
            contextId = contextId,
            knownEntityDtos = parseKnownEntities(knownEntitiesJson),
            schemaName = schemaName,
            sourceLocator = sourceProvenance.first,
            sourceRevision = sourceProvenance.second,
        )
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

    private fun parseSourceLocator(json: String?): SourceLocatorInputDto? =
        if (json.isNullOrBlank()) null else objectMapper.readValue(json)

    /**
     * Turns the request's locator and revision fields into the typed pair the context wants.
     *
     * Everything that would otherwise fail deep inside extraction is checked here, so the caller
     * gets a 400 before the pipeline or Tika is touched: a revision with no locator has nothing to
     * attach to, and a source key or revision longer than [SourceIdentityBounds] allows would blow
     * up when the first [com.embabel.dice.provenance.ProvenanceEntry] was built. The length checks
     * throw with the broken limit named, and that text is what the caller gets back.
     */
    private fun resolveSourceProvenance(
        sourceLocatorInput: SourceLocatorInputDto?,
        revision: String?,
    ): Pair<SourceLocator?, SourceRevisionRef?> {
        require(sourceLocatorInput != null || revision == null) {
            "sourceRevision requires sourceLocator"
        }
        val sourceLocator = sourceLocatorInput?.toSourceLocator()
        // A revisionless request never builds a SourceRevisionRef, so the key ceiling has to be
        // checked on its own here to cover that case too.
        sourceLocator?.let { SourceIdentityBounds.requireSourceKeyWithinBounds(it.key()) }
        val sourceRevision = revision?.let {
            SourceRevisionRef(sourceLocator!!.key(), it)
        }
        return sourceLocator to sourceRevision
    }

    /**
     * Gives a chunk an id derived from the tenant, the source revision, the chunk's position in the
     * document, and its text, so re-posting the same revision produces the same chunk ids and grounds
     * onto the same rows instead of accumulating a fresh set per replay. A different revision of the
     * same source gets different ids, which is what keeps the two versions separately traceable.
     *
     * [contextId] is part of the identity because chunk ids are what grounding is looked up by, and
     * `findByGrounding` is not context-scoped. Without the tenant in the hash, two contexts ingesting
     * the same document at the same revision would mint the same chunk id and a grounding lookup in
     * one context would reach the other's propositions.
     *
     * Without a revision the chunk keeps whatever id it arrived with — old requests are untouched.
     * The identity material is length-framed so no combination of values can be re-split into a
     * different one.
     *
     * The chunk text is in the identity, so the ids hold only as long as the chunker keeps producing
     * the same chunks. Re-posting one revision after a chunker configuration change re-mints the ids
     * and grounds onto fresh rows.
     */
    private fun revisionStableChunk(
        chunk: Chunk,
        contextId: String,
        sourceRevision: SourceRevisionRef?,
        ordinal: Int,
    ): Chunk {
        if (sourceRevision == null) return chunk
        val identityMaterial = listOf(
            contextId,
            sourceRevision.sourceKey,
            sourceRevision.sourceRevision,
            ordinal.toString(),
            chunk.text,
        ).joinToString(separator = "") { value -> "${value.length}:$value" }
        return Chunk.create(
            id = "source-revision:${Sha256ContentHasher.hash(identityMaterial)}",
            text = chunk.text,
            urtext = chunk.urtext,
            parentId = chunk.parentId,
            metadata = chunk.metadata,
        )
    }

    private fun buildContext(
        contextId: String,
        knownEntityDtos: List<KnownEntityDto>,
        schemaName: String? = null,
        sourceLocator: SourceLocator? = null,
        sourceRevision: SourceRevisionRef? = null,
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

        var context = SourceAnalysisContext(
            schema = schema,
            entityResolver = entityResolver,
            contextId = ContextId(contextId),
            knownEntities = knownEntities,
        )

        if (sourceLocator != null) {
            context = context.withSourceLocator(sourceLocator)
        }
        if (sourceRevision != null) {
            context = context.withSourceRevision(sourceRevision)
        }
        return context
    }

    /**
     * Turns one chunk's extraction result into the response body.
     *
     * [storedByOfferedId] maps the id each proposition was offered to the store under to whatever
     * the store answered with, and every proposition named in the response goes through it. That is
     * what keeps the response honest: an id a caller reads here is an id
     * [PropositionRepository.findById] will resolve.
     */
    private fun buildExtractResponse(
        chunkId: String,
        contextId: String,
        result: ChunkPropositionResult,
        storedByOfferedId: Map<String, Proposition>,
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
            // One entry per extracted proposition, which is what `take` bounds the list to when
            // the pipeline hands back a different number of revision results.
            result.revisionResults.take(result.propositions.size).map { revisionResult ->
                val written = storedForm(revisionResult.writtenProposition(), storedByOfferedId)
                PropositionDto.from(written, revisionResult)
            }
        } else {
            result.propositions.map { PropositionDto.from(storedForm(it, storedByOfferedId), "CREATED") }
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

    /**
     * The proposition [proposition] became once the store had it, falling back to [proposition]
     * itself when the store said nothing about it.
     */
    private fun storedForm(proposition: Proposition, storedByOfferedId: Map<String, Proposition>): Proposition =
        storedByOfferedId[proposition.id] ?: proposition

    /**
     * The proposition a revision outcome writes to the store.
     *
     * A merge or a reinforcement folds the new claim into an existing proposition and writes that
     * one; the freshly extracted proposition is never stored, so its id would resolve to nothing.
     * A contradiction writes both sides and this names the new claim, which is the one the caller
     * asked for; the retired original is counted in the revision summary.
     */
    private fun RevisionResult.writtenProposition(): Proposition = when (this) {
        is RevisionResult.New -> proposition
        is RevisionResult.Merged -> revised
        is RevisionResult.Reinforced -> revised
        is RevisionResult.Contradicted -> new
        is RevisionResult.Generalized -> proposition
    }

    /**
     * Answers 400 with the reason a request was refused, so a caller can read which check it broke
     * — a length ceiling names its limit. Declared on this controller alone, so nothing else in a
     * host application changes shape.
     */
    @ExceptionHandler(InvalidSourceProvenanceException::class)
    fun handleInvalidSourceProvenance(
        e: InvalidSourceProvenanceException,
    ): ResponseEntity<ExtractErrorResponse> =
        ResponseEntity.badRequest().body(ExtractErrorResponse(e.message ?: DEFAULT_REJECTION_REASON))

    /**
     * Builds the locator the request names, rejecting any field combination that would silently
     * mean something else. `connectorId` belongs to `connector` locators alone; the other three
     * kinds refuse it.
     *
     * A connector id may hold any character, colons included. [ConnectorRef] escapes its own
     * connector id when it renders a key, so `"a:b"` and `"a"` stay distinguishable and the key
     * round-trips to the same string every time. Screening colons out here would refuse ids the
     * domain type handles perfectly well, such as a region-qualified `gmail:eu-west`.
     */
    private fun SourceLocatorInputDto.toSourceLocator(): SourceLocator {
        require(value.isNotBlank()) { "sourceLocator.value must not be blank" }
        return when (kind) {
            "uri" -> {
                require(connectorId == null) { "uri sourceLocator must not set connectorId" }
                UriLocator(value, display)
            }

            "file" -> {
                require(connectorId == null) { "file sourceLocator must not set connectorId" }
                FileLocator(value, display)
            }

            "content" -> {
                require(connectorId == null) { "content sourceLocator must not set connectorId" }
                ContentAddressedLocator(value, display)
            }

            "connector" -> {
                require(!connectorId.isNullOrBlank()) { "connector sourceLocator requires connectorId" }
                ConnectorRef(connectorId, value, display)
            }

            else -> throw IllegalArgumentException("Unsupported sourceLocator.kind: $kind")
        }
    }
}

/** What a refused request is called when the check that refused it left no message. */
private const val DEFAULT_REJECTION_REASON = "invalid source provenance"

/**
 * A request whose source locator or revision the extraction endpoints refuse.
 *
 * It carries the text of the check that failed, and [PropositionPipelineController] hands that text
 * back in the 400 body, so a caller reading a length rejection sees which ceiling it broke and by
 * how much.
 */
class InvalidSourceProvenanceException(message: String) : RuntimeException(message)
