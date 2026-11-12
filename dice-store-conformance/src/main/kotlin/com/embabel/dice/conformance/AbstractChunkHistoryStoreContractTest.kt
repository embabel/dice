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
package com.embabel.dice.conformance

import com.embabel.agent.core.ContextId
import com.embabel.dice.incremental.AnalysisBookmark
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import org.jetbrains.annotations.ApiStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Cross-backend contract for [ChunkHistoryStore]. It checks the behaviour the interface documents:
 * which content hashes count as processed, which bookmark a source gets, how both are scoped by
 * context, and what clearing removes. Each subclass supplies an empty store and inherits the
 * whole suite.
 *
 * A store that keeps the interface default for [ChunkHistoryStore.clearByContext] fails cases 8 and
 * 10. A store that keeps the default for [ChunkHistoryStore.clearAll] fails case 9. The defaults
 * exist for older implementations, so a current store should override both.
 *
 * The suite does not check which bookmark is last when processing times go backwards. The interface
 * does not define that case.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
abstract class AbstractChunkHistoryStoreContractTest {

    /** A fresh, empty store for the test about to run. */
    protected abstract fun store(): ChunkHistoryStore

    private val ctxA = ContextId("chunk-ctx-a")
    private val ctxB = ContextId("chunk-ctx-b")
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun record(
        contextId: ContextId,
        sourceId: String,
        contentHash: String,
        endIndex: Int,
        processedAt: Instant,
    ): ProcessedChunkRecord = ProcessedChunkRecord(
        bookmarkKey = BookmarkKey(contextId, sourceId),
        hashKey = HashKey(contextId, contentHash),
        startIndex = 0,
        endIndex = endIndex,
        processedAt = processedAt,
    )

    @Test
    @DisplayName("a source that was never processed has no bookmark")
    open fun aSourceThatWasNeverProcessedHasNoBookmark() {
        val store = store()

        assertNull(store.getLastBookmark(BookmarkKey(ctxA, "source-1")))
    }

    @Test
    @DisplayName("content that was never recorded is not processed")
    open fun contentThatWasNeverRecordedIsNotProcessed() {
        val store = store()
        store.recordProcessed(record(ctxA, "source-1", "hash-1", 5, t0))

        assertFalse(store.isProcessed(HashKey(ctxA, "hash-2")))
    }

    @Test
    @DisplayName("recorded content is processed in its context")
    open fun recordedContentIsProcessedInItsContext() {
        val store = store()
        store.recordProcessed(record(ctxA, "source-1", "hash-1", 5, t0))

        assertTrue(store.isProcessed(HashKey(ctxA, "hash-1")))
    }

    @Test
    @DisplayName("the bookmark carries the source, the end index and the time of the latest record")
    open fun theBookmarkCarriesTheLatestRecord() {
        val store = store()
        val later = t0.plusSeconds(1)
        store.recordProcessed(record(ctxA, "source-1", "hash-1", 5, t0))
        store.recordProcessed(record(ctxA, "source-1", "hash-2", 9, later))

        assertEquals(
            AnalysisBookmark("source-1", 9, later),
            store.getLastBookmark(BookmarkKey(ctxA, "source-1")),
        )
    }

    @Test
    @DisplayName("a content hash is tracked per context")
    open fun aContentHashIsTrackedPerContext() {
        val store = store()
        store.recordProcessed(record(ctxA, "source-1", "hash-1", 5, t0))

        assertTrue(store.isProcessed(HashKey(ctxA, "hash-1")))
        assertFalse(store.isProcessed(HashKey(ctxB, "hash-1")))
    }

    @Test
    @DisplayName("a bookmark is tracked per context")
    open fun aBookmarkIsTrackedPerContext() {
        val store = store()
        store.recordProcessed(record(ctxA, "source-1", "hash-1", 5, t0))

        assertEquals(
            AnalysisBookmark("source-1", 5, t0),
            store.getLastBookmark(BookmarkKey(ctxA, "source-1")),
        )
        assertNull(store.getLastBookmark(BookmarkKey(ctxB, "source-1")))
    }

    @Test
    @DisplayName("a bookmark is tracked per source")
    open fun aBookmarkIsTrackedPerSource() {
        val store = store()
        store.recordProcessed(record(ctxA, "source-1", "hash-1", 5, t0))

        assertEquals(
            AnalysisBookmark("source-1", 5, t0),
            store.getLastBookmark(BookmarkKey(ctxA, "source-1")),
        )
        assertNull(store.getLastBookmark(BookmarkKey(ctxA, "source-2")))
    }

    @Test
    @DisplayName("clearByContext removes that context and leaves the others")
    open fun clearByContextRemovesThatContextOnly() {
        val store = store()
        store.recordProcessed(record(ctxA, "source-a", "hash-a", 5, t0))
        store.recordProcessed(record(ctxB, "source-b", "hash-b", 7, t0))

        store.clearByContext(ctxA)

        assertFalse(store.isProcessed(HashKey(ctxA, "hash-a")))
        assertNull(store.getLastBookmark(BookmarkKey(ctxA, "source-a")))
        assertTrue(store.isProcessed(HashKey(ctxB, "hash-b")))
        assertEquals(
            AnalysisBookmark("source-b", 7, t0),
            store.getLastBookmark(BookmarkKey(ctxB, "source-b")),
        )
    }

    @Test
    @DisplayName("clearAll removes every context")
    open fun clearAllRemovesEveryContext() {
        val store = store()
        store.recordProcessed(record(ctxA, "source-a", "hash-a", 5, t0))
        store.recordProcessed(record(ctxB, "source-b", "hash-b", 7, t0))

        store.clearAll()

        assertFalse(store.isProcessed(HashKey(ctxA, "hash-a")))
        assertNull(store.getLastBookmark(BookmarkKey(ctxA, "source-a")))
        assertFalse(store.isProcessed(HashKey(ctxB, "hash-b")))
        assertNull(store.getLastBookmark(BookmarkKey(ctxB, "source-b")))
    }

    @Test
    @DisplayName("content can be recorded again after its context is cleared")
    open fun contentCanBeRecordedAgainAfterItsContextIsCleared() {
        val store = store()
        store.recordProcessed(record(ctxA, "source-1", "hash-1", 5, t0))

        store.clearByContext(ctxA)
        assertFalse(store.isProcessed(HashKey(ctxA, "hash-1")))

        val later = t0.plusSeconds(1)
        store.recordProcessed(record(ctxA, "source-1", "hash-1", 6, later))

        assertTrue(store.isProcessed(HashKey(ctxA, "hash-1")))
        assertEquals(
            AnalysisBookmark("source-1", 6, later),
            store.getLastBookmark(BookmarkKey(ctxA, "source-1")),
        )
    }
}
