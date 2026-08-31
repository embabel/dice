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

import com.embabel.dice.metamodel.DeclaredObservedDiff
import com.embabel.dice.metamodel.DeclaredObservedDiffer
import com.embabel.dice.metamodel.DeclaredSchema
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelDiffer
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.ObservedSchema
import com.embabel.dice.metamodel.PropertySignature

/**
 * The shipped differ: a deterministic structural comparison, with no heuristics and no LLM.
 *
 * It works on the data a [MetamodelVersion] already carries: entity type names, per-type label sets,
 * per-type property *signatures*, and relationship descriptors. Those are the same fields the
 * content hash is built from, which is what makes an empty diff and an equal hash mean the same
 * thing.
 *
 * Two rules it keeps throughout. Sets are compared as sets, never as a delimiter-joined projection,
 * because a label or property name can contain a comma or a space, which is routine when names come
 * from LLM extraction, and joining would collapse two different sets into a false "unchanged".
 * Output is canonical: type names, property names and signatures all come out sorted, so the same
 * pair of versions always produces the same change list in the same order, and a stored diff can be
 * compared with another one.
 *
 * Stateless and thread-safe; one shared instance is fine.
 */
class StructuralMetamodelDiffer : MetamodelDiffer, DeclaredObservedDiffer {

    override fun diff(from: MetamodelVersion, to: MetamodelVersion): MetamodelDiff {
        val changes = mutableListOf<MetamodelChange>()

        val fromTypes = from.entityTypeNames.toSet()
        val toTypes = to.entityTypeNames.toSet()

        (fromTypes - toTypes).sorted().mapTo(changes) { MetamodelChange.EntityTypeRemoved(it) }
        (toTypes - fromTypes).sorted().mapTo(changes) { MetamodelChange.EntityTypeAdded(it) }

        // A type present in both versions can still have moved: labels, whole properties, or the
        // shape of a property whose name stayed put. Walk them in name order so the output is
        // canonical.
        for (typeName in (fromTypes intersect toTypes).sorted()) {
            val addedLabels = canonical(to.entityTypeLabels[typeName].orEmpty() - from.entityTypeLabels[typeName].orEmpty())
            val removedLabels = canonical(from.entityTypeLabels[typeName].orEmpty() - to.entityTypeLabels[typeName].orEmpty())

            val properties = comparePropertiesOf(
                typeName = typeName,
                from = from.entityTypeProperties[typeName].orEmpty(),
                to = to.entityTypeProperties[typeName].orEmpty(),
            )

            if (addedLabels.isNotEmpty() || removedLabels.isNotEmpty() ||
                properties.added.isNotEmpty() || properties.removed.isNotEmpty()
            ) {
                changes += MetamodelChange.EntityTypeModified(
                    typeName = typeName,
                    addedLabels = addedLabels,
                    removedLabels = removedLabels,
                    addedProperties = properties.added,
                    removedProperties = properties.removed,
                )
            }
            changes += properties.signatureChanges
        }

        val fromRels = from.relationshipNames.toSet()
        val toRels = to.relationshipNames.toSet()

        (fromRels - toRels).sorted().mapTo(changes) { MetamodelChange.RelationshipRemoved(it) }
        (toRels - fromRels).sorted().mapTo(changes) { MetamodelChange.RelationshipAdded(it) }

        return MetamodelDiff(fromVersion = from, toVersion = to, changes = changes)
    }

    override fun diffAgainstObserved(declared: DeclaredSchema, observed: ObservedSchema): DeclaredObservedDiff {
        val declaredTypes = declared.version.entityTypeNames.toSet()
        val observedTypes = observed.entityTypeNames

        // What a graph reports is labels, and a type carries every label in its hierarchy: declare
        // `Person` with parent `Agent` and every Person node comes back carrying both. Comparing
        // observed labels against type names alone would call `Agent` undeclared drift on a schema
        // nobody had touched, so the declared side of the drift check is the type names plus every
        // label those types declare.
        val declaredLabels = declaredTypes + declared.version.entityTypeLabels.values.flatten()

        // Drift is observed and never declared: orphaned data whose declaring integration is gone,
        // or was never registered. The opposite direction gets its own informational bucket, since a
        // declared type with zero instances is an ordinary state. That direction stays on the type
        // names: "declared but with no data" is a statement about types, and a parent label listed
        // as an unobserved type would be noise about something that was never a type in its own
        // right.
        //
        // Relationships compare on the bare type name, because that is all a graph can report: a
        // `db.relationshipTypes()`-style query knows the type, not which node types an instance
        // connected. The bare names come off the declaration, which carried them un-rendered. We
        // never recover one by parsing a `From-[name]->To` descriptor, since these names are free
        // text and can contain a `-[...]->`-shaped substring themselves.
        val declaredRels = declared.relationshipTypeNames
        val observedRels = observed.relationshipTypeNames

        return DeclaredObservedDiff(
            declared = declared,
            observedSchema = observed,
            driftedEntityTypes = canonical(observedTypes - declaredLabels),
            driftedRelationshipTypes = canonical(observedRels - declaredRels),
            unobservedEntityTypes = canonical(declaredTypes - observedTypes),
            unobservedRelationshipTypes = canonical(declaredRels - observedRels),
        )
    }

    /** The three ways a type's property set can differ, gathered in one pass. */
    private data class PropertyDelta(
        val added: Set<PropertySignature>,
        val removed: Set<PropertySignature>,
        val signatureChanges: List<MetamodelChange.PropertySignatureChanged>,
    )

    /**
     * Compare one type's properties, matching them up by name.
     *
     * Matching by name turns a reshaped property into one change carrying a before and an after:
     * `age: string` becoming `age: integer` is a single
     * [MetamodelChange.PropertySignatureChanged], which saves a caller pairing up a deletion and an
     * addition.
     *
     * A name can legitimately map to more than one signature on either side, because a
     * `DataDictionary` may hold two same-named domain types whose properties get unioned into a
     * single stamp. There is no single before/after to pair in that case, so the differing
     * signatures are reported as added and removed.
     */
    private fun comparePropertiesOf(
        typeName: String,
        from: Set<PropertySignature>,
        to: Set<PropertySignature>,
    ): PropertyDelta {
        val fromByName = from.groupBy { it.name }
        val toByName = to.groupBy { it.name }

        val added = mutableListOf<PropertySignature>()
        val removed = mutableListOf<PropertySignature>()
        val signatureChanges = mutableListOf<MetamodelChange.PropertySignatureChanged>()

        for (propertyName in (fromByName.keys + toByName.keys).sorted()) {
            val before = fromByName[propertyName].orEmpty().toSet()
            val after = toByName[propertyName].orEmpty().toSet()
            when {
                before == after -> Unit
                before.isEmpty() -> added += after
                after.isEmpty() -> removed += before
                before.size == 1 && after.size == 1 -> signatureChanges += MetamodelChange.PropertySignatureChanged(
                    typeName = typeName,
                    propertyName = propertyName,
                    before = before.single(),
                    after = after.single(),
                )

                else -> {
                    added += after - before
                    removed += before - after
                }
            }
        }

        return PropertyDelta(
            added = canonical(added),
            removed = canonical(removed),
            signatureChanges = signatureChanges,
        )
    }

    private companion object {

        /**
         * Sort into a set that iterates in that order.
         *
         * The sets inside a [MetamodelVersion] are JVM-immutable copies, whose iteration order is
         * unspecified and varies between JVM runs. Set equality doesn't care, but a
         * change list that gets logged or rendered does, so anything leaving here is sorted first.
         */
        private fun <T : Comparable<T>> canonical(values: Collection<T>): Set<T> =
            values.sorted().toCollection(LinkedHashSet())
    }
}
