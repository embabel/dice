package com.embabel.dice.incremental

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Handy base implementation of [IncrementalAnalyzer] providing:
 * - Tracking of what's been processed to avoid re-processing
 * - Windowing with overlap to maintain context
 * - Hash-based deduplication
 *
 * Subclasses implement [processChunk] to produce their specific result type.
 *
 * @param T The type of items in the source
 * @param R The type of result produced by analysis
 * @param historyStore Tracks processing history (caller provides implementation)
 * @param formatter Formats source items to text
 * @param config Window and trigger configuration
 */
abstract class AbstractIncrementalAnalyzer<T, R>(
    protected val historyStore: ChunkHistoryStore,
    protected val formatter: IncrementalSourceFormatter<T>,
    protected val config: WindowConfig = WindowConfig(),
) : IncrementalAnalyzer<T, R> {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Process a chunk and produce a result.
     *
     * @param chunk The chunk to process
     * @param context Analysis context
     * @return The processing result
     */
    protected abstract fun processChunk(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): R

    /**
     * Called after successful processing to allow subclasses to log or post-process.
     * Default implementation does nothing.
     */
    protected open fun onProcessed(
        source: IncrementalSource<T>,
        startIndex: Int,
        endIndex: Int,
        result: R,
    ) {
        // Default: no-op
    }

    override fun analyze(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
    ): R? {
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

        return processWindow(source, context, lastIndex)
    }

    override fun analyzeNow(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
    ): R? {
        if (source.size == 0) {
            return null
        }

        val lastBookmark = historyStore.getLastBookmark(source.id)
        val lastIndex = lastBookmark?.endIndex ?: 0

        return processWindow(source, context, lastIndex)
    }

    private fun processWindow(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
        lastIndex: Int,
    ): R? {
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
        val chunk = Chunk.Companion.create(
            text = text,
            parentId = source.id,
            metadata = mapOf(
                "source_type" to "incremental",
                "source_id" to source.id,
                "start_index" to startIndex,
                "end_index" to endIndex,
            )
        )

        val result = processChunk(chunk, context)

        // Record processing
        historyStore.recordProcessed(
            ProcessedChunkRecord(
                contentHash = contentHash,
                sourceId = source.id,
                startIndex = startIndex,
                endIndex = endIndex,
                processedAt = Instant.now(),
            )
        )

        onProcessed(source, startIndex, endIndex, result)

        return result
    }

    companion object {
        internal fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}