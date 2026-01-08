package com.embabel.dice.incremental

import java.time.Instant

/**
 * Tracks the history of processed chunks for incremental analysis.
 * Enables deduplication and resumption of processing.
 *
 * Implementations should persist this data to allow resumption across sessions.
 */
interface ChunkHistoryStore {

    /**
     * Get the last analysis bookmark for a source.
     * Returns null if the source has never been processed.
     *
     * @param sourceId The source identifier
     * @return The last bookmark, or null if never processed
     */
    fun getLastBookmark(sourceId: String): AnalysisBookmark?

    /**
     * Check if content with the given hash has already been processed.
     * Used for deduplication.
     *
     * @param contentHash SHA-256 hash of the content
     * @return true if already processed
     */
    fun isProcessed(contentHash: String): Boolean

    /**
     * Record that a chunk has been processed.
     *
     * @param record The processing record to store
     */
    fun recordProcessed(record: ProcessedChunkRecord)
}

/**
 * Bookmark tracking the last analysis position for a source.
 */
data class AnalysisBookmark(
    val sourceId: String,
    val endIndex: Int,
    val processedAt: Instant,
)

/**
 * Record of a processed chunk.
 */
data class ProcessedChunkRecord(
    val contentHash: String,
    val sourceId: String,
    val startIndex: Int,
    val endIndex: Int,
    val processedAt: Instant,
)

/**
 * Configuration for windowed processing.
 */
data class WindowConfig(
    /**
     * Maximum number of items to process in one window.
     */
    val windowSize: Int = 20,

    /**
     * Number of items to overlap between windows for context.
     */
    val overlapSize: Int = 2,

    /**
     * Minimum number of new items required before triggering analysis.
     */
    val triggerInterval: Int = 4,
)

/**
 * Simple in-memory implementation for testing.
 */
class InMemoryChunkHistoryStore : ChunkHistoryStore {

    private val bookmarks = mutableMapOf<String, AnalysisBookmark>()
    private val processedHashes = mutableSetOf<String>()

    override fun getLastBookmark(sourceId: String): AnalysisBookmark? =
        bookmarks[sourceId]

    override fun isProcessed(contentHash: String): Boolean =
        contentHash in processedHashes

    override fun recordProcessed(record: ProcessedChunkRecord) {
        processedHashes.add(record.contentHash)
        bookmarks[record.sourceId] = AnalysisBookmark(
            sourceId = record.sourceId,
            endIndex = record.endIndex,
            processedAt = record.processedAt,
        )
    }
}
