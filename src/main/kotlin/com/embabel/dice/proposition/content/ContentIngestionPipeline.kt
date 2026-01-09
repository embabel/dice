package com.embabel.dice.proposition.content

import com.embabel.agent.api.common.Ai
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.revision.LlmPropositionReviser
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Result of processing content through the ingest pipeline.
 */
data class IngestionResult(
    val contentId: String,
    val content: ProposableContent,
    val revisionResults: List<RevisionResult>,
    val timestamp: Instant = Instant.now(),
) {
    val propositionCount: Int get() = revisionResults.size
    val newCount: Int get() = revisionResults.count { it is RevisionResult.New }
    val mergedCount: Int get() = revisionResults.count { it is RevisionResult.Merged }
    val reinforcedCount: Int get() = revisionResults.count { it is RevisionResult.Reinforced }
    val contradictedCount: Int get() = revisionResults.count { it is RevisionResult.Contradicted }
}

/**
 * Statistics about the current pipeline state.
 */
data class IngestionStats(
    val totalPropositions: Int,
    val activePropositions: Int,
    val contradictedPropositions: Int,
    val averageConfidence: Double,
    val oldestProposition: Instant?,
    val newestProposition: Instant?,
)

/**
 * Generic pipeline for ingesting content and extracting propositions.
 *
 * This pipeline implements the core loop:
 * 1. **Propose** - Extracts propositions from content using LLM
 * 2. **Retrieve** - Finds similar propositions using vector similarity
 * 3. **Revise** - Merges, reinforces, or contradicts based on classification
 *
 * Works with any [ProposableContent] - sensor observations, document chunks, etc.
 *
 * Key design principles:
 * - Propositions are never deleted, only marked as contradicted
 * - Confidence decays over time based on proposition-specific decay rate
 * - Similar propositions reinforce each other, boosting confidence
 * - Contradictory evidence reduces confidence of existing propositions
 */
class ContentIngestionPipeline(
    private val proposer: PropositionExtractor,
    private val reviser: PropositionReviser,
    private val repository: PropositionRepository,
) {
    private val logger = LoggerFactory.getLogger(ContentIngestionPipeline::class.java)

    /**
     * Process content through the pipeline.
     *
     * @param content The content to process
     * @return Processing result with all revision outcomes
     */
    fun process(content: ProposableContent): IngestionResult {
        val contentId = content.sourceId

        logger.debug("Processing content: {} at {}", contentId, content.timestamp)

        if (!content.isProcessable()) {
            logger.debug("Content not processable: {}", contentId)
            return IngestionResult(
                contentId = contentId,
                content = content,
                revisionResults = emptyList(),
            )
        }

        // 1. Propose: Extract propositions from content
        val suggestions = proposer.propose(content)

        if (suggestions.isEmpty()) {
            logger.debug("No propositions extracted from content")
            return IngestionResult(
                contentId = contentId,
                content = content,
                revisionResults = emptyList(),
            )
        }

        logger.debug("Extracted {} propositions", suggestions.size)

        // 2. Convert to propositions with grounding
        val propositions =
            proposer.toPropositions(suggestions = suggestions, sourceId = contentId, contextId = content.contextId)

        // 3. Revise: For each proposition, retrieve similar and revise
        val results = propositions.map { proposition ->
            reviser.revise(proposition, repository)
        }

        logger.info(
            "Processed content: {} propositions ({} new, {} merged, {} reinforced, {} contradicted)",
            results.size,
            results.count { it is RevisionResult.New },
            results.count { it is RevisionResult.Merged },
            results.count { it is RevisionResult.Reinforced },
            results.count { it is RevisionResult.Contradicted },
        )

        return IngestionResult(
            contentId = contentId,
            content = content,
            revisionResults = results,
        )
    }

    /**
     * Process a stream of content.
     *
     * @param contentStream Flow of content to process
     * @return Flow of processing results
     */
    fun processStream(contentStream: Flow<ProposableContent>): Flow<IngestionResult> =
        contentStream.map { process(it) }

    /**
     * Process multiple content items.
     *
     * @param contents Content items to process
     * @return List of processing results
     */
    fun processAll(contents: List<ProposableContent>): List<IngestionResult> =
        contents.map { process(it) }

    /**
     * Query propositions relevant to a topic.
     *
     * @param query The query text
     * @param topK Maximum number of results
     * @param includeContradicted Whether to include contradicted propositions
     * @return Propositions ordered by relevance (with decay applied)
     */
    fun query(
        query: String,
        topK: Int = 10,
        includeContradicted: Boolean = false,
    ): List<Proposition> {
        val results = repository.findSimilar(
            TextSimilaritySearchRequest(
                query = query,
                similarityThreshold = 0.0,
                topK = topK * 2
            )
        )
            .filter { includeContradicted || it.status == PropositionStatus.ACTIVE }
            .map { it.withDecayApplied() }
            .sortedByDescending { it.confidence }
            .take(topK)

        return results
    }

    /**
     * Get all active propositions about an entity.
     *
     * @param entityId The entity ID
     * @return Propositions mentioning this entity
     */
    fun aboutEntity(entityId: RetrievableIdentifier): List<Proposition> =
        repository.findByEntity(entityId)
            .filter { it.status == PropositionStatus.ACTIVE }
            .map { it.withDecayApplied() }
            .sortedByDescending { it.confidence }

    /**
     * Get current pipeline statistics.
     */
    fun stats(): IngestionStats {
        val all = repository.findAll()
        val active = all.filter { it.status == PropositionStatus.ACTIVE }
        val contradicted = all.filter { it.status == PropositionStatus.CONTRADICTED }

        return IngestionStats(
            totalPropositions = all.size,
            activePropositions = active.size,
            contradictedPropositions = contradicted.size,
            averageConfidence = if (active.isNotEmpty()) {
                active.map { it.effectiveConfidence() }.average()
            } else 0.0,
            oldestProposition = all.minOfOrNull { it.created },
            newestProposition = all.maxOfOrNull { it.created },
        )
    }

    /**
     * Get the underlying repository.
     */
    fun repository(): PropositionRepository = repository

    companion object {
        /**
         * Create a pipeline with default LLM-based components.
         *
         * @param ai The AI service for LLM calls
         * @param embeddingService The embedding service for vector similarity
         * @param templateName Optional custom prompt template name
         * @return Configured pipeline
         */
        fun create(
            ai: Ai,
            embeddingService: EmbeddingService,
            templateName: String = "content_propose",
        ): ContentIngestionPipeline {
            val repository = InMemoryPropositionRepository(embeddingService)
            val proposer = LlmPropositionExtractor(ai, templateName = templateName)
            val reviser = LlmPropositionReviser
                .withLlm(LlmOptions())
                .withAi(ai)

            return ContentIngestionPipeline(
                proposer = proposer,
                reviser = reviser,
                repository = repository,
            )
        }

        /**
         * Create a pipeline with a custom repository.
         *
         * @param ai The AI service for LLM calls
         * @param repository Custom proposition repository
         * @param templateName Optional custom prompt template name
         * @return Configured pipeline
         */
        fun create(
            ai: Ai,
            repository: PropositionRepository,
            templateName: String = "content_propose",
        ): ContentIngestionPipeline {
            val proposer = LlmPropositionExtractor(ai, templateName = templateName)
            val reviser = LlmPropositionReviser
                .withLlm(LlmOptions())
                .withAi(ai)

            return ContentIngestionPipeline(
                proposer = proposer,
                reviser = reviser,
                repository = repository,
            )
        }
    }
}

