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
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.common.support.Sha256ContentHasher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/*
 * Contract for AbstractIncrementalAnalyzer.analyzeThenRecord and the analyze behaviour it shares.
 *
 * R1  The source must have at least triggerInterval new items past the bookmark to be analysed.
 * R2  The window must not already be recorded (by content hash) to be analysed.
 * E1  When the window is ready and new, the result of processChunk is returned.
 * E2  The result is handed to the store before anything is recorded.
 * E3  If the store throws, nothing is recorded, onProcessed is not called, and the exception
 *     propagates.
 * E4  If the store returns, the bookmark and the hash are recorded, then onProcessed runs.
 * E5  When the window is not ready or already processed, null is returned and neither
 *     processChunk nor the store runs.
 * I1  analyze keeps its trigger rule and still records before it returns.
 * L1  After a successful call, a second call on the same unchanged source returns null.
 */
class AnalyzeThenRecordTest {

    private val contextId = ContextId("analyze-then-record")
    private val context = SourceAnalysisContext(
        schema = DataDictionary.fromClasses("analyze-then-record"),
        entityResolver = AlwaysCreateEntityResolver,
        contextId = contextId,
    )

    private class ListSource(
        override val id: String,
        val items: MutableList<String>,
    ) : IncrementalSource<String> {
        override val size: Int get() = items.size
        override fun getItems(start: Int, end: Int): List<String> = items.subList(start, end).toList()
    }

    /**
     * Records every call so the tests can check order and counts. The result is "result-N"
     * where N counts processChunk calls.
     */
    private class RecordingAnalyzer(
        store: ChunkHistoryStore,
        config: WindowConfig = WindowConfig(),
    ) : AbstractIncrementalAnalyzer<String, String>(
        historyStore = store,
        formatter = object : IncrementalSourceFormatter<String> {
            override fun format(items: List<String>): String = items.joinToString("\n")
        },
        config = config,
    ) {
        val chunks = mutableListOf<Chunk>()
        val events = mutableListOf<String>()

        override fun processChunk(chunk: Chunk, context: SourceAnalysisContext): String {
            chunks += chunk
            return "result-${chunks.size}"
        }

        override fun onProcessed(
            source: IncrementalSource<String>,
            startIndex: Int,
            endIndex: Int,
            result: String,
        ) {
            events += "onProcessed:$result"
        }

        fun run(
            source: IncrementalSource<String>,
            context: SourceAnalysisContext,
            store: (String) -> Unit,
        ): String? = analyzeThenRecord(source, context, store)
    }

    private fun hashOf(text: String): HashKey =
        HashKey(contextId, Sha256ContentHasher.hash(text))

    private val fourItems = listOf("a", "b", "c", "d")
    private val fourItemText = fourItems.joinToString("\n")

    // R1, E5: too few new items -> null, no processChunk, no store call.
    @Test
    fun `R1 E5 fewer new items than triggerInterval returns null and runs nothing`() {
        val store = InMemoryChunkHistoryStore()
        val analyzer = RecordingAnalyzer(store)
        val source = ListSource("src", mutableListOf("a", "b", "c"))
        var storeCalls = 0

        val result = analyzer.run(source, context) { storeCalls++ }

        assertNull(result)
        assertEquals(0, analyzer.chunks.size)
        assertEquals(0, storeCalls)
        assertNull(store.getLastBookmark(BookmarkKey(contextId, "src")))
    }

    // R1 boundary: exactly triggerInterval new items is enough.
    @Test
    fun `R1 exactly triggerInterval new items is enough to analyse`() {
        val analyzer = RecordingAnalyzer(InMemoryChunkHistoryStore())
        val source = ListSource("src", fourItems.toMutableList())

        val result = analyzer.run(source, context) { }

        assertNotNull(result)
        assertEquals(1, analyzer.chunks.size)
    }

    // E3: store throws -> exception propagates, nothing recorded, onProcessed not called.
    // The next call analyses the window again.
    @Test
    fun `E3 store throwing propagates the exception and records nothing`() {
        val store = InMemoryChunkHistoryStore()
        val analyzer = RecordingAnalyzer(store)
        val source = ListSource("src", fourItems.toMutableList())

        assertThrows(IllegalStateException::class.java) {
            analyzer.run(source, context) { throw IllegalStateException("store down") }
        }

        assertNull(store.getLastBookmark(BookmarkKey(contextId, "src")))
        assertEquals(false, store.isProcessed(hashOf(fourItemText)))
        assertEquals(emptyList<String>(), analyzer.events)
    }

    @Test
    fun `E3 a window whose store failed is analysed again on the next call`() {
        val store = InMemoryChunkHistoryStore()
        val analyzer = RecordingAnalyzer(store)
        val source = ListSource("src", fourItems.toMutableList())

        assertThrows(IllegalStateException::class.java) {
            analyzer.run(source, context) { throw IllegalStateException("store down") }
        }
        val retry = analyzer.run(source, context) { }

        assertEquals("result-2", retry)
        assertEquals(2, analyzer.chunks.size)
        assertEquals(4, store.getLastBookmark(BookmarkKey(contextId, "src"))?.endIndex)
    }

    // E2, E4: the store runs before recording, and recording happens before onProcessed.
    @Test
    fun `E2 E4 store sees no bookmark yet, then bookmark and hash are recorded before onProcessed`() {
        val store = InMemoryChunkHistoryStore()
        val analyzer = RecordingAnalyzer(store)
        val source = ListSource("src", fourItems.toMutableList())
        val bookmarkSeenByStore = mutableListOf<Any?>()

        val result = analyzer.run(source, context) { stored ->
            bookmarkSeenByStore += store.getLastBookmark(BookmarkKey(contextId, "src"))
            analyzer.events += "store:$stored"
        }

        assertEquals("result-1", result)
        assertEquals(listOf<Any?>(null), bookmarkSeenByStore)
        assertEquals(listOf("store:result-1", "onProcessed:result-1"), analyzer.events)
        assertEquals(4, store.getLastBookmark(BookmarkKey(contextId, "src"))?.endIndex)
        assertEquals(true, store.isProcessed(hashOf(fourItemText)))
    }

    // E1, L1: after success, a second call on the same source returns null and does not
    // run processChunk or the store again.
    @Test
    fun `L1 second call on an unchanged source returns null without running anything`() {
        val store = InMemoryChunkHistoryStore()
        val analyzer = RecordingAnalyzer(store)
        val source = ListSource("src", fourItems.toMutableList())
        analyzer.run(source, context) { }
        var storeCalls = 0

        val second = analyzer.run(source, context) { storeCalls++ }

        assertNull(second)
        assertEquals(1, analyzer.chunks.size)
        assertEquals(0, storeCalls)
    }

    // R2, E5: the bookmark says the window is new, but its hash was already processed, so
    // nothing runs. The bookmark is hidden here so only the hash check can stop the call.
    @Test
    fun `R2 E5 a window whose hash is already processed returns null even without a bookmark`() {
        val inner = InMemoryChunkHistoryStore()
        val store = object : ChunkHistoryStore by inner {
            override fun getLastBookmark(key: BookmarkKey): AnalysisBookmark? = null
        }
        val analyzer = RecordingAnalyzer(store)
        val source = ListSource("src", fourItems.toMutableList())
        analyzer.run(source, context) { }
        var storeCalls = 0

        val second = analyzer.run(source, context) { storeCalls++ }

        assertNull(second)
        assertEquals(1, analyzer.chunks.size)
        assertEquals(0, storeCalls)
    }

    // I1: analyze keeps its trigger rule and still records before it returns.
    @Test
    fun `I1 analyze returns null below triggerInterval and records nothing`() {
        val store = InMemoryChunkHistoryStore()
        val analyzer = RecordingAnalyzer(store)
        val source = ListSource("src", mutableListOf("a", "b", "c"))

        assertNull(analyzer.analyze(source, context))
        assertEquals(0, analyzer.chunks.size)
        assertNull(store.getLastBookmark(BookmarkKey(contextId, "src")))
    }

    @Test
    fun `I1 analyze records the window before it returns`() {
        val store = InMemoryChunkHistoryStore()
        val analyzer = RecordingAnalyzer(store)
        val source = ListSource("src", fourItems.toMutableList())

        val result = analyzer.analyze(source, context)

        assertEquals("result-1", result)
        assertEquals(4, store.getLastBookmark(BookmarkKey(contextId, "src"))?.endIndex)
        assertEquals(true, store.isProcessed(hashOf(fourItemText)))
        assertEquals(listOf("onProcessed:result-1"), analyzer.events)
    }
}
