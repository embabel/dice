package com.embabel.dice.pipeline

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.text2graph.builder.SourceAnalysisConfig
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Fluent API for building proposition extraction pipelines.
 *
 * Example usage:
 * ```kotlin
 * val pipeline = PropositionBuilders
 *     .withExtractor(LlmPropositionExtractor(ai))
 *     .withEntityResolver(InMemoryEntityResolver())
 *     .withStore(InMemoryPropositionRepository(embeddingService))
 *     .withReviser(LlmPropositionReviser(ai))  // Optional: enables merge/reinforce/contradict
 *     .withProjector(LlmGraphProjector(ai))
 *     .withProjector(DefaultPrologProjector(prologSchema = schema))
 *     .build()
 *
 * val result = pipeline.process(chunks, config)
 * // With revision enabled, result includes revision statistics:
 * // - result.newCount, result.mergedCount, result.reinforcedCount, result.contradictedCount
 * val graphResults = pipeline.project<GraphProjector>(result, config)
 * ```
 */
object PropositionBuilders {

    @JvmStatic
    fun withExtractor(extractor: PropositionExtractor): EntityResolverStep =
        EntityResolverStep(extractor)
}

/**
 * Builder step for configuring entity resolver.
 */
class EntityResolverStep(
    private val extractor: PropositionExtractor,
) {
    fun withEntityResolver(entityResolver: EntityResolver): StoreStep =
        StoreStep(extractor, entityResolver)
}

/**
 * Builder step for configuring proposition store.
 */
class StoreStep(
    private val extractor: PropositionExtractor,
    private val entityResolver: EntityResolver,
) {
    /**
     * Use a custom proposition store.
     * Note: InMemoryPropositionRepository requires an EmbeddingService for vector similarity search.
     */
    fun withStore(store: PropositionRepository): PropositionPipelineBuilder =
        PropositionPipelineBuilder(extractor, entityResolver, store)
}

/**
 * Final builder step before creating the pipeline.
 */
class PropositionPipelineBuilder(
    private val extractor: PropositionExtractor,
    private val entityResolver: EntityResolver,
    private val store: PropositionRepository,
    private val projectors: MutableList<Projector<*>> = mutableListOf(),
    private var reviser: PropositionReviser? = null,
) {
    /**
     * Add a projector for transforming propositions to a typed output.
     * Multiple projectors can be added for different projection types.
     *
     * @param projector Any projector implementation (GraphProjector, PrologProjector, etc.)
     */
    fun <T : Projected> withProjector(projector: Projector<T>): PropositionPipelineBuilder {
        projectors.add(projector)
        return this
    }

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
        PropositionPipeline(extractor, store, projectors.toList(), reviser)
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
) {
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
}

/**
 * Result of processing multiple chunks through the proposition pipeline.
 */
data class PropositionExtractionResult(
    val chunkResults: List<ChunkPropositionResult>,
    val allPropositions: List<Proposition>,
) {
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
}

/**
 * Pipeline for extracting propositions from chunks.
 * Coordinates extraction, entity resolution, storage, and projection to typed outputs.
 *
 * When a [PropositionReviser] is configured, the pipeline will compare new propositions
 * against existing ones and merge, reinforce, or mark contradictions instead of simply
 * storing all propositions.
 */
class PropositionPipeline(
    private val extractor: PropositionExtractor,
    private val store: PropositionRepository,
    private val projectors: List<Projector<*>> = emptyList(),
    private val reviser: PropositionReviser? = null,
) {
    private val logger = LoggerFactory.getLogger(PropositionPipeline::class.java)

    /** Whether revision is enabled for this pipeline */
    val hasRevision: Boolean get() = reviser != null

    /**
     * Get a projector by its class type.
     *
     * @param projectorClass The class of the projector to retrieve
     * @return The projector instance, or null if not configured
     */
    @Suppress("UNCHECKED_CAST")
    fun <P : Projector<*>> projector(projectorClass: KClass<P>): P? =
        projectors.firstOrNull { projectorClass.isInstance(it) } as P?

    /**
     * Get a projector by its class type (reified version).
     */
    inline fun <reified P : Projector<*>> projector(): P? = projector(P::class)

    /**
     * Check if a projector of the given type is configured.
     */
    fun <P : Projector<*>> hasProjector(projectorClass: KClass<P>): Boolean =
        projectors.any { projectorClass.isInstance(it) }

    /**
     * Check if a projector of the given type is configured (reified version).
     */
    inline fun <reified P : Projector<*>> hasProjector(): Boolean = hasProjector(P::class)

    /**
     * Project propositions using the specified projector type.
     *
     * @param projectorClass The class of the projector to use
     * @param extractionResult The extraction result containing propositions
     * @param schema The data dictionary for projection
     * @return Projection results
     * @throws IllegalStateException if no projector of the given type is configured
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Projected, P : Projector<T>> project(
        projectorClass: KClass<P>,
        extractionResult: PropositionExtractionResult,
        schema: DataDictionary,
    ): ProjectionResults<T> {
        val projector = projector(projectorClass)
            ?: throw IllegalStateException("No ${projectorClass.simpleName} configured. Use withProjector() when building the pipeline.")

        logger.info("Projecting {} propositions with {}", extractionResult.totalPropositions, projectorClass.simpleName)

        val results = (projector as Projector<T>).projectAll(extractionResult.allPropositions, schema)

        logger.info(
            "Projection complete: {} projected, {} skipped, {} failed",
            results.successCount,
            results.skipCount,
            results.failureCount
        )

        return results
    }

    /**
     * Project propositions using a projector instance directly.
     *
     * @param projector The projector to use
     * @param propositions The propositions to project
     * @param schema The data dictionary for projection
     * @return Projection results
     */
    fun <T : Projected> project(
        projector: Projector<T>,
        propositions: List<Proposition>,
        schema: DataDictionary,
    ): ProjectionResults<T> {
        logger.info("Projecting {} propositions", propositions.size)
        return projector.projectAll(propositions, schema)
    }

    /**
     * Process a single chunk through the pipeline.
     * Extracts propositions, resolves entities, and stores results.
     *
     * If a reviser is configured, propositions are compared against existing ones
     * and merged/reinforced/contradicted as appropriate. Otherwise, all propositions
     * are stored directly.
     *
     * @param chunk The chunk to process
     * @param config Configuration including schema
     * @return Processing result with propositions and optional revision results
     */
    fun processChunk(
        chunk: Chunk,
        config: SourceAnalysisConfig,
    ): ChunkPropositionResult {
        logger.debug("Processing chunk: {}", chunk.id)

        // Step 1: Extract propositions from chunk
        val suggestedPropositions = extractor.extract(chunk, config)
        logger.debug("Extracted {} propositions", suggestedPropositions.propositions.size)

        // Step 2: Convert mentions to suggested entities
        val suggestedEntities = extractor.toSuggestedEntities(suggestedPropositions)
        logger.debug("Created {} suggested entities", suggestedEntities.suggestedEntities.size)

        // Step 3: Resolve entities using existing resolver
        val resolutions = config.entityResolver.resolve(suggestedEntities, config.schema)
        logger.debug("Resolved {} entities", resolutions.resolutions.size)

        // Step 4: Apply resolutions to create final propositions
        val propositions = extractor.resolvePropositions(suggestedPropositions, resolutions)
        logger.debug("Created {} propositions", propositions.size)

        // Step 5: Store or revise propositions
        val revisionResults = if (reviser != null) {
            // Revise each proposition against existing ones
            val results = reviser.reviseAll(propositions, store)
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
            // No reviser - store all propositions directly
            store.saveAll(propositions)
            logger.debug("Stored {} propositions", propositions.size)
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
     * @param config Configuration including schema
     * @return Aggregated processing results
     */
    fun process(
        chunks: List<Chunk>,
        config: SourceAnalysisConfig,
    ): PropositionExtractionResult {
        logger.info("Processing {} chunks{}", chunks.size, if (reviser != null) " with revision" else "")

        val chunkResults = chunks.map { chunk ->
            processChunk(chunk, config)
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

    /**
     * Get all configured projectors.
     */
    fun projectors(): List<Projector<*>> = projectors

    /**
     * Get the proposition store for querying.
     */
    fun store(): PropositionRepository = store
}
