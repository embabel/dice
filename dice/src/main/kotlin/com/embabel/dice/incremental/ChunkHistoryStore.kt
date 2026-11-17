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
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The stores whose typed clear is handing over to the String clear on this thread. Each default
 * clear forwards to the other, so a store that overrides neither would loop without this. It is
 * kept per store so a decorator that clears its delegate inside its own clear still reaches it.
 */
private val storesForwardingClear: ThreadLocal<MutableSet<ChunkHistoryStore>> =
    ThreadLocal.withInitial { Collections.newSetFromMap(IdentityHashMap()) }

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
     *
     * The default calls [clearByContextValue], so a store written in Java, which overrides only
     * that method, is cleared by this call too. A store that overrides neither method clears
     * nothing, as before.
     */
    fun clearByContext(contextId: ContextId) {
        val forwarding = storesForwardingClear.get()
        if (!forwarding.add(this)) return
        try {
            clearByContextValue(contextId.value)
        } finally {
            forwarding.remove(this)
        }
    }

    /**
     * Removes all history for one context, named by its id as a string. The default calls
     * [clearByContext], so a store that overrides only that method is reached from Java too.
     *
     * Java cannot override [clearByContext], because it takes a ContextId, so a store written in
     * Java overrides this method instead. Calls to either method reach that override.
     *
     * @param contextIdValue The context to clear, as a plain string.
     * @throws IllegalArgumentException if contextIdValue is blank
     */
    fun clearByContextValue(contextIdValue: String) {
        if (this in storesForwardingClear.get()) return
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
