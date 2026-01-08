package com.embabel.dice.incremental

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Analyzes incremental sources (conversations, listening history, etc.) by:
 * - Tracking what's been processed to avoid re-processing
 * - Windowing with overlap to maintain context
 * - Hash-based deduplication
 *
 * Call [analyze] any time - it decides whether processing is needed based on
 * how much new content has accumulated since the last analysis.
 *
 * @param T The type of items in the source
 * @param pipeline The proposition extraction pipeline
 * @param historyStore Tracks processing history (caller provides implementation)
 * @param formatter Formats source items to text
 * @param config Window and trigger configuration
 */
class IncrementalAnalyzer<T>(
    private val pipeline: PropositionPipeline,
    private val historyStore: ChunkHistoryStore,
    private val formatter: IncrementalSourceFormatter<T>,
    private val config: WindowConfig = WindowConfig(),
) {

    private val logger = LoggerFactory.getLogger(IncrementalAnalyzer::class.java)

    /**
     * Analyze the source if enough new content has accumulated.
     *
     * @param source The incremental source to analyze
     * @param context Analysis context including schema, entity resolver, etc.
     * @return Processing result if analysis was performed, null if not ready or already processed
     */
    fun analyze(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
    ): ChunkPropositionResult? {
        val lastBookmark = historyStore.getLastBookmark(source.id)
        val lastIndex = lastBookmark?.endIndex ?: 0

        // Check if enough new content to trigger analysis
        val newItemCount = source.size - lastIndex
        if (newItemCount < config.triggerInterval) {
            logger.debug(
                "Source {} has {} new items, need {} to trigger",
                source.id, newItemCount, config.triggerInterval
            )
            return null
        }

        // Calculate window with overlap
        val startIndex = max(0, lastIndex - config.overlapSize)
        val endIndex = min(source.size, startIndex + config.windowSize)

        logger.debug(
            "Analyzing source {} from {} to {} (size: {})",
            source.id, startIndex, endIndex, source.size
        )

        // Get items and format
        val items = source.getItems(startIndex, endIndex)
        val text = formatter.format(items)

        // Check if already processed (hash dedup)
        val contentHash = sha256(text)
        if (historyStore.isProcessed(contentHash)) {
            logger.debug("Content already processed (hash: {})", contentHash.take(8))
            return null
        }

        // Create chunk and process
        val chunk = Chunk.create(
            text = text,
            parentId = source.id,
            metadata = mapOf(
                "source_type" to "incremental",
                "source_id" to source.id,
                "start_index" to startIndex,
                "end_index" to endIndex,
            )
        )

        val result = pipeline.processChunk(chunk, context)

        // Record processing
        val now = Instant.now()
        historyStore.recordProcessed(
            ProcessedChunkRecord(
                contentHash = contentHash,
                sourceId = source.id,
                startIndex = startIndex,
                endIndex = endIndex,
                processedAt = now,
            )
        )

        logger.info(
            "Analyzed source {} [{}-{}]: {} propositions extracted",
            source.id, startIndex, endIndex, result.propositions.size
        )

        return result
    }

    /**
     * Force analysis regardless of trigger interval.
     * Still respects hash deduplication.
     */
    fun analyzeNow(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
    ): ChunkPropositionResult? {
        if (source.size == 0) {
            return null
        }

        val lastBookmark = historyStore.getLastBookmark(source.id)
        val lastIndex = lastBookmark?.endIndex ?: 0

        val startIndex = max(0, lastIndex - config.overlapSize)
        val endIndex = min(source.size, startIndex + config.windowSize)

        val items = source.getItems(startIndex, endIndex)
        val text = formatter.format(items)

        val contentHash = sha256(text)
        if (historyStore.isProcessed(contentHash)) {
            logger.debug("Content already processed (hash: {})", contentHash.take(8))
            return null
        }

        val chunk = Chunk.create(
            text = text,
            parentId = source.id,
            metadata = mapOf(
                "source_type" to "incremental",
                "source_id" to source.id,
                "start_index" to startIndex,
                "end_index" to endIndex,
            )
        )

        val result = pipeline.processChunk(chunk, context)

        historyStore.recordProcessed(
            ProcessedChunkRecord(
                contentHash = contentHash,
                sourceId = source.id,
                startIndex = startIndex,
                endIndex = endIndex,
                processedAt = Instant.now(),
            )
        )

        return result
    }

    companion object {
        private fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
