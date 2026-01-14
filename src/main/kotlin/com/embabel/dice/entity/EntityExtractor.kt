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
package com.embabel.dice.entity

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Extracts entities from text chunks.
 *
 * This is a simpler interface than [com.embabel.dice.text2graph.SourceAnalyzer] when you only need
 * entity extraction without relationship suggestion.
 *
 * Built-in implementations:
 * - [LlmEntityExtractor]: Uses an LLM to identify entities based on a schema
 *
 * @see LlmEntityExtractor
 * @see com.embabel.dice.text2graph.SourceAnalyzer
 */
interface EntityExtractor {

    /**
     * Suggest entities from a chunk based on the provided schema.
     *
     * @param chunk The text chunk to analyze
     * @param context Analysis context including schema and configuration
     * @return Suggested entities found in the chunk
     */
    fun suggestEntities(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): SuggestedEntities
}

/**
 * LLM response structure for entity extraction.
 */
internal data class ExtractedEntities(
    val entities: List<ExtractedEntityInfo>,
)

/**
 * Entity information from LLM extraction (without chunkId).
 */
internal data class ExtractedEntityInfo(
    @field:JsonPropertyDescription("Labels/types for the entity")
    val labels: List<String>,
    @field:JsonPropertyDescription("The name of the entity")
    val name: String,
    @field:JsonPropertyDescription("Brief description or summary of the entity")
    val summary: String,
    @field:JsonPropertyDescription("UUID if identifying a specific existing entity")
    val id: String? = null,
    @field:JsonPropertyDescription("Additional properties as key-value pairs")
    val properties: Map<String, Any> = emptyMap(),
) {

    fun toSuggestedEntity(chunkId: String): SuggestedEntity {
        return SuggestedEntity(
            labels = labels,
            name = name,
            summary = summary,
            chunkId = chunkId,
            id = id,
            properties = properties,
        )
    }
}
