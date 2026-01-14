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

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.core.Sourced
import com.embabel.common.core.types.HasInfoString


/**
 * Update we'll apply to a knowledge graph in a persistent store.
 * Identifies the chunks that it is based on.
 */
data class KnowledgeGraphDelta(
    override val chunkIds: Set<String>,
    val entityMerges: Merges<com.embabel.dice.common.SuggestedEntityResolution, NamedEntityData>,
    val relationshipMerges: Merges<SuggestedRelationshipResolution, RelationshipInstance>,
) : HasInfoString, Sourced {

    fun newEntities(): List<NamedEntityData> {
        return entityMerges.merges
            .filter { it.resolution is com.embabel.dice.common.NewEntity }
            .mapNotNull { it.convergenceTarget }
    }

    fun mergedEntities(): List<EntityMerge> {
        return entityMerges.merges
            .filter { it.resolution is com.embabel.dice.common.ExistingEntity }
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
