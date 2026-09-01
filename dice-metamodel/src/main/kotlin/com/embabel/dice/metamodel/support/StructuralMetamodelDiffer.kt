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
 * churns; the names inside a descriptor are free text and are never parsed.
 *
 * A rename has to be unmistakable to pair. When two new types both declare `Person` as a former
 * name, the declaration is saying two things at once, and the differ pairs neither: both read as
 * ordinary additions, `Person` reads as an ordinary removal, and an
 * [MetamodelChange.AmbiguousEntityTypeRename] says which claim was set aside. See
 * `docs/design/metamodel-diff.md`.
 *
 * Stateless and thread-safe; one shared instance is fine.
 */
class StructuralMetamodelDiffer : MetamodelDiffer, DeclaredObservedDiffer {

    override fun diff(from: MetamodelVersion, to: MetamodelVersion): MetamodelDiff {
        val changes = mutableListOf<MetamodelChange>()

        val fromTypes = from.entityTypeNames.toSet()
        val toTypes = to.entityTypeNames.toSet()

        val typePairing = pairRenamedTypes(
            removed = fromTypes - toTypes,
            added = toTypes - fromTypes,
            aliases = to.entityTypeAliases,
        )
        val renamedTypes = typePairing.renames

        (fromTypes - toTypes - renamedTypes.keys).sorted()
            .mapTo(changes) { MetamodelChange.EntityTypeRemoved(it) }
        (toTypes - fromTypes - renamedTypes.values.toSet()).sorted()
            .mapTo(changes) { MetamodelChange.EntityTypeAdded(it) }

        // A contested claim pairs nothing, so both sides of it have just been reported as an
        // ordinary removal and addition. The entry saying which declaration was set aside goes
        // here, ahead of the per-type blocks.
        changes += typePairing.ambiguities

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
            changes += properties.ambiguities
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
        // A declared name can also be fully qualified where the observed one is simple. The stamp
        // holds `com.example.Person` for a JVM-backed type, extraction records the mention as
        // `Person`, and the graph reports `Person`. Both spellings of every declared type go on the
        // declared side, through DeclaredSchema.entityTypeOwnLabels.
        //
        // Declared former names count as declared too. Nodes written before a type was renamed keep
        // the old label, and the rename was declared, so the old label is known. Leaving it out
        // would report a declared rename as drift on every check from then on. A former name is a
        // declared name, so it brings its own label with it the same way.
        val declaredAliases = declared.version.entityTypeAliases.values.flatten()
        val declaredLabels = declaredTypes +
            declared.entityTypeOwnLabels +
            declared.version.entityTypeLabels.values.flatten() +
            declaredAliases +
            ownLabelsOf(declaredAliases)

        // A type the host's dictionary names but the selector leaves outside governance is a known
        // type, and the drift check has to recognise it as such. It gets its own excluded set,
        // separate from declaredLabels, because it must stay out of unobservedEntityTypes below: a
        // governance-exempt type with no data isn't the informational case that bucket describes.
        // These names come off the same dictionary the governed ones do, so they can be fully
        // qualified in the same way, and their own labels are excluded alongside them.
        val excludedFromDrift = declaredLabels +
            declared.ungovernedEntityTypeNames +
            ownLabelsOf(declared.ungovernedEntityTypeNames)

        // Drift is observed and never declared: orphaned data whose declaring integration is gone,
        // or was never registered. The opposite direction gets its own informational bucket, since a
        // declared type with zero instances is an ordinary state. That direction stays on the type
        // names: "declared but with no data" is a statement about types, and a parent label listed
        // as an unobserved type would be noise about something that was never a type in its own
        // right. A declared type counts as observed under either spelling of its own name, so a
        // fully qualified declaration is answered by the simple label a graph reports for it.
        //
        // Relationships compare on the bare type name, because that is all a graph can report: a
        // `db.relationshipTypes()`-style query knows the type, not which node types an instance
        // connected. The bare names come off the declaration, which carried them un-rendered. We
        // never recover one by parsing a `From-[name]->To` descriptor, since these names are free
        // text and can contain a `-[...]->`-shaped substring themselves.
        val declaredRels = declared.relationshipTypeNames
        val observedRels = observed.relationshipTypeNames
        val relsExcludedFromDrift = declaredRels + declared.ungovernedRelationshipTypeNames

        return DeclaredObservedDiff(
            declared = declared,
            observedSchema = observed,
            driftedEntityTypes = canonical(observedTypes - excludedFromDrift),
            driftedRelationshipTypes = canonical(observedRels - relsExcludedFromDrift),
            unobservedEntityTypes = canonical(declaredTypes.filterNot { isObserved(it, observedTypes) }),
            unobservedRelationshipTypes = canonical(declaredRels - observedRels),
        )
    }

    /** The five ways a type's property set can differ, gathered in one pass. */
    private data class PropertyDelta(
        val added: Set<PropertySignature>,
        val removed: Set<PropertySignature>,
        val renames: List<MetamodelChange.PropertyRenamed>,
        val ambiguities: List<MetamodelChange.AmbiguousPropertyRename>,
        val signatureChanges: List<MetamodelChange.PropertySignatureChanged>,
    )

    /** What the type-level pairing found: the renames, and the claims it declined to resolve. */
    private class TypeRenamePairing(
        val renames: Map<String, String>,
        val ambiguities: List<MetamodelChange.AmbiguousEntityTypeRename>,
    )

    /** The same for one type's properties. */
    private class PropertyRenamePairing(
        val renames: List<MetamodelChange.PropertyRenamed>,
        val ambiguities: List<MetamodelChange.AmbiguousPropertyRename>,
    )

    /**
     * Pair each removed type with the added type that declares the removed name as a former name.
     *
     * Pairing needs an exclusive claim on both sides: among the types that are new in this version,
     * the removed name is declared by exactly one of them, and that one declares exactly one removed
     * name. Anything else is a contested claim, and it pairs nothing — both sides read as an
     * ordinary removal and addition, with a [MetamodelChange.AmbiguousEntityTypeRename] naming the
     * whole group. See [groupClaims].
     *
     * Exclusivity is judged among added types only, since only a removed name arriving on a new type
     * is shaped like a rename. A type present in both versions can legally declare the same removed
     * name as a former name — that is a merge, not a rename candidate — and it does not contest the
     * pairing. It reports where it belongs, as an [MetamodelChange.EntityTypeAliasesChanged] on the
     * surviving type.
     *
     * Matching is against the whole declared alias set, so a type renamed twice still pairs across
     * stamps that aren't adjacent. An alias naming a type that isn't gone from the newer version
     * says nothing and is left out before any of this.
     */
    private fun pairRenamedTypes(
        removed: Set<String>,
        added: Set<String>,
        aliases: Map<String, Set<String>>,
    ): TypeRenamePairing {
        if (removed.isEmpty() || added.isEmpty() || aliases.isEmpty()) {
            return TypeRenamePairing(emptyMap(), emptyList())
        }

        val claims = added
            .associateWith { addedName -> aliases[addedName].orEmpty() intersect removed }
            .filterValues { it.isNotEmpty() }
        if (claims.isEmpty()) return TypeRenamePairing(emptyMap(), emptyList())

        val grouped = groupClaims(claims)
        return TypeRenamePairing(
            renames = grouped.paired,
            ambiguities = grouped.contested.map { group ->
                MetamodelChange.AmbiguousEntityTypeRename(
                    formerNames = group.formerNames,
                    candidates = group.candidates,
                )
            },
        )
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
     * Pair each removed property name with the added signature that declares it as a former name.
     *
     * Runs on what is left after the name matching in [comparePropertiesOf], so a name present on
     * both sides is never a candidate: an alias naming a property that still exists says nothing.
     * The pairing rule is the type pairing's rule one level down — an exclusive claim on both
     * sides, and a contested claim pairs nothing and reports as a
     * [MetamodelChange.AmbiguousPropertyRename].
     *
     * A name carrying more than one signature is left out. That is the type-merge path, where two
     * same-named domain types each declare the property, and there is no single before or after to
     * pair; it falls back to a removal and an addition, as it does for a shape change. A name left
     * out this way is not part of any claim, so a signature naming it never turns up contested.
     */
    private fun pairRenamedProperties(
        typeName: String,
        added: List<PropertySignature>,
        removed: List<PropertySignature>,
        fromNames: Set<String>,
        toNames: Set<String>,
    ): PropertyRenamePairing {
        val empty = PropertyRenamePairing(emptyList(), emptyList())

        val candidates = added
            .groupBy { it.name }
            .filter { (name, signatures) -> signatures.size == 1 && name !in fromNames }
            .mapValues { (_, signatures) -> signatures.single() }
        if (candidates.isEmpty()) return empty

        val goneNames = removed
            .groupBy { it.name }
            .filter { (name, signatures) -> signatures.size == 1 && name !in toNames }
            .mapValues { (_, signatures) -> signatures.single() }
        if (goneNames.isEmpty()) return empty

        val claims = candidates
            .mapValues { (_, signature) -> signature.aliases intersect goneNames.keys }
            .filterValues { it.isNotEmpty() }
        if (claims.isEmpty()) return empty

        val grouped = groupClaims(claims)
        return PropertyRenamePairing(
            renames = grouped.paired
                .map { (beforeName, afterName) ->
                    MetamodelChange.PropertyRenamed(
                        typeName = typeName,
                        before = goneNames.getValue(beforeName),
                        after = candidates.getValue(afterName),
                    )
                }
                .sortedBy { it.after.name },
            ambiguities = grouped.contested.map { group ->
                MetamodelChange.AmbiguousPropertyRename(
                    typeName = typeName,
                    formerNames = group.formerNames,
                    candidates = group.candidates,
                )
            },
        )
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
        // removed sets, so one rename is one entry rather than a removal plus an addition too. A
        // contested claim pairs nothing, so its names stay in both sets and are reported plainly.
        val pairing = pairRenamedProperties(
            typeName = typeName,
            added = added,
            removed = removed,
            fromNames = fromByName.keys,
            toNames = toByName.keys,
        )

        return PropertyDelta(
            added = canonical(added - pairing.renames.mapTo(mutableSetOf()) { it.after }),
            removed = canonical(removed - pairing.renames.mapTo(mutableSetOf()) { it.before }),
            renames = pairing.renames,
            ambiguities = pairing.ambiguities,
            signatureChanges = signatureChanges,
        )
    }

    private companion object {

        /**
         * The own labels of a set of declared names: [DeclaredSchema.ownLabelOf] over each of them.
         */
        private fun ownLabelsOf(names: Collection<String>): Set<String> =
            names.mapTo(mutableSetOf()) { DeclaredSchema.ownLabelOf(it) }

        /**
         * Whether the graph reported a declared type, under either spelling of its name: the name
         * as it was declared, or the label that name writes onto a node.
         */
        private fun isObserved(declaredTypeName: String, observedTypes: Set<String>): Boolean =
            declaredTypeName in observedTypes || DeclaredSchema.ownLabelOf(declaredTypeName) in observedTypes

        /** Old names and the new names claiming them, where no one claim is exclusive. */
        private class ContestedClaim(val formerNames: Set<String>, val candidates: Set<String>)

        /** The outcome of reading a set of declared claims: what paired, and what was contested. */
        private class ClaimGrouping(
            val paired: Map<String, String>,
            val contested: List<ContestedClaim>,
        )

        /**
         * Read declared former names — each new name against the old names it claims — and decide
         * which of them are renames.
         *
         * A claim is a rename when it is exclusive both ways within [claims]: the old name is
         * claimed by that new name alone, and that new name claims that old name alone. Everything
         * else is contested and pairs nothing.
         *
         * Exclusivity is judged over what the caller puts in [claims], and each caller leaves some
         * declarations out — a surviving type's alias at the type level, a name carrying two
         * signatures at the property level. A declaration left out doesn't contest anything here;
         * it reports through whichever change kind covers it.
         *
         * The way to see the rule is as a graph, old names on one side, new names on the other, an
         * edge for each claim. A piece of that graph holding one name on each side is a rename, and
         * the walk below pairs it. A larger piece has no answer in the declaration: two new types
         * claiming `Person` could each be the one that carries its data, and picking either would
         * attach the old type's history to a type nobody nominated. The whole piece comes back as
         * one [ContestedClaim] so a caller sees which names are tangled together, and each name in
         * it goes on to report as an ordinary addition or removal.
         *
         * Deterministic: old names are walked in sorted order, so a group is always found at its
         * lowest old name, and groups don't share old names. That puts [ClaimGrouping.contested] in
         * order of first contested old name, and the names inside each group come out sorted.
         *
         * @param claims New name to the old names it declares as former names. Every old name here
         *   is one the comparison already found gone; names on both sides are filtered out earlier.
         */
        private fun groupClaims(claims: Map<String, Set<String>>): ClaimGrouping {
            val claimants = mutableMapOf<String, MutableSet<String>>()
            claims.forEach { (newName, oldNames) ->
                oldNames.forEach { oldName -> claimants.getOrPut(oldName) { mutableSetOf() } += newName }
            }

            val paired = LinkedHashMap<String, String>()
            val contested = mutableListOf<ContestedClaim>()
            val visited = mutableSetOf<String>()

            for (startName in claimants.keys.sorted()) {
                if (startName in visited) continue

                // Walk out from this old name to every name reachable through a claim.
                val groupOld = mutableSetOf(startName)
                val groupNew = mutableSetOf<String>()
                val pendingOld = ArrayDeque(listOf(startName))
                val pendingNew = ArrayDeque<String>()
                while (pendingOld.isNotEmpty() || pendingNew.isNotEmpty()) {
                    while (pendingOld.isNotEmpty()) {
                        val oldName = pendingOld.removeFirst()
                        claimants.getValue(oldName).forEach { if (groupNew.add(it)) pendingNew += it }
                    }
                    while (pendingNew.isNotEmpty()) {
                        val newName = pendingNew.removeFirst()
                        claims.getValue(newName).forEach { if (groupOld.add(it)) pendingOld += it }
                    }
                }
                visited += groupOld

                if (groupOld.size == 1 && groupNew.size == 1) {
                    paired[groupOld.first()] = groupNew.first()
                } else {
                    contested += ContestedClaim(canonical(groupOld), canonical(groupNew))
                }
            }

            return ClaimGrouping(paired = paired, contested = contested)
        }

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
