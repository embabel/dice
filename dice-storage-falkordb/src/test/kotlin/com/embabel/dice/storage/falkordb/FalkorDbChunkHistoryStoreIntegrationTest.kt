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
package com.embabel.dice.storage.falkordb

import com.embabel.agent.core.ContextId
import com.embabel.dice.incremental.AnalysisBookmark
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class FalkorDbChunkHistoryStoreIntegrationTest {
    @Test
    fun `bookmarks and idempotent hashes persist with exact time and context isolation`() {
        server.graph().use { first ->
            server.graph(first.graph).use { second ->
                val store = FalkorDbChunkHistoryStore(first.objects)
                val restored = FalkorDbChunkHistoryStore(second.objects)
                val a = ContextId("tenant|a")
                val b = ContextId("tenant")
                val at = Instant.parse("2026-01-02T03:04:05.123456789Z")
                val key = BookmarkKey(a, "source'\\\$id")
                val hash = HashKey(a, "hash|x")
                assertNull(store.getLastBookmark(key))
                assertFalse(store.isProcessed(hash))
                val record = ProcessedChunkRecord(key, hash, 0, 8, at)
                store.recordProcessed(record)
                store.recordProcessed(record)
                assertEquals(AnalysisBookmark(key.sourceId, 8, at), restored.getLastBookmark(key))
                assertTrue(restored.isProcessed(hash))
                assertFalse(restored.isProcessed(HashKey(b, hash.contentHash)))
                store.recordProcessed(ProcessedChunkRecord(BookmarkKey(b, "a|source"), HashKey(b, hash.contentHash), 0, 3, at))
                val sharedSource = BookmarkKey(a, "second-source")
                store.recordProcessed(ProcessedChunkRecord(sharedSource, hash, 0, 5, at.plusSeconds(1)))
                assertEquals(AnalysisBookmark(key.sourceId, 8, at), restored.getLastBookmark(key))
                assertEquals(5, restored.getLastBookmark(sharedSource)?.endIndex)
                store.recordProcessed(record.copy(endIndex = 12, processedAt = at.plusNanos(1)))
                assertEquals(12, restored.getLastBookmark(key)?.endIndex)
                store.clearByContext(a)
                assertNull(restored.getLastBookmark(key))
                assertFalse(restored.isProcessed(hash))
                assertTrue(restored.isProcessed(HashKey(b, hash.contentHash)))
                val propositions = FalkorDbPropositionRepository(first.objects, first.persistence)
                val sentinel = Proposition(id = "sentinel", contextId = b, text = "retain", mentions = emptyList(), confidence = 0.9)
                propositions.save(sentinel)
                assertEquals(1, propositions.clearAll())
                assertTrue(restored.isProcessed(HashKey(b, hash.contentHash)))
                propositions.save(sentinel)
                store.clearAll()
                assertEquals(sentinel, propositions.findById(sentinel.id))
                assertFalse(restored.isProcessed(HashKey(b, hash.contentHash)))
                assertNull(restored.getLastBookmark(BookmarkKey(b, "a|source")))
            }
        }
    }

    companion object {
        @Container
        @JvmField
        internal val server = FalkorDbTestServer()
    }
}
