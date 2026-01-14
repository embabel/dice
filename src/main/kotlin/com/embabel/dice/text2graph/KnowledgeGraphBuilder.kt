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
package com.embabel.dice.text2graph

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext

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
        context: SourceAnalysisContext,
    ): KnowledgeGraphDelta?

}
