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
    private val id: String? = null,
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
    val suggestedEntities: List<SuggestedEntity>,
) : Sourced {

    override val chunkIds: Set<String>
        get() = suggestedEntities.map { it.chunkId }.toSet()
}


