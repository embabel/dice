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
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class FalkorDbChunkHistoryStoreIntegrationTest {
    @Test
    fun `bookmarks and idempotent hashes persist with exact time and context isolation`() {
        val graph = "chunks_${UUID.randomUUID().toString().replace("-", "")}"
        val first = server.provider(graph)
        val second = server.provider(graph)
        try {
            val store = FalkorDbChunkHistoryStore(first)
            val restored = FalkorDbChunkHistoryStore(second)
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
            val propositions = FalkorDbPropositionRepository(first)
            val sentinel = Proposition(id = "sentinel", contextId = b, text = "retain", mentions = emptyList(), confidence = 0.9)
            propositions.save(sentinel)
            assertEquals(1, propositions.clearAll())
            assertTrue(restored.isProcessed(HashKey(b, hash.contentHash)))
            propositions.save(sentinel)
            store.clearAll()
            assertEquals(sentinel, propositions.findById(sentinel.id))
            assertFalse(restored.isProcessed(HashKey(b, hash.contentHash)))
            assertNull(restored.getLastBookmark(BookmarkKey(b, "a|source")))
        } finally {
            first.end()
            second.end()
        }
    }

    @Test
    fun `ambient transactions are rejected before recording either half`() {
        val provider = server.provider("chunks_${UUID.randomUUID().toString().replace("-", "")}")
        try {
            val store = FalkorDbChunkHistoryStore(provider)
            val key = BookmarkKey(ContextId("scope"), "source")
            val hash = HashKey(key.contextId, "hash")
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                assertThrows<IllegalStateException> {
                    store.recordProcessed(ProcessedChunkRecord(key, hash, 0, 1, Instant.EPOCH))
                }
                assertThrows<IllegalStateException> { store.clearAll() }
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            assertFalse(store.isProcessed(hash))
            assertNull(store.getLastBookmark(key))
        } finally {
            provider.end()
        }
    }

    @Test
    fun `concurrent cold starts and hash recording keep one marker with independent source bookmarks`() {
        val graph = "chunks_race_${UUID.randomUUID().toString().replace("-", "")}"
        val providers = listOf(server.provider(graph), server.provider(graph))
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val context = ContextId("shared")
        try {
            val jobs = providers.mapIndexed { worker, provider -> pool.submit {
                check(start.await(10, TimeUnit.SECONDS))
                val store = FalkorDbChunkHistoryStore(provider)
                repeat(15) { n -> store.recordProcessed(ProcessedChunkRecord(
                    BookmarkKey(context, "source-$worker"), HashKey(context, "same-hash"), n, n + 1,
                    Instant.EPOCH.plusNanos(n.toLong()),
                )) }
            } }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            val store = FalkorDbChunkHistoryStore(providers.first())
            assertTrue(store.isProcessed(HashKey(context, "same-hash")))
            assertEquals(15, store.getLastBookmark(BookmarkKey(context, "source-0"))?.endIndex)
            assertEquals(15, store.getLastBookmark(BookmarkKey(context, "source-1"))?.endIndex)
            val access = FalkorDbAccess(providers.first(), null)
            assertEquals(1L, (access.query("MATCH (h:DiceChunkHash) RETURN {value:count(h)} AS result").single()["value"] as Number).toLong())
            assertEquals(2L, (access.query("MATCH (b:DiceBookmark) RETURN {value:count(b)} AS result").single()["value"] as Number).toLong())
        } finally {
            pool.shutdownNow()
            providers.forEach { it.end() }
        }
    }

    companion object {
        @Container
        @JvmField
        internal val server = FalkorDbTestServer()
    }
}
