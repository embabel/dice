package com.embabel.dice.web.rest

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST controller for DICE memory operations.
 * Provides endpoints for managing stored propositions (CRUD, search, retrieval).
 */
@RestController
@RequestMapping("/api/v1/contexts/{contextId}/memory")
class MemoryController(
    private val propositionRepository: PropositionRepository,
) {

    private val logger = LoggerFactory.getLogger(MemoryController::class.java)

    /**
     * Retrieve all propositions for a context.
     */
    @GetMapping
    fun getMemory(
        @PathVariable contextId: String,
        @RequestParam(required = false) status: PropositionStatus?,
        @RequestParam(required = false, defaultValue = "0.0") minConfidence: Double,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): ResponseEntity<MemoryResponse> {
        logger.debug("Retrieving memory for context: {}", contextId)

        var propositions = propositionRepository.findByContextId(ContextId(contextId))

        if (status != null) {
            propositions = propositions.filter { it.status == status }
        }
        if (minConfidence > 0.0) {
            propositions = propositions.filter { it.confidence >= minConfidence }
        }
        propositions = propositions.take(limit)

        val response = MemoryResponse(
            contextId = contextId,
            count = propositions.size,
            propositions = propositions.map { PropositionDto.from(it) },
        )

        return ResponseEntity.ok(response)
    }

    /**
     * Search memory by similarity.
     */
    @PostMapping("/search")
    fun searchMemory(
        @PathVariable contextId: String,
        @RequestBody request: MemorySearchRequest,
    ): ResponseEntity<MemorySearchResponse> {
        logger.debug("Searching memory for context: {} query: {}", contextId, request.query)

        val searchRequest = TextSimilaritySearchRequest(
            query = request.query,
            topK = request.topK,
            similarityThreshold = request.similarityThreshold,
        )

        val results = propositionRepository.findSimilarWithScores(searchRequest)
            .filter { result ->
                result.match.contextIdValue == contextId
            }
            .filter { result ->
                val statusFilter = request.filters.status
                statusFilter == null || result.match.status in statusFilter
            }
            .filter { result ->
                val minConf = request.filters.minConfidence
                minConf == null || result.match.confidence >= minConf
            }
            .filter { result ->
                val types = request.filters.mentionTypes
                types == null || result.match.mentions.any { it.type in types }
            }

        val response = MemorySearchResponse(
            contextId = contextId,
            query = request.query,
            results = results.map { result ->
                SimilarityResultDto(
                    proposition = PropositionDto.from(result.match),
                    score = result.score,
                )
            },
        )

        return ResponseEntity.ok(response)
    }

    /**
     * Get propositions mentioning a specific entity.
     */
    @GetMapping("/entity/{entityType}/{entityId}")
    fun getMemoryByEntity(
        @PathVariable contextId: String,
        @PathVariable entityType: String,
        @PathVariable entityId: String,
    ): ResponseEntity<EntityMemoryResponse> {
        logger.debug("Retrieving memory by entity: {}:{} for context: {}", entityType, entityId, contextId)

        val identifier = RetrievableIdentifier(entityId, entityType)
        val propositions = propositionRepository.findByEntity(identifier)
            .filter { it.contextIdValue == contextId }

        val response = EntityMemoryResponse(
            entity = EntityReference(
                id = entityId,
                type = entityType,
                name = null,
            ),
            propositions = propositions.map { PropositionDto.from(it) },
        )

        return ResponseEntity.ok(response)
    }

    /**
     * Create a proposition directly (without extraction).
     */
    @PostMapping
    fun createProposition(
        @PathVariable contextId: String,
        @RequestBody request: CreatePropositionRequest,
    ): ResponseEntity<PropositionDto> {
        logger.info("Creating proposition for context: {}", contextId)

        val proposition = Proposition(
            contextId = ContextId(contextId),
            text = request.text,
            mentions = request.mentions.map { it.toEntityMention() },
            confidence = request.confidence.coerceIn(0.0, 1.0),
            decay = request.decay.coerceIn(0.0, 1.0),
            reasoning = request.reasoning,
        )

        val saved = propositionRepository.save(proposition)

        return ResponseEntity.status(HttpStatus.CREATED).body(
            PropositionDto.from(saved, "CREATED")
        )
    }

    /**
     * Get a specific proposition by ID.
     */
    @GetMapping("/{propositionId}")
    fun getProposition(
        @PathVariable contextId: String,
        @PathVariable propositionId: String,
    ): ResponseEntity<PropositionDto> {
        val proposition = propositionRepository.findById(propositionId)
            ?: return ResponseEntity.notFound().build()

        if (proposition.contextIdValue != contextId) {
            return ResponseEntity.notFound().build()
        }

        return ResponseEntity.ok(PropositionDto.from(proposition))
    }

    /**
     * Delete (retract) a proposition.
     */
    @DeleteMapping("/{propositionId}")
    fun deleteProposition(
        @PathVariable contextId: String,
        @PathVariable propositionId: String,
    ): ResponseEntity<DeleteResponse> {
        val existing = propositionRepository.findById(propositionId)
            ?: return ResponseEntity.notFound().build()

        if (existing.contextIdValue != contextId) {
            return ResponseEntity.notFound().build()
        }

        val deleted = propositionRepository.delete(propositionId)

        return if (deleted) {
            ResponseEntity.ok(DeleteResponse(
                id = propositionId,
                status = PropositionStatus.CONTRADICTED,
                previousStatus = existing.status,
            ))
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
