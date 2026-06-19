/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice.incremental

import com.embabel.agent.core.ContextId
import java.time.Instant

/**
 * Tracks the history of processed chunks for incremental analysis.
 * Enables deduplication and resumption of processing.
 *
 * Implementations should persist this data to allow resumption across sessions.
 * Bookmarks and content-hash deduplication are scoped by [ContextId] so that
 * isolated sessions (multi-tenant runs, simulations, tests) do not leak state.
 */
interface ChunkHistoryStore {

    /**
     * Get the last [AnalysisBookmark] for a source within a context.
     *
     * Bookmarks drive incremental resumption in [AbstractIncrementalAnalyzer]:
     * the analyzer reads [AnalysisBookmark.endIndex] to decide where the next
     * window starts and whether enough new items exist to trigger processing.
     * Returns null if the source has never been processed in that context.
     */
    fun getLastBookmark(contextId: ContextId, sourceId: String): AnalysisBookmark?

    /**
     * Check if content with the given hash has already been processed in a context.
     */
    fun isProcessed(contextId: ContextId, contentHash: String): Boolean

    /**
     * Record that a chunk has been processed.
     */
    fun recordProcessed(record: ProcessedChunkRecord)

    /**
     * Remove all history for the given context. Default no-op for backward compatibility.
     */
    fun clearByContext(contextId: ContextId) {}

    /**
     * Remove all history across every context. Default no-op for backward compatibility.
     */
    fun clearAll() {}
}

/**
 * Resume marker for incremental source analysis within a [ContextId].
 *
 * When [AbstractIncrementalAnalyzer] processes a growing source (conversation,
 * message stream, log tail), it cannot re-read from the beginning each time.
 * After each successful window it records an [AnalysisBookmark] so the next
 * invocation knows how far analysis has progressed for that `(contextId, sourceId)`.
 *
 * [endIndex] is the exclusive upper bound of items already incorporated into a
 * processed window. The analyzer uses it to:
 * - compute how many new items have arrived since the last run ([WindowConfig.triggerInterval])
 * - start the next window with optional overlap ([WindowConfig.overlapSize]) for LLM context
 *
 * Bookmarks complement content-hash deduplication ([ChunkHistoryStore.isProcessed]):
 * the hash prevents re-processing identical *text*, while the bookmark prevents
 * re-scanning from index zero when new items append to the same source.
 *
 * Cleared by [ChunkHistoryStore.clearByContext] when a session or tenant ends.
 *
 * @property sourceId Identifier of the incremental source (e.g. conversation id)
 * @property endIndex Exclusive index of the last analyzed item in the source sequence
 * @property processedAt When this bookmark was last updated
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
    val contextId: ContextId,
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

    private val bookmarks = mutableMapOf<BookmarkKey, AnalysisBookmark>()
    private val processedHashes = mutableSetOf<HashKey>()

    override fun getLastBookmark(contextId: ContextId, sourceId: String): AnalysisBookmark? =
        bookmarks[BookmarkKey(contextId, sourceId)]

    override fun isProcessed(contextId: ContextId, contentHash: String): Boolean =
        HashKey(contextId, contentHash) in processedHashes

    override fun recordProcessed(record: ProcessedChunkRecord) {
        processedHashes.add(HashKey(record.contextId, record.contentHash))
        bookmarks[BookmarkKey(record.contextId, record.sourceId)] = AnalysisBookmark(
            sourceId = record.sourceId,
            endIndex = record.endIndex,
            processedAt = record.processedAt,
        )
    }

    override fun clearByContext(contextId: ContextId) {
        bookmarks.keys.removeIf { it.contextId == contextId }
        processedHashes.removeIf { it.contextId == contextId }
    }

    override fun clearAll() {
        bookmarks.clear()
        processedHashes.clear()
    }

    private data class BookmarkKey(val contextId: ContextId, val sourceId: String)

    private data class HashKey(val contextId: ContextId, val contentHash: String)
}
