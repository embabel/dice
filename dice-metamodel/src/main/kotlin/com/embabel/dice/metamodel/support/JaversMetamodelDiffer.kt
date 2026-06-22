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
 * implementation currently uses direct string equality on the sorted label/property strings
 * rather than the full JaVers object-graph diff. JaVers is retained as the declared dependency
 * because it is already present in the consuming application for broader audit-trail use cases
 * (proposition-level change tracking), and the `JaversBuilder` instance here is available for
 * richer structural diffing if that need arises.
 *
 * Stateless and thread-safe — a single shared instance is fine.
 */
class JaversMetamodelDiffer : MetamodelDiffer {

    private val logger = LoggerFactory.getLogger(JaversMetamodelDiffer::class.java)

    // JaVers instance kept for future richer diffing; entity-type shape comparison uses
    // direct string equality (see diff() below), which is sufficient and cheaper.
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

        // Detect modified entity types (label- and property-level changes on a type whose name is
        // preserved). We project each common type into a TypeShapeSnapshot and compare the sorted
        // label/property strings directly — a simpler and cheaper approach than full JaVers diffing.
        val commonTypes = fromTypes intersect toTypes
        for (typeName in commonTypes.sorted()) {
            val fromSnapshot = buildShapeSnapshot(from, typeName)
            val toSnapshot = buildShapeSnapshot(to, typeName)

            val shapeChanged = fromSnapshot.sortedLabels != toSnapshot.sortedLabels
                || fromSnapshot.sortedProperties != toSnapshot.sortedProperties

            if (shapeChanged) {
                val addedLabels = toSnapshot.labelSet - fromSnapshot.labelSet
                val removedLabels = fromSnapshot.labelSet - toSnapshot.labelSet
                val addedProperties = toSnapshot.propertySet - fromSnapshot.propertySet
                val removedProperties = fromSnapshot.propertySet - toSnapshot.propertySet
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

    /**
     * Builds a [TypeShapeSnapshot] for [typeName] from the given [version], reading the
     * label and property sets stored in the version. Falls back to empty sets if the type
     * name is somehow absent (shouldn't happen for the common-type intersection, but safe).
     */
    private fun buildShapeSnapshot(version: MetamodelVersion, typeName: String): TypeShapeSnapshot {
        val labels = version.entityTypeLabels[typeName].orEmpty()
        val properties = version.entityTypeProperties[typeName].orEmpty()
        return TypeShapeSnapshot(
            typeName = typeName,
            sortedLabels = labels.sorted().joinToString(" "),
            sortedProperties = properties.sorted().joinToString(" "),
            labelSet = labels,
            propertySet = properties,
        )
    }
}

/**
 * Lightweight snapshot of a single entity type's label and property sets, used as the JaVers
 * comparison target. JaVers compares it by value, not identity.
 *
 * @property typeName The entity type name.
 * @property sortedLabels Sorted, space-joined labels — what JaVers actually compares. Space is used
 *   as the delimiter because it cannot appear in a JVM identifier, avoiding corruption when label
 *   or property names contain commas.
 * @property sortedProperties Sorted, space-joined property names — what JaVers actually compares.
 * @property labelSet Raw label set, kept so we can compute added/removed sets after the diff.
 * @property propertySet Raw property-name set, kept so we can compute added/removed sets after the diff.
 */
internal data class TypeShapeSnapshot(
    val typeName: String,
    val sortedLabels: String,
    val sortedProperties: String,
    val labelSet: Set<String> = sortedLabels.split(" ").filter { it.isNotBlank() }.toSet(),
    val propertySet: Set<String> = sortedProperties.split(" ").filter { it.isNotBlank() }.toSet(),
)
