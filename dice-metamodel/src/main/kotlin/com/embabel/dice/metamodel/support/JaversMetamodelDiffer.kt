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
package com.embabel.dice.metamodel.support

import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelDiffer
import com.embabel.dice.metamodel.MetamodelVersion
import org.javers.core.JaversBuilder
import org.slf4j.LoggerFactory

/**
 * [MetamodelDiffer] that uses [JaVers](https://javers.org/) as its underlying comparison engine.
 *
 * For the entity-type shape comparison (label and property drift on same-named types), the
 * implementation compares the label and property sets directly rather than running the full
 * JaVers object-graph diff. JaVers is retained as the declared dependency because it is already
 * present in the consuming application for broader audit-trail use cases (proposition-level change
 * tracking), and the `JaversBuilder` instance here is available for richer structural diffing if
 * that need arises.
 *
 * Stateless and thread-safe — a single shared instance is fine.
 */
class JaversMetamodelDiffer : MetamodelDiffer {

    private val logger = LoggerFactory.getLogger(JaversMetamodelDiffer::class.java)

    // JaVers instance kept for future richer diffing; entity-type shape comparison compares the
    // label/property sets directly (see diff() below), which is sufficient and cheaper.
    @Suppress("unused")
    private val javers = JaversBuilder.javers().build()

    override fun diff(from: MetamodelVersion, to: MetamodelVersion): MetamodelDiff {
        val changes = mutableListOf<MetamodelChange>()

        // --- Entity type changes ---
        val fromTypes = from.entityTypeNames.toSet()
        val toTypes = to.entityTypeNames.toSet()

        val removedTypes = fromTypes - toTypes
        val addedTypes = toTypes - fromTypes

        removedTypes.sorted().mapTo(changes) { MetamodelChange.EntityTypeRemoved(it) }
        addedTypes.sorted().mapTo(changes) { MetamodelChange.EntityTypeAdded(it) }

        // Detect modified entity types: a type whose name is preserved but whose label or property
        // set changed. Compare the sets directly — never a delimiter-joined projection — so a label
        // or property whose name contains the delimiter (spaces are common in free-text / LLM-extracted
        // names) can't collapse two genuinely different sets into a false "unchanged".
        val commonTypes = fromTypes intersect toTypes
        for (typeName in commonTypes.sorted()) {
            val fromLabels = from.entityTypeLabels[typeName].orEmpty()
            val toLabels = to.entityTypeLabels[typeName].orEmpty()
            val fromProperties = from.entityTypeProperties[typeName].orEmpty()
            val toProperties = to.entityTypeProperties[typeName].orEmpty()

            val addedLabels = toLabels - fromLabels
            val removedLabels = fromLabels - toLabels
            val addedProperties = toProperties - fromProperties
            val removedProperties = fromProperties - toProperties

            if (addedLabels.isNotEmpty() || removedLabels.isNotEmpty() ||
                addedProperties.isNotEmpty() || removedProperties.isNotEmpty()
            ) {
                changes += MetamodelChange.EntityTypeModified(
                    typeName = typeName,
                    addedLabels = addedLabels,
                    removedLabels = removedLabels,
                    addedProperties = addedProperties,
                    removedProperties = removedProperties,
                )
            }
        }

        // --- Relationship changes ---
        val fromRels = from.relationshipNames.toSet()
        val toRels = to.relationshipNames.toSet()

        val removedRels = fromRels - toRels
        val addedRels = toRels - fromRels

        removedRels.sorted().mapTo(changes) { MetamodelChange.RelationshipRemoved(it) }
        addedRels.sorted().mapTo(changes) { MetamodelChange.RelationshipAdded(it) }

        logger.debug(
            "Metamodel diff '{}' → '{}': {} added types, {} removed types, {} modified types, " +
                "{} added rels, {} removed rels",
            from.schemaName,
            to.schemaName,
            addedTypes.size,
            removedTypes.size,
            changes.count { it is MetamodelChange.EntityTypeModified },
            addedRels.size,
            removedRels.size,
        )

        return MetamodelDiff(fromVersion = from, toVersion = to, changes = changes)
    }
}
