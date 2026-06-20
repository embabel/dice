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
 * Composite key for per-source analysis bookmarks within a [ContextId].
 */
data class BookmarkKey(
    val contextId: ContextId,
    val sourceId: String,
)

/**
 * Composite key for content-hash deduplication within a [ContextId].
 */
data class HashKey(
    val contextId: ContextId,
    val contentHash: String,
) {
    override fun toString(): String =
        "HashKey(contextId=$contextId, contentHash=${contentHash.take(8)}…)"
}

/**
 * Resume marker for incremental source analysis within a [ContextId].
 *
 * When [AbstractIncrementalAnalyzer] processes a growing source (conversation,
 * message stream, log tail), it cannot re-read from the beginning each time.
 * After each successful window it records an [AnalysisBookmark] so the next
 * invocation knows how far analysis has progressed for that [BookmarkKey].
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
 * Record of a processed chunk, keyed by [BookmarkKey] and [HashKey].
 */
data class ProcessedChunkRecord(
    val bookmarkKey: BookmarkKey,
    val hashKey: HashKey,
    val startIndex: Int,
    val endIndex: Int,
    val processedAt: Instant,
) {
    init {
        require(bookmarkKey.contextId == hashKey.contextId) {
            "bookmarkKey and hashKey must share the same contextId"
        }
    }

    val contextId: ContextId get() = bookmarkKey.contextId
    val sourceId: String get() = bookmarkKey.sourceId
    val contentHash: String get() = hashKey.contentHash

    override fun toString(): String =
        "ProcessedChunkRecord(bookmarkKey=$bookmarkKey, hashKey=$hashKey, " +
            "startIndex=$startIndex, endIndex=$endIndex, processedAt=$processedAt)"
}

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
