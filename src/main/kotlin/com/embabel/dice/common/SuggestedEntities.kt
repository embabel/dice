package com.embabel.dice.common

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.common.core.Sourced
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.*

data class SuggestedEntity(
    val labels: List<String>,
    val name: String,
    val summary: String,
    @param:JsonPropertyDescription("Will be a UUID. Include only if provided")
    private val id: String? = null,
    @field:JsonPropertyDescription("Map from property name to value")
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
 */
data class SuggestedEntities(
    override val chunkIds: Set<String>,
    val suggestedEntities: List<SuggestedEntity>,
) : Sourced


