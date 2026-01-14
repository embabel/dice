package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.ChainedEntityResolver
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.common.resolver.KnownEntityResolver
import com.embabel.dice.text2graph.EntityExtractor
import org.slf4j.LoggerFactory

/**
 * Pipeline for extracting and resolving entities from chunks.
 *
 * This is a simpler alternative to [PropositionPipeline] when you only need
 * entity extraction without proposition creation.
 *
 * Example usage:
 * ```kotlin
 * val pipeline = EntityPipeline
 *     .withExtractor(LlmEntityExtractor.withLlm(llmOptions).withAi(ai))
 *
 * val result = pipeline.processChunk(chunk, context)
 * entityRepository.saveAll(result.entitiesToPersist())
 * ```
 *
 * This pipeline does NOT persist anything. It returns an [EntityResults]
 * containing all extracted entities. The caller is responsible for
 * persisting these to the appropriate repository.
 *
 * @param extractor The entity extractor to use
 */
class EntityPipeline private constructor(
    private val extractor: EntityExtractor,
) {

    companion object {

        private val logger = LoggerFactory.getLogger(EntityPipeline::class.java)

        /**
         * Create a new pipeline with the given extractor.
         *
         * @param extractor The entity extractor to use
         * @return A new pipeline instance
         */
        @JvmStatic
        fun withExtractor(extractor: EntityExtractor): EntityPipeline =
            EntityPipeline(extractor)
    }

    /**
     * Process a single chunk through the pipeline.
     * Extracts entities and resolves them against the knowledge graph.
     *
     * Note: This method does NOT persist anything. The caller should persist
     * entities from the returned result.
     *
     * @param chunk The chunk to process
     * @param context Configuration including schema and entity resolver
     * @return Processing result with resolved entities
     */
    fun processChunk(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): ChunkEntityResult {
        logger.debug("Processing chunk for entities: {}", chunk.id)

        // Step 1: Extract entities from chunk
        val suggestedEntities = extractor.suggestEntities(chunk, context)
        logger.debug("Extracted {} suggested entities", suggestedEntities.suggestedEntities.size)

        // Step 2: Resolve entities using existing resolver (wrapped with known entities)
        val resolver = KnownEntityResolver.withKnownEntities(context.knownEntities, context.entityResolver)
        val resolutions = resolver.resolve(suggestedEntities, context.schema)
        logger.debug("Resolved {} entities", resolutions.resolutions.size)

        return ChunkEntityResult(
            chunkId = chunk.id,
            suggestedEntities = suggestedEntities,
            entityResolutions = resolutions,
        )
    }

    /**
     * Process multiple chunks through the pipeline.
     *
     * For cross-chunk entity resolution, the context's EntityResolver is wrapped with
     * an InMemoryEntityResolver via ChainedEntityResolver. This ensures entities discovered
     * in earlier chunks can be recognized in later chunks without external persistence.
     *
     * @param chunks The chunks to process
     * @param context Configuration including schema
     * @return Aggregated processing results
     */
    fun process(
        chunks: List<Chunk>,
        context: SourceAnalysisContext,
    ): EntityResults {
        logger.info("Processing {} chunks for entities", chunks.size)

        // Wrap the resolver with InMemoryEntityResolver for cross-chunk entity resolution.
        // Order: user's resolver first (for pre-existing entities), then in-memory (for this run's entities)
        val crossChunkResolver = ChainedEntityResolver(
            listOf(context.entityResolver, InMemoryEntityResolver())
        )
        val crossChunkContext = context.copy(entityResolver = crossChunkResolver)

        val chunkResults = chunks.map { chunk ->
            processChunk(chunk, crossChunkContext)
        }

        val result = EntityResults(chunkResults = chunkResults)

        val stats = result.entityExtractionStats
        logger.info(
            "Extracted {} entities from {} chunks: {} new, {} updated, {} reference-only",
            result.totalResolved,
            chunks.size,
            stats.newCount,
            stats.updatedCount,
            stats.referenceOnlyCount,
        )

        return result
    }
}
