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
 * Declared former names make a rename pair up instead of reading as a removal and an addition. The
 * pairing runs first, and everything after it compares the older version modulo the renames it
 * found: a label or a reference target still spelled with an old name is the rename propagating, so
 * it folds into the rename entry rather than reporting as loss on every referrer and every child.
 * Rendered relationship descriptors are left alone, so a relationship touching a renamed type still
 * churns; the names inside a descriptor are free text and are never parsed. See
 * `docs/design/metamodel-diff.md`.
 *
 * Stateless and thread-safe; one shared instance is fine.
 */
class StructuralMetamodelDiffer : MetamodelDiffer, DeclaredObservedDiffer {

    override fun diff(from: MetamodelVersion, to: MetamodelVersion): MetamodelDiff {
        val changes = mutableListOf<MetamodelChange>()

        val fromTypes = from.entityTypeNames.toSet()
        val toTypes = to.entityTypeNames.toSet()

        val renamedTypes = pairRenamedTypes(
            removed = fromTypes - toTypes,
            added = toTypes - fromTypes,
            aliases = to.entityTypeAliases,
        )

        (fromTypes - toTypes - renamedTypes.keys).sorted()
            .mapTo(changes) { MetamodelChange.EntityTypeRemoved(it) }
        (toTypes - fromTypes - renamedTypes.values.toSet()).sorted()
            .mapTo(changes) { MetamodelChange.EntityTypeAdded(it) }

        // The types to compare member by member: those named the same in both versions, plus each
        // paired rename, which compares the old name's members against the new name's. Both are
        // reported under the newer name and walked in that order, so the output is canonical.
        val comparable = ((fromTypes intersect toTypes).map { it to it } + renamedTypes.toList())
            .sortedBy { (_, afterName) -> afterName }

        for ((beforeName, afterName) in comparable) {
            if (beforeName != afterName) {
                changes += MetamodelChange.EntityTypeRenamed(before = beforeName, after = afterName)
            }

            val aliasesChange = compareAliasesOf(beforeName, afterName, from, to)
            if (aliasesChange != null) changes += aliasesChange

            // The before side is read modulo the renames this diff already found: a label or a
            // reference target spelled with an old name is the rename propagating, and comparing it
            // against the new spelling would report the rename again as loss.
            val beforeLabels = substitute(from.entityTypeLabels[beforeName].orEmpty(), renamedTypes)
            val afterLabels = to.entityTypeLabels[afterName].orEmpty()
            val addedLabels = canonical(afterLabels - beforeLabels)
            val removedLabels = canonical(beforeLabels - afterLabels)

            val properties = comparePropertiesOf(
                typeName = afterName,
                from = from.entityTypeProperties[beforeName].orEmpty()
                    .mapTo(mutableSetOf()) { substitute(it, renamedTypes) },
                to = to.entityTypeProperties[afterName].orEmpty(),
            )

            if (addedLabels.isNotEmpty() || removedLabels.isNotEmpty() ||
                properties.added.isNotEmpty() || properties.removed.isNotEmpty()
            ) {
                changes += MetamodelChange.EntityTypeModified(
                    typeName = afterName,
                    addedLabels = addedLabels,
                    removedLabels = removedLabels,
                    addedProperties = properties.added,
                    removedProperties = properties.removed,
                )
            }
            changes += properties.renames
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
        //
        // Declared former names count as declared too. Nodes written before a type was renamed keep
        // the old label, and the rename was declared, so the old label is known. Leaving it out
        // would report a declared rename as drift on every check from then on.
        val declaredLabels = declaredTypes +
            declared.version.entityTypeLabels.values.flatten() +
            declared.version.entityTypeAliases.values.flatten()

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

    /** The four ways a type's property set can differ, gathered in one pass. */
    private data class PropertyDelta(
        val added: Set<PropertySignature>,
        val removed: Set<PropertySignature>,
        val renames: List<MetamodelChange.PropertyRenamed>,
        val signatureChanges: List<MetamodelChange.PropertySignatureChanged>,
    )

    /**
     * Pair each removed type with an added type that declares the removed name as a former name.
     *
     * Removed names are walked in sorted order and matched against added names in sorted order,
     * which makes the pairing one-to-one and the same every run. An added type claiming two removed
     * names takes the first of them, and the other stays an ordinary removal; a removed name
     * claimed by two added types goes to the first of those, and the second stays an ordinary
     * addition.
     *
     * Matching is against the whole declared alias set, so a type renamed twice still pairs across
     * stamps that aren't adjacent.
     *
     * @return Old name to new name, empty when nothing paired.
     */
    private fun pairRenamedTypes(
        removed: Set<String>,
        added: Set<String>,
        aliases: Map<String, Set<String>>,
    ): Map<String, String> {
        if (removed.isEmpty() || added.isEmpty() || aliases.isEmpty()) return emptyMap()

        val candidates = added.sorted()
        val claimed = mutableSetOf<String>()
        val paired = LinkedHashMap<String, String>()
        for (removedName in removed.sorted()) {
            val match = candidates.firstOrNull { addedName ->
                addedName !in claimed && removedName in aliases[addedName].orEmpty()
            } ?: continue
            claimed += match
            paired[removedName] = match
        }
        return paired
    }

    /**
     * Compare what two versions declare as one type's former names.
     *
     * When the type was itself renamed, the alias naming the old name is the rename and is reported
     * as [MetamodelChange.EntityTypeRenamed], so it is left out of the comparison here. What is left
     * is an alias added or retired on top of that, which moves the content hash and has to surface
     * as something.
     *
     * @return The change, or `null` when the declarations agree.
     */
    private fun compareAliasesOf(
        beforeName: String,
        afterName: String,
        from: MetamodelVersion,
        to: MetamodelVersion,
    ): MetamodelChange.EntityTypeAliasesChanged? {
        val before = from.entityTypeAliases[beforeName].orEmpty()
        val after = to.entityTypeAliases[afterName].orEmpty()
        val implied = if (beforeName == afterName) emptySet() else setOf(beforeName)
        if (before - implied == after - implied) return null
        return MetamodelChange.EntityTypeAliasesChanged(
            typeName = afterName,
            before = canonical(before),
            after = canonical(after),
        )
    }

    /** Rewrite any label that is a renamed type's old name into its new one. */
    private fun substitute(labels: Set<String>, renames: Map<String, String>): Set<String> =
        if (renames.isEmpty()) labels else labels.mapTo(mutableSetOf()) { renames[it] ?: it }

    /**
     * Rewrite a reference property's target when it points at a renamed type.
     *
     * Only [PropertySignature.Kind.REFERENCE] targets are rewritten. A `VALUE` property's type is a
     * free-text rendering of a JVM type, so an entity type named `Date` must not rewrite every
     * property declared as holding a `Date` value.
     */
    private fun substitute(signature: PropertySignature, renames: Map<String, String>): PropertySignature {
        if (renames.isEmpty() || signature.kind != PropertySignature.Kind.REFERENCE) return signature
        val renamedTarget = renames[signature.type] ?: return signature
        return signature.copy(type = renamedTarget)
    }

    /**
     * Pair each removed property name with an added signature that declares it as a former name.
     *
     * Runs on what is left after the name matching in [comparePropertiesOf], so a name present on
     * both sides is never a candidate: an alias naming a property that still exists says nothing.
     * Removed names are walked in sorted order against added signatures in sorted order, the same
     * one-to-one discipline the type pairing uses, with the same outcome when one signature claims
     * two old names or two signatures claim one.
     *
     * A name carrying more than one signature is left out. That is the type-merge path, where two
     * same-named domain types each declare the property, and there is no single before or after to
     * pair; it falls back to a removal and an addition, as it does for a shape change.
     */
    private fun pairRenamedProperties(
        typeName: String,
        added: List<PropertySignature>,
        removed: List<PropertySignature>,
        fromNames: Set<String>,
        toNames: Set<String>,
    ): List<MetamodelChange.PropertyRenamed> {
        val candidates = added
            .groupBy { it.name }
            .filter { (name, signatures) -> signatures.size == 1 && name !in fromNames }
            .values
            .map { it.single() }
            .sorted()
        if (candidates.isEmpty()) return emptyList()

        val goneNames = removed
            .groupBy { it.name }
            .filter { (name, signatures) -> signatures.size == 1 && name !in toNames }
            .mapValues { (_, signatures) -> signatures.single() }

        val claimed = mutableSetOf<String>()
        val renames = mutableListOf<MetamodelChange.PropertyRenamed>()
        for (removedName in goneNames.keys.sorted()) {
            val match = candidates.firstOrNull { it.name !in claimed && removedName in it.aliases } ?: continue
            claimed += match.name
            renames += MetamodelChange.PropertyRenamed(
                typeName = typeName,
                before = goneNames.getValue(removedName),
                after = match,
            )
        }
        return renames.sortedBy { it.after.name }
    }

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
     *
     * Names that match nothing are then run through [pairRenamedProperties], which pairs a
     * disappearing name with an arriving signature that declares it as a former name.
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

        // Renames are paired off what is left, and the pair is then excluded from the added and
        // removed sets, so one rename is one entry rather than a removal plus an addition too.
        val renames = pairRenamedProperties(
            typeName = typeName,
            added = added,
            removed = removed,
            fromNames = fromByName.keys,
            toNames = toByName.keys,
        )

        return PropertyDelta(
            added = canonical(added - renames.mapTo(mutableSetOf()) { it.after }),
            removed = canonical(removed - renames.mapTo(mutableSetOf()) { it.before }),
            renames = renames,
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
