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

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.ContentHasher
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.support.Sha256ContentHasher
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Handy base implementation of [IncrementalAnalyzer] providing:
 * - Tracking of what's been processed to avoid re-processing
 * - Windowing with overlap to maintain context
 * - Hash-based deduplication
 *
 * Subclasses implement [processChunk] to produce their specific result type.
 *
 * @param T The type of items in the source
 * @param R The type of result produced by analysis
 * @param historyStore Tracks processing history (caller provides implementation)
 * @param formatter Formats source items to text
 * @param config Window and trigger configuration
 */
abstract class AbstractIncrementalAnalyzer<T, R>(
    protected val historyStore: ChunkHistoryStore,
    protected val formatter: IncrementalSourceFormatter<T>,
    protected val config: WindowConfig = WindowConfig(),
    protected val contentHasher: ContentHasher = Sha256ContentHasher,
) : IncrementalAnalyzer<T, R> {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Process a chunk and produce a result.
     *
     * @param chunk The chunk to process
     * @param context Analysis context
     * @return The processing result
     */
    protected abstract fun processChunk(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): R

    /**
     * Called after successful processing to allow subclasses to log or post-process.
     * Default implementation does nothing.
     */
    protected open fun onProcessed(
        source: IncrementalSource<T>,
        startIndex: Int,
        endIndex: Int,
        result: R,
    ) {
        // Default: no-op
    }

    override fun analyze(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
    ): R? {
        val lastIndex = lastIndexIfTriggered(source, context) ?: return null
        val window = prepareWindow(source, context, lastIndex) ?: return null

        val result = processChunk(window.chunk, context)
        recordWindow(source, context, window, result)
        return result
    }

    override fun analyzeNow(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
    ): R? {
        if (source.size == 0) {
            return null
        }

        val lastBookmark = historyStore.getLastBookmark(BookmarkKey(context.contextId, source.id))
        val lastIndex = lastBookmark?.endIndex ?: 0
        val window = prepareWindow(source, context, lastIndex) ?: return null

        val result = processChunk(window.chunk, context)
        recordWindow(source, context, window, result)
        return result
    }

    /**
     * Analyses the next window like [analyze], hands the result to [store], and records the window
     * only once [store] returns. If [store] throws, nothing is recorded and the exception goes up
     * to the caller, so the next call analyses again from the same starting point. That window may
     * end later if the source has grown since. A store or window record that throws an exception
     * is logged at `error` with the source, its context and the window's item range, because a
     * window that always fails is analysed again on every call and the caller's exception need not
     * say which source it was.
     *
     * Only Kotlin code in this module can call it. The method is synthetic in bytecode, so Java
     * source cannot see it.
     *
     * @param source The source to read the next window from
     * @param context Analysis context
     * @param store Saves the result somewhere durable before the window is marked as done
     * @return The result, or null when the window was not ready or was already processed.
     */
    @JvmSynthetic
    internal fun analyzeThenRecord(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
        store: (R) -> Unit,
    ): R? {
        val lastIndex = lastIndexIfTriggered(source, context) ?: return null
        val window = prepareWindow(source, context, lastIndex) ?: return null

        val result = processChunk(window.chunk, context)
        try {
            store(result)
        } catch (e: Exception) {
            logUnrecorded(
                source, context, window,
                "Could not store items [{}, {}) of source {} in context {}, so this call did not record the " +
                    "window as processed",
                e,
            )
            throw e
        }
        recordWindow(source, context, window, result)
        return result
    }

    /**
     * Returns the bookmark position to continue from, or null when there are not yet enough new
     * items to be worth analysing.
     */
    private fun lastIndexIfTriggered(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
    ): Int? {
        val lastBookmark = historyStore.getLastBookmark(BookmarkKey(context.contextId, source.id))
        val lastIndex = lastBookmark?.endIndex ?: 0

        val newItemCount = source.size - lastIndex
        if (newItemCount < config.triggerInterval) {
            logger.debug(
                "Source {} has {} new items, need {} to trigger",
                source.id, newItemCount, config.triggerInterval
            )
            return null
        }
        return lastIndex
    }

    /**
     * A window that is ready to hand to [processChunk]: where it sits in the source, the hash of
     * its text, and the chunk itself.
     */
    private class PreparedWindow(
        val startIndex: Int,
        val endIndex: Int,
        val contentHash: String,
        val chunk: Chunk,
    )

    /**
     * Works out the window that starts [WindowConfig.overlapSize] items before [lastIndex], formats
     * it, and builds its chunk. Returns null when the same text was already processed.
     */
    private fun prepareWindow(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
        lastIndex: Int,
    ): PreparedWindow? {
        // Calculate window with overlap
        val startIndex = max(0, lastIndex - config.overlapSize)
        val endIndex = min(source.size, startIndex + config.windowSize)

        logger.debug(
            "Analyzing source {} from {} to {} (size: {})",
            source.id, startIndex, endIndex, source.size
        )

        // Get items and format
        val items = source.getItems(startIndex, endIndex)
        val text = formatter.format(items)

        // Check if already processed (hash dedup)
        val contentHash = contentHasher.hash(text)
        if (historyStore.isProcessed(HashKey(context.contextId, contentHash))) {
            logger.debug("Content already processed (hash: {})", contentHash.take(8))
            return null
        }

        val chunk = Chunk.Companion.create(
            text = text,
            parentId = source.id,
            metadata = mapOf(
                "source_type" to "incremental",
                "source_id" to source.id,
                "start_index" to startIndex,
                "end_index" to endIndex,
            )
        )
        return PreparedWindow(startIndex, endIndex, contentHash, chunk)
    }

    /**
     * Marks [window] as done in the history store and tells [onProcessed] about it.
     */
    private fun recordWindow(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
        window: PreparedWindow,
        result: R,
    ) {
        try {
            historyStore.recordProcessed(
                ProcessedChunkRecord(
                    bookmarkKey = BookmarkKey(context.contextId, source.id),
                    hashKey = HashKey(context.contextId, window.contentHash),
                    startIndex = window.startIndex,
                    endIndex = window.endIndex,
                    processedAt = Instant.now(),
                )
            )
        } catch (e: Exception) {
            logUnrecorded(
                source, context, window,
                "Could not record items [{}, {}) of source {} in context {} as processed",
                e,
            )
            throw e
        }

        onProcessed(source, window.startIndex, window.endIndex, result)
    }

    /**
     * Logs [message] at `error` with the window's item range, the source and the context, and with
     * [failure] attached.
     *
     * The exception itself goes to the caller untouched and may not say which source it came from.
     * This line does, so a host can spot a source whose window fails on every call and choose to
     * stop asking for that source.
     */
    private fun logUnrecorded(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
        window: PreparedWindow,
        message: String,
        failure: Exception,
    ) {
        logger.error(message, window.startIndex, window.endIndex, source.id, context.contextId.value, failure)
    }

}
