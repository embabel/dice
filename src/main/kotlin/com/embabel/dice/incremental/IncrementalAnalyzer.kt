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

import com.embabel.dice.common.SourceAnalysisContext

/**
 * Analyzes incremental sources (conversations, listening history, etc.)
 * and produces results of type [R].
 *
 * @param T The type of items in the source
 * @param R The type of result produced by analysis
 */
interface IncrementalAnalyzer<T, R> {

    /**
     * Analyze the source if enough new content has accumulated.
     *
     * @param source The incremental source to analyze
     * @param context Analysis context including schema, entity resolver, etc.
     * @return Processing result if analysis was performed, null if not ready or already processed
     */
    fun analyze(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
    ): R?

    /**
     * Force analysis regardless of trigger interval.
     * Implementations may still skip if content was already processed.
     *
     * @param source The incremental source to analyze
     * @param context Analysis context including schema, entity resolver, etc.
     * @return Processing result if analysis was performed, null if already processed
     */
    fun analyzeNow(
        source: IncrementalSource<T>,
        context: SourceAnalysisContext,
    ): R?
}
