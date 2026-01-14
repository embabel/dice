package com.embabel.dice.text2graph

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.entity.EntityExtractor

/**
 * Adapter that wraps a [SourceAnalyzer] as an [EntityExtractor].
 *
 * Useful when you have an existing SourceAnalyzer but only need entity extraction.
 */
class SourceAnalyzerEntityExtractor(
    private val sourceAnalyzer: SourceAnalyzer,
) : EntityExtractor {

    override fun suggestEntities(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): SuggestedEntities = sourceAnalyzer.suggestEntities(chunk, context)
}
