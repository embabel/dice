package com.embabel.dice.text2graph

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.t.SourceAnalysisConfig

/**
 * Analyze text
 * Process each chunk in turn.
 * Not responsible for disambiguation or merging,
 * which is handled by a later pipeline stage.
 */
interface SourceAnalyzer {

    /**
     * Identify entities in a chunk based on the provided schema.
     */
    fun suggestEntities(
        chunk: Chunk,
        context: SourceAnalysisConfig,
    ): SuggestedEntities

    /**
     * Suggest relationships between the given entities based on the provided schema.
     */
    fun suggestRelationships(
        chunk: Chunk,
        suggestedEntitiesResolution: Resolutions<SuggestedEntityResolution>,
        context: SourceAnalysisConfig,
    ): SuggestedRelationships
}