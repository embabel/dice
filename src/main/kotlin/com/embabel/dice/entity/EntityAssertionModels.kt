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

/**
 * Request to assert entities and relationships into the knowledge graph.
 *
 * Entities are resolved against existing graph data using the full
 * escalation chain (exact match → heuristic → vector → LLM).
 * New entities are created; existing entities are updated with merged labels/properties.
 *
 * @param entities Entities to resolve and persist
 * @param relationships Relationships to create between resolved entities
 */
data class EntityAssertionRequest(
    val entities: List<EntityAssertion>,
    val relationships: List<RelationshipAssertion> = emptyList(),
)

/**
 * A single entity to assert into the knowledge graph.
 *
 * @param name The entity name, used for resolution matching
 * @param labels Type labels for the entity, e.g. ["Person", "Engineer"]
 * @param description Optional description/summary of the entity
 * @param properties Additional properties to store on the entity node
 */
data class EntityAssertion(
    val name: String,
    val labels: List<String> = emptyList(),
    val description: String? = null,
    val properties: Map<String, Any> = emptyMap(),
)

/**
 * A relationship to assert between two entities.
 *
 * Source and target are matched by name against entities in the same request.
 *
 * @param source Entity name (must match an entity in the request)
 * @param target Entity name (must match an entity in the request)
 * @param type Relationship type, e.g. "WORKS_AT"
 * @param description Optional description of the relationship
 * @param properties Additional properties to store on the relationship
 */
data class RelationshipAssertion(
    val source: String,
    val target: String,
    val type: String,
    val description: String? = null,
    val properties: Map<String, Any> = emptyMap(),
)

/**
 * Result of processing an [EntityAssertionRequest].
 *
 * @param resolutions Resolution outcome for each asserted entity
 * @param relationships Persistence outcome for each asserted relationship
 */
data class EntityAssertionResult(
    val resolutions: List<EntityResolutionResult>,
    val relationships: List<RelationshipResult>,
)

/**
 * Resolution outcome for a single entity.
 *
 * @param name The original entity name from the assertion
 * @param entityId The resolved entity ID (graph node ID)
 * @param resolution How the entity was resolved
 * @param labels Final merged label set on the entity
 */
data class EntityResolutionResult(
    val name: String,
    val entityId: String,
    val resolution: ResolutionOutcome,
    val labels: Set<String>,
)

/**
 * How an entity assertion was resolved.
 */
enum class ResolutionOutcome {
    /** A new entity was created in the graph */
    NEW,
    /** Matched an existing entity; labels/properties were merged */
    EXISTING,
    /** Matched a reference-only entity; not modified */
    REFERENCE_ONLY,
    /** Entity creation was vetoed by the data dictionary */
    VETOED,
}

/**
 * Persistence outcome for a single relationship.
 *
 * @param source Source entity name
 * @param target Target entity name
 * @param type Relationship type
 * @param persisted Whether the relationship was actually persisted (false if source or target was vetoed)
 */
data class RelationshipResult(
    val source: String,
    val target: String,
    val type: String,
    val persisted: Boolean,
)
