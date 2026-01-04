package com.embabel.dice.common

import com.embabel.agent.rag.model.NamedEntityData

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
     * All entities that need to be persisted (new + updated).
     */
    fun entitiesToPersist(): List<NamedEntityData> = newEntities() + updatedEntities()
}
