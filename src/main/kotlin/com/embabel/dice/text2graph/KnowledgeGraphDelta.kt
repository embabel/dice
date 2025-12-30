package com.embabel.dice.text2graph

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.core.Sourced
import com.embabel.common.core.types.HasInfoString


/**
 * Update we'll apply to a knowledge graph in a persistent store.
 * Identifies the chunks that it is based on.
 */
data class KnowledgeGraphDelta(
    override val chunkIds: Set<String>,
    val entityMerges: Merges<SuggestedEntityResolution, NamedEntityData>,
    val relationshipMerges: Merges<SuggestedRelationshipResolution, RelationshipInstance>,
) : HasInfoString, Sourced {

    fun newEntities(): List<NamedEntityData> {
        return entityMerges.merges
            .filter { it.resolution is NewEntity }
            .mapNotNull { it.convergenceTarget }
    }

    fun mergedEntities(): List<EntityMerge> {
        return entityMerges.merges
            .filter { it.resolution is ExistingEntity }
            .filter { it.convergenceTarget != null }
    }

    fun newOrModifiedEntities(): List<NamedEntityData> {
        // Deduplicate by ID since the same entity can appear multiple times:
        // - Once as NewEntity (first seen in chunk 1)
        // - Once or more as ExistingEntity (seen again in later chunks)
        // Merged entities come first so their upgraded labels take precedence
        // (e.g., Person upgraded to Doctor keeps the Doctor label)
        return (mergedEntities().mapNotNull { it.convergenceTarget } + newEntities())
            .distinctBy { it.id }
    }

    fun newRelationships(): List<NewRelationship> {
        return relationshipMerges.merges.map { it.resolution }.filterIsInstance<NewRelationship>()
    }

    fun mergedRelationships(): List<ExistingRelationship> {
        return relationshipMerges.merges.map { it.resolution }.filterIsInstance<ExistingRelationship>()
    }

    override fun infoString(verbose: Boolean?, indent: Int): String {
//        return "KnowledgeGraphUpdate(entitiesResolution=${entitiesResolution.resolutions.size}, relationships=${newRelationships.size}, entityLabels=${
//            "TODO"
//        }, relationshipTypes=${newRelationships.map { it.type }.distinct().joinToString(", ")})"
        return toString()
    }
}

