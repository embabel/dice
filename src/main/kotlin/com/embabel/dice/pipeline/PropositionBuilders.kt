package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.*
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import org.slf4j.LoggerFactory
/**
 * Fluent API for building proposition extraction pipelines.
 *
 * Example usage:
 * ```kotlin
 * val pipeline = PropositionBuilders
 *     .withExtractor(LlmPropositionExtractor(ai))
 *     .withStore(InMemoryPropositionRepository(embeddingService))
 *     .withReviser(LlmPropositionReviser(ai))  // Optional: enables merge/reinforce/contradict
 *     .build()
 *
 * val result = pipeline.process(chunks, context)
 * // With revision enabled, result includes revision statistics:
 * // - result.newCount, result.mergedCount, result.reinforcedCount, result.contradictedCount
 *
 * // Project to different formats as needed:
 * val graphResults = graphProjector.projectAll(result.allPropositions, schema)
 * val prologResults = prologProjector.projectAll(result.allPropositions, schema)
 * ```
 */
object PropositionBuilders {

    @JvmStatic
    fun withExtractor(extractor: PropositionExtractor): StoreStep =
        StoreStep(extractor)
}

/**
 * Builder step for configuring proposition store.
 */
class StoreStep(
    private val extractor: PropositionExtractor,
) {
    /**
     * Use a custom proposition store.
     * Note: InMemoryPropositionRepository requires an EmbeddingService for vector similarity search.
     */
    fun withStore(store: PropositionRepository): PropositionPipelineBuilder =
        PropositionPipelineBuilder(extractor, store)
}

/**
 * Final builder step before creating the pipeline.
 */
class PropositionPipelineBuilder(
    private val extractor: PropositionExtractor,
    private val store: PropositionRepository,
    private var reviser: PropositionReviser? = null,
) {
    /**
     * Add a reviser to merge/reinforce/contradict propositions against existing ones.
     * When enabled, new propositions are compared against existing ones in the repository
     * and merged if identical, reinforced if similar, or marked as contradicting if conflicting.
     *
     * @param reviser The proposition reviser to use
     */
    fun withReviser(reviser: PropositionReviser): PropositionPipelineBuilder {
        this.reviser = reviser
        return this
    }

    fun build(): PropositionPipeline =
        PropositionPipeline(extractor, reviser, store)
}

/**
 * Result of processing a single chunk through the proposition pipeline.
 */
data class ChunkPropositionResult(
    val chunkId: String,
    val suggestedPropositions: SuggestedPropositions,
    val entityResolutions: Resolutions<SuggestedEntityResolution>,
    val propositions: List<Proposition>,
    val revisionResults: List<RevisionResult> = emptyList(),
) : EntityExtractionResult {
    /** Number of propositions that were new (not similar to existing) */
    val newCount: Int get() = revisionResults.count { it is RevisionResult.New }

    /** Number of propositions that were merged with existing identical ones */
    val mergedCount: Int get() = revisionResults.count { it is RevisionResult.Merged }

    /** Number of propositions that reinforced existing similar ones */
    val reinforcedCount: Int get() = revisionResults.count { it is RevisionResult.Reinforced }

    /** Number of propositions that contradicted existing ones */
    val contradictedCount: Int get() = revisionResults.count { it is RevisionResult.Contradicted }

    /** Whether revision was enabled for this chunk */
    val hasRevision: Boolean get() = revisionResults.isNotEmpty()

    // ===========================================
    // EntityExtractionResult Implementation
    // ===========================================

    override fun newEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .filterIsInstance<NewEntity>()
            .map { it.suggested.suggestedEntity }

    override fun updatedEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .filterIsInstance<ExistingEntity>()
            .map { it.existing }
}

/**
 * Result of processing multiple chunks through the proposition pipeline.
 * Implements [EntityExtractionResult] for access to entities needing persistence.
 *
 * This result contains all extracted data but does NOT persist anything.
 * The caller is responsible for persisting entities and propositions as needed.
 */
data class PropositionExtractionResult(
    val chunkResults: List<ChunkPropositionResult>,
    val allPropositions: List<Proposition>,
) : EntityExtractionResult {

    val totalPropositions: Int get() = allPropositions.size
    val fullyResolvedCount: Int get() = allPropositions.count { it.isFullyResolved() }
    val partiallyResolvedCount: Int get() = allPropositions.count { !it.isFullyResolved() && it.mentions.any { m -> m.resolvedId != null } }
    val unresolvedCount: Int get() = allPropositions.count { it.mentions.none { m -> m.resolvedId != null } }

    /** All revision results across all chunks */
    val allRevisionResults: List<RevisionResult> get() = chunkResults.flatMap { it.revisionResults }

    /** Whether revision was enabled */
    val hasRevision: Boolean get() = chunkResults.any { it.hasRevision }

    /** Total new propositions (not similar to existing) */
    val newCount: Int get() = chunkResults.sumOf { it.newCount }

    /** Total merged propositions */
    val mergedCount: Int get() = chunkResults.sumOf { it.mergedCount }

    /** Total reinforced propositions */
    val reinforcedCount: Int get() = chunkResults.sumOf { it.reinforcedCount }

    /** Total contradicted propositions */
    val contradictedCount: Int get() = chunkResults.sumOf { it.contradictedCount }

    // ===========================================
    // EntityExtractionResult Implementation
    // ===========================================

    override fun newEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.newEntities() }.distinctBy { it.id }

    override fun updatedEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.updatedEntities() }.distinctBy { it.id }
}

/**
 * Pipeline for extracting propositions from chunks.
 * Coordinates extraction and entity resolution.
 *
 * This pipeline does NOT persist anything. It returns a [PropositionExtractionResult]
 * containing all extracted entities and propositions. The caller is responsible for
 * persisting these to the appropriate repositories.
 *
 * When a [PropositionReviser] is configured with a [PropositionRepository], the pipeline
 * will compare new propositions against existing ones and classify them as new, merged,
 * reinforced, or contradicted.
 *
 * @param extractor The proposition extractor to use
 * @param reviser Optional reviser for comparing against existing propositions
 * @param propositionRepository Required if reviser is set, for reading existing propositions
 */
class PropositionPipeline(
    private val extractor: PropositionExtractor,
    private val reviser: PropositionReviser? = null,
    private val propositionRepository: PropositionRepository? = null,
) {
    init {
        if (reviser != null && propositionRepository == null) {
            throw IllegalArgumentException("PropositionRepository is required when using a reviser")
        }
    }

    private val logger = LoggerFactory.getLogger(PropositionPipeline::class.java)

    /** Whether revision is enabled for this pipeline */
    val hasRevision: Boolean get() = reviser != null

    /**
     * Process a single chunk through the pipeline.
     * Extracts propositions and resolves entities.
     *
     * If a reviser is configured, propositions are compared against existing ones
     * and classified. Otherwise, propositions are returned without revision.
     *
     * Note: This method does NOT persist anything. The caller should persist
     * entities and propositions from the returned result.
     *
     * @param chunk The chunk to process
     * @param context Configuration including schema and entity resolver
     * @return Processing result with propositions, entities, and optional revision results
     */
    fun processChunk(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): ChunkPropositionResult {
        logger.debug("Processing chunk: {}", chunk.id)

        // Step 1: Extract propositions from chunk
        val suggestedPropositions = extractor.extract(chunk, context)
        logger.debug("Extracted {} propositions", suggestedPropositions.propositions.size)

        // Step 2: Convert mentions to suggested entities
        val suggestedEntities = extractor.toSuggestedEntities(suggestedPropositions)
        logger.debug("Created {} suggested entities", suggestedEntities.suggestedEntities.size)

        // Step 3: Resolve entities using existing resolver
        val resolutions = context.entityResolver.resolve(suggestedEntities, context.schema)
        logger.debug("Resolved {} entities", resolutions.resolutions.size)

        // Step 4: Apply resolutions to create final propositions
        val propositions = extractor.resolvePropositions(suggestedPropositions, resolutions)
        logger.debug("Created {} propositions", propositions.size)

        // Step 5: Optionally revise propositions against existing ones
        val revisionResults = if (reviser != null && propositionRepository != null) {
            val results = reviser.reviseAll(propositions, propositionRepository)
            logger.debug(
                "Revised {} propositions: {} new, {} merged, {} reinforced, {} contradicted",
                results.size,
                results.count { it is RevisionResult.New },
                results.count { it is RevisionResult.Merged },
                results.count { it is RevisionResult.Reinforced },
                results.count { it is RevisionResult.Contradicted },
            )
            results
        } else {
            emptyList()
        }

        return ChunkPropositionResult(
            chunkId = chunk.id,
            suggestedPropositions = suggestedPropositions,
            entityResolutions = resolutions,
            propositions = propositions,
            revisionResults = revisionResults,
        )
    }

    /**
     * Process multiple chunks through the pipeline.
     *
     * @param chunks The chunks to process
     * @param context Configuration including schema
     * @return Aggregated processing results
     */
    fun process(
        chunks: List<Chunk>,
        context: SourceAnalysisContext,
    ): PropositionExtractionResult {
        logger.info("Processing {} chunks{}", chunks.size, if (reviser != null) " with revision" else "")

        val chunkResults = chunks.map { chunk ->
            processChunk(chunk, context)
        }

        val allPropositions = chunkResults.flatMap { it.propositions }

        val result = PropositionExtractionResult(
            chunkResults = chunkResults,
            allPropositions = allPropositions,
        )

        if (result.hasRevision) {
            logger.info(
                "Extracted {} propositions from {} chunks: {} new, {} merged, {} reinforced, {} contradicted ({} fully resolved)",
                allPropositions.size,
                chunks.size,
                result.newCount,
                result.mergedCount,
                result.reinforcedCount,
                result.contradictedCount,
                allPropositions.count { it.isFullyResolved() }
            )
        } else {
            logger.info(
                "Extracted {} propositions from {} chunks ({} fully resolved)",
                allPropositions.size,
                chunks.size,
                allPropositions.count { it.isFullyResolved() }
            )
        }

        return result
    }
}
