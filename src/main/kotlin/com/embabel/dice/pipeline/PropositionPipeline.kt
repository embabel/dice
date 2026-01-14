package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.ChainedEntityResolver
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.common.resolver.KnownEntityResolver
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import org.slf4j.LoggerFactory

/**
 * Pipeline for extracting propositions from chunks.
 * Coordinates extraction and entity resolution.
 *
 * Example usage:
 * ```kotlin
 * val pipeline = PropositionPipeline
 *     .withExtractor(LlmPropositionExtractor(ai))
 *     .withRevision(reviser, propositionRepository)  // Optional
 *
 * val result = pipeline.process(chunks, context)
 * ```
 *
 * This pipeline does NOT persist anything. It returns a [PropositionResults]
 * containing all extracted entities and propositions. The caller is responsible for
 * persisting these to the appropriate repositories.
 *
 * When a [PropositionReviser] is configured with a [PropositionRepository], the pipeline
 * will compare new propositions against existing ones and classify them as new, merged,
 * reinforced, or contradicted.
 */
class PropositionPipeline private constructor(
    private val extractor: PropositionExtractor,
    private val reviser: PropositionReviser? = null,
    private val propositionRepository: PropositionRepository? = null,
) {

    companion object {

        /**
         * Create a new pipeline with the given extractor.
         *
         * @param extractor The proposition extractor to use
         * @return A new pipeline instance
         */
        @JvmStatic
        fun withExtractor(extractor: PropositionExtractor): PropositionPipeline =
            PropositionPipeline(extractor)
    }

    private val logger = LoggerFactory.getLogger(PropositionPipeline::class.java)

    /** Whether revision is enabled for this pipeline */
    val hasRevision: Boolean get() = reviser != null

    /**
     * Add a reviser to compare new propositions against existing ones.
     * When enabled, propositions are classified as new, merged, reinforced, or contradicted.
     *
     * @param reviser The proposition reviser to use
     * @param propositionRepository Repository containing existing propositions to compare against
     * @return A new pipeline instance with revision enabled
     */
    fun withRevision(reviser: PropositionReviser, propositionRepository: PropositionRepository): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository)

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

        // Step 2: Convert mentions to suggested entities (include source text for context)
        val suggestedEntities = extractor.toSuggestedEntities(suggestedPropositions, context, chunk.text)
        logger.debug("Created {} suggested entities", suggestedEntities.suggestedEntities.size)

        // Step 3: Resolve entities using existing resolver (wrapped with known entities)
        val resolver = KnownEntityResolver.withKnownEntities(context.knownEntities, context.entityResolver)
        val resolutions = resolver.resolve(suggestedEntities, context.schema)
        logger.debug("Resolved {} entities", resolutions.resolutions.size)

        // Step 4: Apply resolutions to create final propositions
        val propositions = extractor.resolvePropositions(suggestedPropositions, resolutions, context)
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
     * For cross-chunk entity resolution, the context's EntityResolver is wrapped with
     * an InMemoryEntityResolver via MultiEntityResolver. This ensures entities discovered
     * in earlier chunks can be recognized in later chunks without external persistence.
     *
     * @param chunks The chunks to process
     * @param context Configuration including schema
     * @return Aggregated processing results
     */
    fun process(
        chunks: List<Chunk>,
        context: SourceAnalysisContext,
    ): PropositionResults {
        logger.info("Processing {} chunks{}", chunks.size, if (reviser != null) " with revision" else "")

        // Wrap the resolver with InMemoryEntityResolver for cross-chunk entity resolution.
        // Order: user's resolver first (for pre-existing entities), then in-memory (for this run's entities)
        val crossChunkResolver = ChainedEntityResolver(
            listOf(context.entityResolver, InMemoryEntityResolver())
        )
        val crossChunkContext = context.copy(entityResolver = crossChunkResolver)

        val chunkResults = chunks.map { chunk ->
            processChunk(chunk, crossChunkContext)
        }

        val allPropositions = chunkResults.flatMap { it.propositions }

        val result = PropositionResults(
            chunkResults = chunkResults,
            allPropositions = allPropositions,
        )

        if (result.hasRevision) {
            val stats = result.propositionExtractionStats
            logger.info(
                "Extracted {} propositions from {} chunks: {} new, {} generalized, {} merged, {} reinforced, {} contradicted ({} fully resolved)",
                allPropositions.size,
                chunks.size,
                stats.newCount,
                stats.generalizedCount,
                stats.mergedCount,
                stats.reinforcedCount,
                stats.contradictedCount,
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
