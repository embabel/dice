package com.embabel.dice.incremental.entity

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.incremental.AbstractIncrementalAnalyzer
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.incremental.IncrementalSourceFormatter
import com.embabel.dice.incremental.WindowConfig
import com.embabel.dice.pipeline.ChunkEntityResult
import com.embabel.dice.pipeline.EntityPipeline
import org.slf4j.LoggerFactory

/**
 * Incremental analyzer that extracts entities (without propositions) using an [EntityPipeline].
 *
 * This is a simpler alternative to [com.embabel.dice.incremental.proposition.PropositionIncrementalAnalyzer]
 * when you only need entity extraction from conversations or other incremental sources.
 *
 * Example usage:
 * ```kotlin
 * val entityExtractor = LlmEntityExtractor.withLlm(llmOptions).withAi(ai)
 * val pipeline = EntityPipeline.withExtractor(entityExtractor)
 *
 * val analyzer = EntityIncrementalAnalyzer(
 *     pipeline = pipeline,
 *     historyStore = myHistoryStore,
 *     formatter = MessageFormatter.INSTANCE,
 * )
 *
 * val source = ConversationSource(conversation)
 * val result = analyzer.analyze(source, context)
 *
 * // Persist extracted entities
 * result?.persist(entityRepository)
 * ```
 *
 * @param T The type of items in the source
 * @param pipeline The entity extraction pipeline
 * @param historyStore Tracks processing history
 * @param formatter Formats source items to text
 * @param config Window and trigger configuration
 */
class EntityIncrementalAnalyzer<T>(
    private val pipeline: EntityPipeline,
    historyStore: ChunkHistoryStore,
    formatter: IncrementalSourceFormatter<T>,
    config: WindowConfig = WindowConfig(),
) : AbstractIncrementalAnalyzer<T, ChunkEntityResult>(historyStore, formatter, config) {

    private val logger = LoggerFactory.getLogger(EntityIncrementalAnalyzer::class.java)

    override fun processChunk(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): ChunkEntityResult = pipeline.processChunk(chunk, context)

    override fun onProcessed(
        source: IncrementalSource<T>,
        startIndex: Int,
        endIndex: Int,
        result: ChunkEntityResult,
    ) {
        val stats = result.entityExtractionStats
        logger.info(
            "Analyzed source {} [{}-{}]: {} entities extracted ({} new, {} updated)",
            source.id, startIndex, endIndex,
            stats.total, stats.newCount, stats.updatedCount
        )
    }
}
