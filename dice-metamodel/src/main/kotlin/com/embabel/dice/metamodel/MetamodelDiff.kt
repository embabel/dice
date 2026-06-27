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
package com.embabel.dice.metamodel

/**
 * A single structural change between two metamodel versions.
 *
 * Sealed so that callers can exhaustively handle every change kind with a `when` expression.
 */
sealed interface MetamodelChange {

    /**
     * An entity type that is present in the newer schema but absent from the older one.
     *
     * @property typeName The name of the added entity type.
     */
    data class EntityTypeAdded(val typeName: String) : MetamodelChange

    /**
     * An entity type that was present in the older schema but has been removed from the newer one.
     * Propositions whose entity mentions reference this type are candidates for quarantine.
     *
     * @property typeName The name of the removed entity type.
     */
    data class EntityTypeRemoved(val typeName: String) : MetamodelChange

    /**
     * An entity type whose labels and/or properties changed between versions but whose name is
     * preserved. The name is still resolvable; consumers may wish to re-extract mentions that relied
     * on the old shape. A single change captures both the label delta and the property delta for the
     * type; at least one of the four delta sets is non-empty.
     *
     * @property typeName The entity type name (unchanged).
     * @property addedLabels Labels present in the new version but not the old.
     * @property removedLabels Labels present in the old version but not the new.
     * @property addedProperties Property names present in the new version but not the old.
     * @property removedProperties Property names present in the old version but not the new.
     */
    data class EntityTypeModified @JvmOverloads constructor(
        val typeName: String,
        val addedLabels: Set<String> = emptySet(),
        val removedLabels: Set<String> = emptySet(),
        val addedProperties: Set<String> = emptySet(),
        val removedProperties: Set<String> = emptySet(),
    ) : MetamodelChange

    /**
     * An allowed relationship that is present in the newer schema but absent from the older one.
     *
     * @property descriptor A human-readable descriptor of the form `From-[name]->To`.
     */
    data class RelationshipAdded(val descriptor: String) : MetamodelChange

    /**
     * An allowed relationship that was present in the older schema but has been removed.
     *
     * @property descriptor A human-readable descriptor of the form `From-[name]->To`.
     */
    data class RelationshipRemoved(val descriptor: String) : MetamodelChange
}

/**
 * The result of comparing two [MetamodelVersion]s (or two `DataDictionary` instances directly).
 *
 * A diff is **empty** when no structural changes were detected (the schemas are equivalent).
 * Use [isEmpty] to guard against unnecessary quarantine sweeps.
 *
 * @property fromVersion The baseline (older) version.
 * @property toVersion The target (newer) version.
 * @property changes The ordered list of structural changes, grouped by kind.
 */
data class MetamodelDiff(
    val fromVersion: MetamodelVersion,
    val toVersion: MetamodelVersion,
    val changes: List<MetamodelChange>,
) {

    /** `true` when no structural changes were detected. */
    val isEmpty: Boolean get() = changes.isEmpty()

    /** All [MetamodelChange.EntityTypeRemoved] entries, for quick quarantine candidate lookup. */
    val removedEntityTypes: Set<String>
        get() = changes
            .filterIsInstance<MetamodelChange.EntityTypeRemoved>()
            .map { it.typeName }
            .toSet()

    /** All [MetamodelChange.EntityTypeAdded] entries. */
    val addedEntityTypes: Set<String>
        get() = changes
            .filterIsInstance<MetamodelChange.EntityTypeAdded>()
            .map { it.typeName }
            .toSet()

    /** All [MetamodelChange.EntityTypeModified] entries. */
    val modifiedEntityTypes: List<MetamodelChange.EntityTypeModified>
        get() = changes.filterIsInstance<MetamodelChange.EntityTypeModified>()
}
