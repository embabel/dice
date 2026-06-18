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

/**
 * Statistics about entity extraction outcomes.
 */
data class EntityExtractionStats(
    /** Number of new entities (not matched to existing) */
    val newCount: Int,
    /** Number of entities matched to existing ones */
    val updatedCount: Int,
    /** Number of reference-only entities (matched but not updated, e.g., current user) */
    val referenceOnlyCount: Int = 0,
) {
    /** Total number of entities */
    val total: Int get() = newCount + updatedCount + referenceOnlyCount
}

/**
 * Result of entity extraction and resolution.
 * Provides access to entities that need to be persisted.
 */
interface EntityExtractionResult {

    /**
     * Entities that are new (not matched to any existing entity).
     * These should be saved to the entity repository.
     */
    fun newEntities(): List<NamedEntityData>

    /**
     * Entities that were matched to existing entities and may have updated information.
     * These should be updated in the entity repository.
     */
    fun updatedEntities(): List<NamedEntityData>

    /**
     * Entities that were matched to known entities that should not be updated.
     * These are typically entities managed by external services (e.g., the current user).
     * They are referenced in propositions but not persisted during extraction.
     */
    fun referenceOnlyEntities(): List<NamedEntityData> = emptyList()

    /**
     * All entities that need to be persisted (new + updated).
     * Does not include reference-only entities.
     */
    fun entitiesToPersist(): List<NamedEntityData> = newEntities() + updatedEntities()

    /** Statistics about entity extraction outcomes */
    val entityExtractionStats: EntityExtractionStats
        get() = EntityExtractionStats(
            newCount = newEntities().size,
            updatedCount = updatedEntities().size,
            referenceOnlyCount = referenceOnlyEntities().size,
        )
}
