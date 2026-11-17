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
import java.util.concurrent.ConcurrentHashMap

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
    fun getLastBookmark(key: BookmarkKey): AnalysisBookmark?

    /**
     * Check if content with the given hash has already been processed in a context.
     */
    fun isProcessed(key: HashKey): Boolean

    /**
     * Record that a chunk has been processed.
     */
    fun recordProcessed(record: ProcessedChunkRecord)

    /**
     * Removes all history for one context. A store written in Kotlin overrides this one.
     * Default no-op for backward compatibility.
     */
    fun clearByContext(contextId: ContextId) {}

    /**
     * Removes all history for one context, named by its id as a string. The default calls
     * [clearByContext], so a store that overrides only that method is reached from Java too.
     *
     * Java cannot override [clearByContext], because it takes a ContextId, so a store written in
     * Java overrides this method instead. Such a store is reached through this method only, so code
     * that may be handed a Java store should call this one.
     *
     * @param contextIdValue The context to clear, as a plain string.
     * @throws IllegalArgumentException if contextIdValue is blank
     */
    fun clearByContextValue(contextIdValue: String) {
        clearByContext(ContextId(contextIdValue))
    }

    /**
     * Remove all history across every context. Default no-op for backward compatibility.
     */
    fun clearAll() {}
}

/**
 * Thread-safe in-memory implementation for testing.
 * Not intended for production use.
 */
class InMemoryChunkHistoryStore : ChunkHistoryStore {

    private val bookmarks = ConcurrentHashMap<BookmarkKey, AnalysisBookmark>()
    private val processedHashes = ConcurrentHashMap.newKeySet<HashKey>()

    override fun getLastBookmark(key: BookmarkKey): AnalysisBookmark? = bookmarks[key]

    override fun isProcessed(key: HashKey): Boolean = key in processedHashes

    override fun recordProcessed(record: ProcessedChunkRecord) {
        processedHashes.add(record.hashKey)
        bookmarks[record.bookmarkKey] = AnalysisBookmark(
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
}
