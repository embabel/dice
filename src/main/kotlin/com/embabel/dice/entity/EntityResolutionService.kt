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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.common.*
import org.slf4j.LoggerFactory

/**
 * Standalone service for resolving, creating, and relating entities in the knowledge graph.
 *
 * Exposes the full entity resolution escalation chain as a first-class operation,
 * independent of proposition extraction. Entities are resolved using the same
 * [EntityResolver] infrastructure (exact match → heuristic → vector → LLM),
 * then persisted via [NamedEntityDataRepository].
 *
 * Unlike the proposition pipeline, all asserted entities are persisted — there is
 * no "referenced entity IDs" gate.
 *
 * @param entityResolver Resolves suggested entities against existing graph data
 * @param repository Persists entities and relationships to the graph
 * @param schema Data dictionary for label validation and creation permission checks
 */
class EntityResolutionService(
    private val entityResolver: EntityResolver,
    private val repository: NamedEntityDataRepository,
    private val schema: DataDictionary,
) {

    private val logger = LoggerFactory.getLogger(EntityResolutionService::class.java)

    /**
     * Resolve, persist, and relate a set of asserted entities.
     *
     * 1. Each [EntityAssertion] is converted to a [SuggestedEntity] and resolved
     * 2. New entities are saved; existing entities are updated with merged labels/properties
     * 3. Relationships are created between resolved entities (skipping vetoed endpoints)
     *
     * @param request The entities and relationships to assert
     * @return Resolution and relationship outcomes
     */
    fun resolve(request: EntityAssertionRequest): EntityAssertionResult {
        logger.info("Resolving {} entities with {} relationships",
            request.entities.size, request.relationships.size)

        // 1. Convert EntityAssertions → SuggestedEntities
        val suggestedEntities = SuggestedEntities(
            suggestedEntities = request.entities.map { assertion ->
                SuggestedEntity(
                    labels = assertion.labels,
                    name = assertion.name,
                    summary = assertion.description ?: "",
                    chunkId = ASSERTION_CHUNK_ID,
                    properties = assertion.properties,
                )
            },
        )

        // 2. Resolve via full escalation chain
        val resolutions = entityResolver.resolve(suggestedEntities, schema)

        // 3. Persist and build name → result map
        val nameToResult = mutableMapOf<String, EntityResolutionResult>()
        for (resolution in resolutions.resolutions) {
            val result = persistResolution(resolution)
            nameToResult[result.name] = result
        }

        // 4. Process relationships
        val relationshipResults = request.relationships.map { rel ->
            persistRelationship(rel, nameToResult)
        }

        logger.info("Entity assertion complete: {} resolutions, {} relationships",
            nameToResult.size, relationshipResults.count { it.persisted })

        return EntityAssertionResult(
            resolutions = nameToResult.values.toList(),
            relationships = relationshipResults,
        )
    }

    private fun persistResolution(resolution: SuggestedEntityResolution): EntityResolutionResult {
        return when (resolution) {
            is NewEntity -> {
                repository.save(resolution.recommended)
                EntityResolutionResult(
                    name = resolution.suggested.name,
                    entityId = resolution.recommended.id,
                    resolution = ResolutionOutcome.NEW,
                    labels = resolution.recommended.labels(),
                )
            }
            is ExistingEntity -> {
                repository.update(resolution.recommended)
                EntityResolutionResult(
                    name = resolution.suggested.name,
                    entityId = resolution.recommended.id,
                    resolution = ResolutionOutcome.EXISTING,
                    labels = resolution.recommended.labels(),
                )
            }
            is ReferenceOnlyEntity -> {
                EntityResolutionResult(
                    name = resolution.suggested.name,
                    entityId = resolution.existing.id,
                    resolution = ResolutionOutcome.REFERENCE_ONLY,
                    labels = resolution.existing.labels(),
                )
            }
            is VetoedEntity -> {
                EntityResolutionResult(
                    name = resolution.suggested.name,
                    entityId = "",
                    resolution = ResolutionOutcome.VETOED,
                    labels = emptySet(),
                )
            }
        }
    }

    private fun persistRelationship(
        rel: RelationshipAssertion,
        nameToResult: Map<String, EntityResolutionResult>,
    ): RelationshipResult {
        val sourceResult = nameToResult[rel.source]
        val targetResult = nameToResult[rel.target]

        if (sourceResult == null || targetResult == null ||
            sourceResult.resolution == ResolutionOutcome.VETOED ||
            targetResult.resolution == ResolutionOutcome.VETOED
        ) {
            return RelationshipResult(
                source = rel.source,
                target = rel.target,
                type = rel.type,
                persisted = false,
            )
        }

        val properties = buildMap {
            rel.description?.let { put("description", it) }
            putAll(rel.properties)
        }

        val source = RetrievableIdentifier(
            id = sourceResult.entityId,
            type = sourceResult.labels.firstOrNull() ?: "Entity",
        )
        val target = RetrievableIdentifier(
            id = targetResult.entityId,
            type = targetResult.labels.firstOrNull() ?: "Entity",
        )

        repository.mergeRelationship(source, target, RelationshipData(
            name = rel.type,
            properties = properties,
        ))

        return RelationshipResult(
            source = rel.source,
            target = rel.target,
            type = rel.type,
            persisted = true,
        )
    }

    companion object {
        /** Synthetic chunk ID used for directly asserted entities (no source chunk). */
        const val ASSERTION_CHUNK_ID = "__assertion__"
    }
}
