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
package com.embabel.dice.common

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.common.core.Sourced
import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.*

/**
 * Entity suggested following analysis of text.
 * @param labels the labels (types) for the entity
 * @param name the name of the entity
 * @param summary a brief summary or description of the entity
 * @param chunkId the ID of the chunk from which this entity was suggested
 * @param id optional ID if the LLM can identify a specific existing entity, meaning it doesn't require resolution
 * @param properties additional properties for the entity
 */
data class SuggestedEntity(
    val labels: List<String>,
    val name: String,
    val summary: String,
    val chunkId: String,
    val id: String? = null,
    val properties: Map<String, Any> = emptyMap(),
) {
    @JsonIgnore
    val suggestedEntity: NamedEntityData = SimpleNamedEntityData(
        id = id ?: UUID.randomUUID().toString(),
        name = name,
        description = summary,
        labels = labels.map { it.substringAfterLast('.') }.toSet(),
        properties = properties,
    )
}

/**
 * Entities suggested by the LLM based on a single input.
 * These entities may duplicate existing entities in the knowledge graph
 * @param sourceText optional text of the source (e.g., conversation) for context during resolution
 */
data class SuggestedEntities(
    val suggestedEntities: List<SuggestedEntity>,
    val sourceText: String? = null,
) : Sourced {

    override val chunkIds: Set<String>
        get() = suggestedEntities.map { it.chunkId }.toSet()
}
