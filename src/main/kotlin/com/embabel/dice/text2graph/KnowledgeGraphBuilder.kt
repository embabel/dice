package com.embabel.dice.text2graph

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.t.SourceAnalysisConfig

/**
 * Updates a knowledge graph from chunks of text
 */
interface KnowledgeGraphBuilder {

    /**
     * Compute a knowledge delta with the provided chunks.
     * The delta will consist entirely of new entities and relationships
     * if the knowledge graph is empty.
     * @param chunks the source chunks to analyze
     * @param context the source analysis context. Contains schema and
     * directions for analysis
     * @return the computed knowledge graph delta, or null if no changes were detected
     */
    fun computeDelta(
        chunks: Iterable<Chunk>,
        context: SourceAnalysisConfig,
    ): KnowledgeGraphDelta?

}

