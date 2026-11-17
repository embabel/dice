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
     * Removes all history for one context. The default removes nothing.
     *
     * A store written in Kotlin overrides this method. A store written in Java cannot, because the
     * method takes a `ContextId`, so it extends [AbstractChunkHistoryStore].
     *
     * @param contextId The context to clear.
     */
    fun clearByContext(contextId: ContextId) {}

    /**
     * Removes all history for one context, named by its id as a string. Java calls this method.
     *
     * The default calls [clearByContext], so it reaches every store that overrides that method.
     *
     * @param contextIdValue The context to clear, as a plain string.
     * @throws IllegalArgumentException from the default, if contextIdValue is blank
     */
    fun clearByContextValue(contextIdValue: String) = clearByContext(ContextId(contextIdValue))

    /**
     * Remove all history across every context. Default no-op for backward compatibility.
     */
    fun clearAll() {}
}

/**
 * A base class for a chunk history store that has one clear to write.
 *
 * A subclass implements [clearByContextValue], and [clearByContext] calls it, so code that clears a
 * context through either method reaches the subclass. A store written in Java extends this class,
 * because Java cannot override `clearByContext(ContextId)`.
 *
 * ```java
 * public class TableChunkHistoryStore extends AbstractChunkHistoryStore {
 *
 *     @Override
 *     public void clearByContextValue(String contextIdValue) {
 *         // drop every chunk record and bookmark held for this context
 *     }
 *
 *     // getLastBookmark, isProcessed and recordProcessed go here.
 * }
 * ```
 *
 * Spring applies a class-level `@Transactional` only to the methods a class declares itself.
 * [clearByContext] is declared here and calls [clearByContextValue] on the store directly, so that
 * call does not pass through a Spring proxy. A store that needs a transaction for its clear opens
 * one inside [clearByContextValue], for example with `TransactionTemplate`.
 */
abstract class AbstractChunkHistoryStore : ChunkHistoryStore {

    /**
     * Removes all history for one context. Both clear methods end here.
     *
     * @param contextIdValue The context to clear, as a plain string. It is never blank when the
     *   call came through [clearByContext].
     */
    abstract override fun clearByContextValue(contextIdValue: String)

    /**
     * Removes all history for one context by calling [clearByContextValue] with the context's id.
     *
     * @param contextId The context to clear.
     */
    override fun clearByContext(contextId: ContextId) = clearByContextValue(contextId.value)
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
