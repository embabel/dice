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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class AnalyzedWindowTest {

    private val context = SourceAnalysisContext
        .withContextId("window-tenant")
        .withEntityResolver(AlwaysCreateEntityResolver)
        .withSchema(DataDictionary.fromClasses("window"))

    private val bookmark = BookmarkKey.of("window-tenant", "lines-1")

    /** A source over a fixed list of lines. */
    private class Lines(private val items: List<String>) : IncrementalSource<String> {
        override val id = "lines-1"
        override val size get() = items.size
        override fun getItems(start: Int, end: Int) = items.subList(start, end)
    }

    /**
     * An in-memory history store that keeps every record it was given.
     *
     * @param failFirstRecord When true, the first `recordProcessed` call throws.
     */
    private class CountingHistory(private val failFirstRecord: Boolean = false) : ChunkHistoryStore {
        private val delegate = InMemoryChunkHistoryStore()
        private val attempts = AtomicInteger()
        val records = mutableListOf<ProcessedChunkRecord>()
        override fun getLastBookmark(key: BookmarkKey) = delegate.getLastBookmark(key)
        override fun isProcessed(key: HashKey) = delegate.isProcessed(key)
        override fun recordProcessed(record: ProcessedChunkRecord) {
            if (attempts.incrementAndGet() == 1 && failFirstRecord) throw IllegalStateException("history store down")
            records += record
            delegate.recordProcessed(record)
        }
    }

    /**
     * An analyzer whose result is the window text in upper case. Window 10, no overlap, trigger 2.
     *
     * @param history The history store.
     * @param failTold When true, `onProcessed` throws after it noted the result.
     */
    private class LineAnalyzer(history: ChunkHistoryStore, private val failTold: Boolean = false) :
        AbstractIncrementalAnalyzer<String, String>(
            history,
            object : IncrementalSourceFormatter<String> {
                override fun format(items: List<String>) = items.joinToString("\n")
            },
            WindowConfig(windowSize = 10, overlapSize = 0, triggerInterval = 2),
        ) {
        val chunks = mutableListOf<String>()
        val told = mutableListOf<String>()
        override fun processChunk(chunk: Chunk, context: SourceAnalysisContext): String {
            chunks += chunk.text
            return chunk.text.uppercase()
        }
        override fun onProcessed(source: IncrementalSource<String>, startIndex: Int, endIndex: Int, result: String) {
            told += result
            if (failTold) throw IllegalStateException("hook failed")
        }
    }

    private val source = Lines(listOf("a", "b"))

    @Test
    fun `E1 analyze records the window before it returns and tells the subclass`() {
        val history = CountingHistory()
        val analyzer = LineAnalyzer(history)

        assertEquals("A\nB", analyzer.analyze(source, context))
        assertEquals(1, history.records.size)
        assertEquals(2, history.getLastBookmark(bookmark)?.endIndex)
        assertEquals(listOf("A\nB"), analyzer.told)
        assertNull(analyzer.analyze(source, context))
    }

    @Test
    fun `E2 analyzeWindow returns the result and writes nothing to the history store`() {
        val history = CountingHistory()
        val analyzer = LineAnalyzer(history)

        val window = analyzer.analyzeWindow(source, context)

        assertEquals("A\nB", window?.result)
        assertTrue(history.records.isEmpty(), "history records: ${history.records}")
        assertNull(history.getLastBookmark(bookmark))
        assertFalse(window!!.isMarkedProcessed)
        assertTrue(analyzer.told.isEmpty())
    }

    @Test
    fun `E3 a window that is not marked is analysed again by the next call`() {
        val analyzer = LineAnalyzer(CountingHistory())

        assertNotNull(analyzer.analyzeWindow(source, context))
        assertNotNull(analyzer.analyzeWindow(source, context))
        assertEquals(2, analyzer.chunks.size)
    }

    @Test
    fun `E4 markProcessed called twice records once and tells the subclass once`() {
        val history = CountingHistory()
        val analyzer = LineAnalyzer(history)
        val window = analyzer.analyzeWindow(source, context)!!

        window.markProcessed()
        window.markProcessed()

        assertEquals(1, history.records.size)
        assertEquals(1, analyzer.told.size)
        assertTrue(window.isMarkedProcessed)
    }

    @Test
    fun `E5 a marked window is skipped by the next call`() {
        val analyzer = LineAnalyzer(CountingHistory())

        analyzer.analyzeWindow(source, context)!!.markProcessed()

        assertNull(analyzer.analyzeWindow(source, context))
        assertEquals(1, analyzer.chunks.size)
    }

    @Test
    fun `E6 analyzeWindow returns null below the trigger interval and analyzeWindowNow analyses`() {
        val analyzer = LineAnalyzer(CountingHistory())
        val short = Lines(listOf("a"))

        assertNull(analyzer.analyzeWindow(short, context))
        assertEquals("A", analyzer.analyzeWindowNow(short, context)?.result)
    }

    @Test
    fun `E7 a markProcessed whose history store throws leaves the window unmarked and can be repeated`() {
        val history = CountingHistory(failFirstRecord = true)
        val window = LineAnalyzer(history).analyzeWindow(source, context)!!

        assertThrows(IllegalStateException::class.java) { window.markProcessed() }
        assertFalse(window.isMarkedProcessed)

        window.markProcessed()

        assertEquals(1, history.records.size)
        assertTrue(window.isMarkedProcessed)
    }

    @Test
    fun `E8 a window marked after another window moved the bookmark records nothing`() {
        val history = CountingHistory()
        val analyzer = LineAnalyzer(history)
        val first = analyzer.analyzeWindow(source, context)!!
        val second = analyzer.analyzeWindow(source, context)!!

        first.markProcessed()
        second.markProcessed()

        assertEquals(1, history.records.size)
        assertEquals(2, history.getLastBookmark(bookmark)?.endIndex)
        assertTrue(second.isMarkedProcessed)
        assertEquals(1, analyzer.told.size)
    }

    @Test
    fun `E9 an onProcessed that throws leaves the window marked`() {
        val history = CountingHistory()
        val window = LineAnalyzer(history, failTold = true).analyzeWindow(source, context)!!

        assertThrows(IllegalStateException::class.java) { window.markProcessed() }
        assertTrue(window.isMarkedProcessed)
        assertEquals(1, history.records.size)

        window.markProcessed()

        assertEquals(1, history.records.size)
    }
}
