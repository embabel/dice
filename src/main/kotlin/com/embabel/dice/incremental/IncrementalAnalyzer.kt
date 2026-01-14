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
