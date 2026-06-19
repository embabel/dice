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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryChunkHistoryStoreTest {

    private val tenantA = ContextId("tenant-a")
    private val tenantB = ContextId("tenant-b")
    private val now = Instant.parse("2026-06-18T10:00:00Z")

    @Test
    fun `deduplication is scoped to context`() {
        val store = InMemoryChunkHistoryStore()
        val hash = "abc123"

        store.recordProcessed(record(contextId = tenantA, sourceId = "doc-1", hash = hash, endIndex = 5))
        assertTrue(store.isProcessed(tenantA, hash))
        assertFalse(store.isProcessed(tenantB, hash))
    }

    @Test
    fun `bookmarks are scoped to context`() {
        val store = InMemoryChunkHistoryStore()

        store.recordProcessed(record(contextId = tenantA, sourceId = "thread-1", hash = "h1", endIndex = 3))
        store.recordProcessed(record(contextId = tenantB, sourceId = "thread-1", hash = "h2", endIndex = 9))

        assertEquals(3, store.getLastBookmark(tenantA, "thread-1")?.endIndex)
        assertEquals(9, store.getLastBookmark(tenantB, "thread-1")?.endIndex)
    }

    @Test
    fun `clearByContext removes only that context`() {
        val store = InMemoryChunkHistoryStore()
        store.recordProcessed(record(contextId = tenantA, sourceId = "doc-1", hash = "h1", endIndex = 2))
        store.recordProcessed(record(contextId = tenantB, sourceId = "doc-1", hash = "h2", endIndex = 4))

        store.clearByContext(tenantA)

        assertNull(store.getLastBookmark(tenantA, "doc-1"))
        assertFalse(store.isProcessed(tenantA, "h1"))
        assertEquals(4, store.getLastBookmark(tenantB, "doc-1")?.endIndex)
        assertTrue(store.isProcessed(tenantB, "h2"))
    }

    @Test
    fun `clearAll removes every context`() {
        val store = InMemoryChunkHistoryStore()
        store.recordProcessed(record(contextId = tenantA, sourceId = "doc-1", hash = "h1", endIndex = 1))
        store.recordProcessed(record(contextId = tenantB, sourceId = "doc-2", hash = "h2", endIndex = 2))

        store.clearAll()

        assertNull(store.getLastBookmark(tenantA, "doc-1"))
        assertNull(store.getLastBookmark(tenantB, "doc-2"))
        assertFalse(store.isProcessed(tenantA, "h1"))
        assertFalse(store.isProcessed(tenantB, "h2"))
    }

    @Test
    fun `content can be reprocessed after clearByContext`() {
        val store = InMemoryChunkHistoryStore()
        val hash = "same-content"

        store.recordProcessed(record(contextId = tenantA, sourceId = "doc-1", hash = hash, endIndex = 1))
        assertTrue(store.isProcessed(tenantA, hash))

        store.clearByContext(tenantA)

        assertFalse(store.isProcessed(tenantA, hash))
    }

    private fun record(
        contextId: ContextId,
        sourceId: String,
        hash: String,
        endIndex: Int,
    ) = ProcessedChunkRecord(
        contextId = contextId,
        contentHash = hash,
        sourceId = sourceId,
        startIndex = 0,
        endIndex = endIndex,
        processedAt = now,
    )
}
