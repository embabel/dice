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

import org.jetbrains.annotations.ApiStatus

/**
 * One analysed window of an incremental source that is not yet recorded as processed.
 *
 * [AbstractIncrementalAnalyzer.analyzeWindow] returns it. Store [result], then call [markProcessed].
 * A window that is never marked is analysed again by the next call for the same source.
 *
 * Keep one window per source outstanding. The analyzer does not serialise callers. A window marked
 * after a later window for the same source moved the bookmark records nothing, because the later
 * window covered it.
 *
 * Inside a transaction, the record commits or rolls back with the stored result only when the
 * [ChunkHistoryStore] takes part in that transaction. With one that does not, such as
 * [InMemoryChunkHistoryStore], call [markProcessed] after the commit.
 *
 * From Java:
 *
 * ```java
 * AnalyzedWindow<ChunkPropositionResult> window = analyzer.analyzeWindow(source, context);
 * if (window != null) {
 *     window.getResult().persist(propositionRepository, entityRepository);
 *     window.markProcessed();
 * }
 * ```
 *
 * From Kotlin:
 *
 * ```kotlin
 * analyzer.analyzeWindow(source, context)?.let { window ->
 *     window.result.persist(propositionRepository, entityRepository)
 *     window.markProcessed()
 * }
 * ```
 *
 * EXPERIMENTAL. The API may change before 1.0.
 *
 * @param R The type of result the analysis produced
 * @property result What the analysis produced for the window
 * @param record Writes the window to the history store. Returns false when it wrote nothing because the bookmark moved.
 * @param afterRecord Runs once after a record that wrote.
 */
@ApiStatus.Experimental
class AnalyzedWindow<out R> internal constructor(
    val result: R,
    private val record: () -> Boolean,
    private val afterRecord: () -> Unit,
) {

    private val lock = Any()

    // Set once, under the lock and after the record, so a record that throws leaves the window unmarked.
    @Volatile
    private var marked = false

    /** True once [markProcessed] has run its record. */
    val isMarkedProcessed: Boolean
        get() = marked

    /**
     * Records the window as processed, so later calls for the source skip it.
     *
     * The first call writes to the history store, unless the source's bookmark moved since the
     * analysis. Later calls do nothing. When the history store throws, the exception propagates,
     * the window stays unmarked and the call can be repeated. The window counts as marked once the
     * history store holds it, also when the analyzer's `onProcessed` hook then throws.
     */
    fun markProcessed() {
        synchronized(lock) {
            if (marked) return
            val written = record()
            marked = true
            if (written) afterRecord()
        }
    }

    /** Whether the window is marked. The result is left out because it can carry user content. */
    override fun toString(): String = "AnalyzedWindow(markedProcessed=$marked)"
}
