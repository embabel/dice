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
import org.jetbrains.annotations.ApiStatus


import java.util.Objects

/**
 * Copy into a set that nothing can change afterwards, including a Java caller mutating what a
 * getter returned and a builder that still holds the original.
 *
 * `java.util.Set.copyOf` is immutable too, but it randomises iteration order. Results here are
 * sorted on the way out so a diff reads and logs the same way every run, so this wraps a fresh
 * `LinkedHashSet`, which keeps that order and is equally unmodifiable: the mutable copy inside
 * never escapes.
 */
private fun <T> immutableCopy(values: Set<T>): Set<T> =
    java.util.Collections.unmodifiableSet(LinkedHashSet(values))

/** Same for lists: an immutable copy that keeps its order. */
private fun <T> immutableCopy(values: List<T>): List<T> = java.util.List.copyOf(values)

/**
 * One structural change between two metamodel versions.
 *
 * Sealed, so a caller can handle every change kind in a `when` and the compiler flags them when a
 * new kind arrives. The next slice decides which changes are lossy enough to quarantine data over,
 * where an unhandled change kind would be treated as harmless.
 *
 * The kinds don't overlap. A given difference is reported exactly once, by whichever kind describes
 * it most precisely: a property that keeps its name and changes shape is a
 * [PropertySignatureChanged], and one that changes name under a declared alias is a
 * [PropertyRenamed]. The exception is relationships, whose rendered descriptors embed type names the
 * differ compares as atoms: a relationship touching a renamed type churns as a
 * [RelationshipRemoved] plus a [RelationshipAdded] alongside the [EntityTypeRenamed] that already
 * described the same move. See `docs/design/metamodel-diff.md`.
 *
 * [AmbiguousEntityTypeRename] and [AmbiguousPropertyRename] sit outside that accounting. They
 * describe a declaration the differ could not act on rather than a structural difference, and the
 * names they carry are reported as ordinary removals and additions beside them.
 */
sealed interface MetamodelChange {

    /**
     * An entity type present in the newer schema but absent from the older one.
     *
     * @property typeName The name of the added entity type.
     */
    data class EntityTypeAdded(val typeName: String) : MetamodelChange

    /**
     * An entity type present in the older schema and gone from the newer one. Propositions whose
     * entity mentions reference it are the quarantine candidates in a later slice.
     *
     * @property typeName The name of the removed entity type.
     */
    data class EntityTypeRemoved(val typeName: String) : MetamodelChange

    /**
     * An entity type that changed name, paired up because the newer version declares the old name as
     * one of the type's former names.
     *
     * Without the alias the same move reads as [EntityTypeRemoved] plus [EntityTypeAdded], which
     * says the type's data has nothing describing it any more. With it, this entry replaces that
     * pair, and whatever else moved on the type is reported under [after] as an
     * [EntityTypeModified] or a property change.
     *
     * The label swap the rename itself implies rides here: a type's own name is one of its labels,
     * so `Person` becoming `Human` mechanically loses the label `Person` and gains `Human`. That
     * pair is folded into this entry, along with the same swap wherever it propagates — a child
     * type's inherited label, another type's reference to this one. Parent and other label changes
     * still report on the paired [EntityTypeModified].
     *
     * Experimental: shape may change before 1.0.
     *
     * @property before The name in the older version.
     * @property after The name in the newer version.
     */
    data class EntityTypeRenamed(val before: String, val after: String) : MetamodelChange {

        init {
            require(before != after) {
                "EntityTypeRenamed pairs two names for one type, but got '$before' twice."
            }
        }
    }

    /**
     * Former names that more than one new type lays claim to, or a new type claiming more than one
     * former name that was still live. The differ refuses to guess which move was the rename.
     *
     * Pairing needs an exclusive claim: one old name, one new type, and neither of them wanted by
     * anything else. Two new types both declaring `Person` as a former name is a schema saying two
     * incompatible things, and choosing one would attach `Person`'s label, property and reference
     * history to a type nobody nominated. So nothing pairs here. Every name in [formerNames] is
     * reported as an [EntityTypeRemoved], every name in [candidates] as an [EntityTypeAdded], the
     * reading a schema with no aliases at all would get, and this entry records that a declaration
     * was set aside and why.
     *
     * A declaration can reach this state honestly, which is why it reports rather than throws.
     * `MetamodelVersion` refuses an alias naming a type the same schema still declares, and says
     * nothing about two types sharing one former name; and the two sides of a diff are two
     * independently stamped versions, so a caller comparing stamps out of a store has no way to
     * fix the input. The way out is in the declaration: retire the alias from the types that
     * should not carry it, or stamp the moves separately so each rename stands alone.
     *
     * Experimental: shape may change before 1.0.
     *
     * @property formerNames The contested old names, all of them gone from the newer version.
     * @property candidates The new type names claiming them.
     */
    class AmbiguousEntityTypeRename(
        formerNames: Set<String>,
        candidates: Set<String>,
    ) : MetamodelChange {

        // Copied, and a plain class rather than a `data class`, for the same reason as
        // EntityTypeModified: a generated copy() would hand its argument straight to the field.
        val formerNames: Set<String> = immutableCopy(formerNames)

        val candidates: Set<String> = immutableCopy(candidates)

        init {
            require(this.formerNames.isNotEmpty() && this.candidates.isNotEmpty()) {
                "AmbiguousEntityTypeRename needs names on both sides, but got " +
                    "formerNames=${this.formerNames}, candidates=${this.candidates}"
            }
            require(this.formerNames.size > 1 || this.candidates.size > 1) {
                "AmbiguousEntityTypeRename describes a contested claim, but one former name and " +
                    "one candidate is an ordinary rename: ${this.formerNames} to ${this.candidates}"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is AmbiguousEntityTypeRename &&
                formerNames == other.formerNames &&
                candidates == other.candidates

        override fun hashCode(): Int = Objects.hash(formerNames, candidates)

        override fun toString(): String =
            "AmbiguousEntityTypeRename(formerNames=$formerNames, candidates=$candidates)"
    }

    /**
     * An entity type present in both versions whose declared former names changed: an alias was
     * added or retired.
     *
     * Aliases are hashed, so an alias-only edit moves [MetamodelVersion.contentHash] and has to
     * surface as a change for an empty diff and an equal hash to keep meaning the same thing. It
     * says nothing about the data: no label, property or relationship moved.
     *
     * A rename's own alias entry is not reported here. Declaring `Human` with the former name
     * `Person` is the rename, and it rides in [EntityTypeRenamed]; this entry covers what the
     * declaration says about former names beyond that.
     *
     * Experimental: shape may change before 1.0.
     *
     * @property typeName The entity type name in the newer version.
     * @property before The former names declared in the older version.
     * @property after The former names declared in the newer version.
     */
    class EntityTypeAliasesChanged(
        typeName: String,
        before: Set<String>,
        after: Set<String>,
    ) : MetamodelChange {

        val typeName: String = typeName

        // Copied, and a plain class rather than a `data class`, for the same reason as
        // EntityTypeModified: a generated copy() would hand its argument straight to the field.
        val before: Set<String> = immutableCopy(before)

        val after: Set<String> = immutableCopy(after)

        init {
            require(this.before != this.after) {
                "EntityTypeAliasesChanged requires an actual change, but before and after are " +
                    "identical for '$typeName': ${this.before}"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is EntityTypeAliasesChanged &&
                typeName == other.typeName &&
                before == other.before &&
                after == other.after

        override fun hashCode(): Int = Objects.hash(typeName, before, after)

        override fun toString(): String =
            "EntityTypeAliasesChanged(typeName=$typeName, before=$before, after=$after)"
    }

    /**
     * An entity type that exists in both versions but gained or lost labels or whole properties.
     * One entry carries both deltas for the type; at least one of the four sets is non-empty.
     *
     * Properties are matched by **name**. A property that appears or disappears lands here, with
     * its full signature so a reader can see what was gained or lost. A property whose name
     * survives while its shape moves is a [PropertySignatureChanged], which pairs the before and
     * after signatures in one entry.
     *
     * The one exception is a type that declares the same property name more than once with
     * different signatures, which a `DataDictionary` permits when two same-named domain types are
     * merged into one stamp. There is no single before and after to pair up then, so the differing
     * signatures are reported here as added and removed.
     *
     * A property whose declaration pairs an old name with a new one is a [PropertyRenamed] and is
     * left out of [addedProperties] and [removedProperties]. An entry with nothing left in it after
     * that is not emitted at all, so the at-least-one-set-non-empty contract holds.
     *
     * @property typeName The entity type name. For a type that also changed name, this is the newer
     *   name, and the change of name itself is an [EntityTypeRenamed].
     * @property addedLabels Labels present in the new version but not the old.
     * @property removedLabels Labels present in the old version but not the new.
     * @property addedProperties Full signatures of properties whose names are new in this version.
     * @property removedProperties Full signatures of properties whose names are gone in this version.
     */
    class EntityTypeModified @JvmOverloads constructor(
        typeName: String,
        addedLabels: Set<String> = emptySet(),
        removedLabels: Set<String> = emptySet(),
        addedProperties: Set<PropertySignature> = emptySet(),
        removedProperties: Set<PropertySignature> = emptySet(),
    ) : MetamodelChange {

        val typeName: String = typeName

        val addedLabels: Set<String> = immutableCopy(addedLabels)

        val removedLabels: Set<String> = immutableCopy(removedLabels)

        val addedProperties: Set<PropertySignature> = immutableCopy(addedProperties)

        val removedProperties: Set<PropertySignature> = immutableCopy(removedProperties)

        /** Just the names from [addedProperties], for callers that don't care about the shapes. */
        val addedPropertyNames: Set<String> get() = addedProperties.mapTo(LinkedHashSet()) { it.name }

        /** Just the names from [removedProperties]. */
        val removedPropertyNames: Set<String> get() = removedProperties.mapTo(LinkedHashSet()) { it.name }

        override fun equals(other: Any?): Boolean =
            other is EntityTypeModified &&
                typeName == other.typeName &&
                addedLabels == other.addedLabels &&
                removedLabels == other.removedLabels &&
                addedProperties == other.addedProperties &&
                removedProperties == other.removedProperties

        override fun hashCode(): Int =
            Objects.hash(typeName, addedLabels, removedLabels, addedProperties, removedProperties)

        override fun toString(): String =
            "EntityTypeModified(typeName=$typeName, addedLabels=$addedLabels, removedLabels=$removedLabels, " +
                "addedProperties=$addedProperties, removedProperties=$removedProperties)"
    }

    /**
     * A property that changed name on a type present in both versions, paired up because the newer
     * signature declares the old name as one of its former names.
     *
     * Without the alias the same move reads as a removal and an unrelated-looking addition on
     * [EntityTypeModified], which a quarantine policy has to treat as loss. The pair is
     * one-to-one: each old name pairs with at most one new signature, and each new signature claims
     * at most one old name.
     *
     * The two signatures can differ in more than the name. A property renamed and retyped in one
     * step carries its whole delta here, and [typeChanged], [cardinalityChanged] and [kindChanged]
     * describe it the same way [PropertySignatureChanged] does, so a policy judging the shape move
     * applies one rule to both.
     *
     * Experimental: shape may change before 1.0.
     *
     * @property typeName The entity type carrying the property, under its name in the newer version.
     * @property before The signature in the older version, under its old name.
     * @property after The signature in the newer version, under its new name.
     */
    data class PropertyRenamed(
        val typeName: String,
        val before: PropertySignature,
        val after: PropertySignature,
    ) : MetamodelChange {

        init {
            require(before.name != after.name) {
                "PropertyRenamed pairs two names for one property on '$typeName', but got " +
                    "'${before.name}' twice."
            }
        }

        /** `true` when the value type or reference target moved as well as the name. */
        val typeChanged: Boolean get() = before.type != after.type

        /** `true` when the property went from one value to many, or the reverse. */
        val cardinalityChanged: Boolean get() = before.cardinality != after.cardinality

        /** `true` when the property flipped between holding a value and pointing at another type. */
        val kindChanged: Boolean get() = before.kind != after.kind
    }

    /**
     * The same contested claim as [AmbiguousEntityTypeRename], one level down: property names on
     * one type that more than one new signature claims, or one new signature claiming more than one
     * old name.
     *
     * Nothing pairs. Every name in [formerNames] stays in
     * [EntityTypeModified.removedProperties] and every name in [candidates] in
     * [EntityTypeModified.addedProperties], and this entry records that the declaration was set
     * aside. Choosing between the claimants would say a property's data carried over into a
     * signature the operator never named.
     *
     * Experimental: shape may change before 1.0.
     *
     * @property typeName The entity type carrying the properties, under its name in the newer
     *   version.
     * @property formerNames The contested old property names, all of them gone from the newer
     *   version.
     * @property candidates The new property names claiming them.
     */
    class AmbiguousPropertyRename(
        typeName: String,
        formerNames: Set<String>,
        candidates: Set<String>,
    ) : MetamodelChange {

        val typeName: String = typeName

        // Copied, and a plain class, for the same reason as AmbiguousEntityTypeRename.
        val formerNames: Set<String> = immutableCopy(formerNames)

        val candidates: Set<String> = immutableCopy(candidates)

        init {
            require(this.formerNames.isNotEmpty() && this.candidates.isNotEmpty()) {
                "AmbiguousPropertyRename needs names on both sides, but got " +
                    "formerNames=${this.formerNames}, candidates=${this.candidates} on '$typeName'"
            }
            require(this.formerNames.size > 1 || this.candidates.size > 1) {
                "AmbiguousPropertyRename describes a contested claim, but one former name and one " +
                    "candidate is an ordinary rename: ${this.formerNames} to ${this.candidates} " +
                    "on '$typeName'"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is AmbiguousPropertyRename &&
                typeName == other.typeName &&
                formerNames == other.formerNames &&
                candidates == other.candidates

        override fun hashCode(): Int = Objects.hash(typeName, formerNames, candidates)

        override fun toString(): String =
            "AmbiguousPropertyRename(typeName=$typeName, formerNames=$formerNames, candidates=$candidates)"
    }

    /**
     * A property that kept its name on a type present in both versions, but changed shape: its
     * value type narrowed or widened, its cardinality moved, or it turned from a plain value into a
     * reference to another type (or back).
     *
     * Matching properties by name alone misses this case. Turning `age` from a string into an
     * integer, or a single `worksAt` into a list of them, changes what the graph can hold, and data
     * extracted under the old shape may no longer fit the new one, while both versions still have a
     * property called `age`.
     *
     * Whether a move is *lossy* is decided elsewhere. A diff states what changed; deciding that
     * string→integer strands existing values while integer→string doesn't is a policy question for
     * the quarantine slice.
     *
     * Declared former names are part of a signature, so adding or retiring one on a property that
     * kept its name lands here too, with [typeChanged], [cardinalityChanged] and [kindChanged] all
     * `false`. The entry exists so an empty diff and an equal content hash keep meaning the same
     * thing.
     *
     * @property typeName The entity type carrying the property.
     * @property propertyName The property name, the same on both sides.
     * @property before The signature in the older version.
     * @property after The signature in the newer version.
     */
    data class PropertySignatureChanged(
        val typeName: String,
        val propertyName: String,
        val before: PropertySignature,
        val after: PropertySignature,
    ) : MetamodelChange {

        init {
            require(before.name == propertyName && after.name == propertyName) {
                "PropertySignatureChanged pairs signatures for one property name, but got " +
                    "propertyName='$propertyName', before.name='${before.name}', after.name='${after.name}'"
            }
            require(before != after) {
                "PropertySignatureChanged requires an actual change, but before and after are identical: $before"
            }
        }

        /** `true` when the value type or reference target moved (`string` → `integer`, say). */
        val typeChanged: Boolean get() = before.type != after.type

        /** `true` when the property went from one value to many, or the reverse. */
        val cardinalityChanged: Boolean get() = before.cardinality != after.cardinality

        /** `true` when the property flipped between holding a value and pointing at another type. */
        val kindChanged: Boolean get() = before.kind != after.kind
    }

    /**
     * An allowed relationship present in the newer schema but absent from the older one.
     *
     * @property descriptor A readable descriptor of the form `From-[name]->To`.
     */
    data class RelationshipAdded(val descriptor: String) : MetamodelChange

    /**
     * An allowed relationship present in the older schema but gone from the newer one.
     *
     * @property descriptor A readable descriptor of the form `From-[name]->To`.
     */
    data class RelationshipRemoved(val descriptor: String) : MetamodelChange
}

/**
 * What changed between two declared [MetamodelVersion]s.
 *
 * Both sides are declarations, so the comparison is symmetric and every difference is a change.
 * Comparing a declaration against a live graph has a different answer shape; that is
 * [DeclaredObservedDiff].
 *
 * A diff is **empty** when the two schemas are structurally equivalent, the same condition
 * [MetamodelVersion.hasSameContentAs] reports. The two agree by construction: the differ walks the
 * same fields the content hash is built from.
 *
 * @property fromVersion The baseline (older) version.
 * @property toVersion The target (newer) version.
 * @property changes Every change, in a deterministic order: entity-type changes first (by type
 *   name, a renamed type filed under its newer name), then relationship changes. Any
 *   [MetamodelChange.AmbiguousEntityTypeRename] comes after the added and removed types and before
 *   the per-type blocks, ordered by first contested former name. Within one type the order is
 *   [MetamodelChange.EntityTypeRenamed], [MetamodelChange.EntityTypeAliasesChanged],
 *   [MetamodelChange.EntityTypeModified], [MetamodelChange.PropertyRenamed] by new property name,
 *   [MetamodelChange.AmbiguousPropertyRename] by first contested former name, then
 *   [MetamodelChange.PropertySignatureChanged] by property name. The same pair of versions
 *   always produces the same list.
 */
@ApiStatus.Experimental
class MetamodelDiff(
    val fromVersion: MetamodelVersion,
    val toVersion: MetamodelVersion,
    changes: List<MetamodelChange>,
) {

    /**
     * Copied into an immutable list, so a Java caller calling `getChanges().clear()`, or the differ
     * still holding its builder list, cannot reshape a finished diff. Kotlin's `List` is a
     * compile-time promise only, so the refusal has to happen at runtime. This is a plain class for
     * the same reason: a generated `data class` `copy()` would hand its argument straight to the
     * field and skip the copying.
     */
    val changes: List<MetamodelChange> = immutableCopy(changes)

    /** `true` when nothing changed. */
    val isEmpty: Boolean get() = changes.isEmpty()

    /**
     * Names from every [MetamodelChange.EntityTypeRemoved]: the quarantine candidates. A type that
     * was renamed under a declared alias is a [MetamodelChange.EntityTypeRenamed] and is not here.
     * A former name too many types claimed to pair with is here, since the differ declined to read
     * it as a rename; the [MetamodelChange.AmbiguousEntityTypeRename] beside it says why.
     */
    val removedEntityTypes: Set<String>
        get() = changes
            .filterIsInstance<MetamodelChange.EntityTypeRemoved>()
            .mapTo(mutableSetOf()) { it.typeName }

    /** Names from every [MetamodelChange.EntityTypeAdded]. */
    val addedEntityTypes: Set<String>
        get() = changes
            .filterIsInstance<MetamodelChange.EntityTypeAdded>()
            .mapTo(mutableSetOf()) { it.typeName }

    /** Every [MetamodelChange.EntityTypeModified] entry. */
    val modifiedEntityTypes: List<MetamodelChange.EntityTypeModified>
        get() = changes.filterIsInstance<MetamodelChange.EntityTypeModified>()

    /** Every [MetamodelChange.PropertySignatureChanged] entry. */
    val propertySignatureChanges: List<MetamodelChange.PropertySignatureChanged>
        get() = changes.filterIsInstance<MetamodelChange.PropertySignatureChanged>()

    /**
     * Every [MetamodelChange.AmbiguousEntityTypeRename] entry: the declared type renames this diff
     * could not read, because the claim was contested in one direction or the other. Empty for
     * almost every diff. Each entry's names also appear as ordinary additions and removals.
     */
    val ambiguousEntityTypeRenames: List<MetamodelChange.AmbiguousEntityTypeRename>
        get() = changes.filterIsInstance<MetamodelChange.AmbiguousEntityTypeRename>()

    /** The same for properties: every [MetamodelChange.AmbiguousPropertyRename] entry. */
    val ambiguousPropertyRenames: List<MetamodelChange.AmbiguousPropertyRename>
        get() = changes.filterIsInstance<MetamodelChange.AmbiguousPropertyRename>()

    /**
     * Descriptors from every [MetamodelChange.RelationshipAdded]: relationships the newer schema
     * allows and the older one didn't.
     */
    val addedRelationships: Set<String>
        get() = changes
            .filterIsInstance<MetamodelChange.RelationshipAdded>()
            .mapTo(mutableSetOf()) { it.descriptor }

    /**
     * Descriptors from every [MetamodelChange.RelationshipRemoved]. A relationship touching a
     * renamed type shows up here and in [addedRelationships], since the differ compares rendered
     * descriptors as atoms; the [MetamodelChange.EntityTypeRenamed] beside them describes the move.
     */
    val removedRelationships: Set<String>
        get() = changes
            .filterIsInstance<MetamodelChange.RelationshipRemoved>()
            .mapTo(mutableSetOf()) { it.descriptor }

    /**
     * Every entity type this diff says something about: added, removed, renamed, modified, or
     * holding a property that was renamed or reshaped. A reshaped type shows up in both
     * [modifiedEntityTypes] and [propertySignatureChanges], so answering "did anything about
     * `Person` change?" from those lists means checking each one in turn.
     *
     * A rename contributes both names. A caller asking about `Person` after `Person` became `Human`
     * is asking about data written under the old name, and the diff does say something about it.
     */
    val touchedEntityTypes: Set<String>
        get() = changes.flatMapTo(mutableSetOf()) { change ->
            when (change) {
                is MetamodelChange.EntityTypeAdded -> listOf(change.typeName)
                is MetamodelChange.EntityTypeRemoved -> listOf(change.typeName)
                is MetamodelChange.EntityTypeRenamed -> listOf(change.before, change.after)
                is MetamodelChange.AmbiguousEntityTypeRename -> change.formerNames + change.candidates
                is MetamodelChange.EntityTypeAliasesChanged -> listOf(change.typeName)
                is MetamodelChange.EntityTypeModified -> listOf(change.typeName)
                is MetamodelChange.PropertyRenamed -> listOf(change.typeName)
                is MetamodelChange.AmbiguousPropertyRename -> listOf(change.typeName)
                is MetamodelChange.PropertySignatureChanged -> listOf(change.typeName)
                is MetamodelChange.RelationshipAdded -> emptyList()
                is MetamodelChange.RelationshipRemoved -> emptyList()
            }
        }

    override fun equals(other: Any?): Boolean =
        other is MetamodelDiff &&
            fromVersion == other.fromVersion &&
            toVersion == other.toVersion &&
            changes == other.changes

    override fun hashCode(): Int = Objects.hash(fromVersion, toVersion, changes)

    override fun toString(): String =
        "MetamodelDiff(fromVersion=${fromVersion.contentHash}, toVersion=${toVersion.contentHash}, " +
            "changes=$changes)"
}

/**
 * What a declared schema and a live graph disagree about.
 *
 * [MetamodelDiff] compares two declarations to each other. Here one side is what was declared and
 * the other is a snapshot of reality, and the two can legitimately disagree in either direction, so
 * the result is two separate buckets rather than a symmetric change list:
 *
 * - **Drift** ([driftedEntityTypes] / [driftedRelationshipTypes]): observed in the graph, never
 *   declared. This is the actionable case: data is sitting in the graph whose declaring integration
 *   has since been removed, or never registered one, so nothing here can confirm that data as valid
 *   or explain its shape.
 * - **Unobserved** ([unobservedEntityTypes] / [unobservedRelationshipTypes]): declared, with zero
 *   instances in the graph right now. Informational; a declared type with no data yet is a normal
 *   state.
 *
 * **Names only, both ways.** The declaration knows each property's kind, type and cardinality. The
 * graph doesn't, and can't be asked, so anything richer than a name would be a sample of the data
 * rather than the schema. This comparison stops at type and relationship names and says nothing
 * about whether a declared property's shape matches what the graph stores. Property signatures are
 * compared where both sides have them: declared against declared, in [MetamodelDiff].
 *
 * **A declared label counts as declared.** A graph reports labels, and a type usually carries more
 * than one: declaring `Person` with parent `Agent` puts both labels on every `Person` node. So the
 * declared side of the drift comparison is every entity type name *plus* every label those types
 * declare. Without the labels, an inherited label would be reported as undeclared drift on a schema
 * nobody had changed. [unobservedEntityTypes] stays on the type names alone, because "declared but
 * with no data" is a statement about types, and a parent label was never a type in its own right.
 *
 * @property declared The schema as declared at snapshot time, stamp and bare relationship names.
 * @property observedSchema What the live graph held at snapshot time.
 * @property driftedEntityTypes Observed labels matching neither a declared type name nor a declared
 *   label.
 * @property driftedRelationshipTypes Relationship type names observed with no matching declaration.
 * @property unobservedEntityTypes Declared entity type names with no observed instances.
 * @property unobservedRelationshipTypes Declared relationship type names with no observed instances.
 */
@ApiStatus.Experimental
class DeclaredObservedDiff(
    val declared: DeclaredSchema,
    val observedSchema: ObservedSchema,
    driftedEntityTypes: Set<String>,
    driftedRelationshipTypes: Set<String>,
    unobservedEntityTypes: Set<String>,
    unobservedRelationshipTypes: Set<String>,
) {

    // Copied into immutable sets, and a plain class rather than a `data class`, for the same reason
    // as MetamodelDiff: a finished result must not be reshapeable, and a generated copy() would hand
    // its arguments straight to the fields and skip the copying.

    val driftedEntityTypes: Set<String> = immutableCopy(driftedEntityTypes)

    val driftedRelationshipTypes: Set<String> = immutableCopy(driftedRelationshipTypes)

    val unobservedEntityTypes: Set<String> = immutableCopy(unobservedEntityTypes)

    val unobservedRelationshipTypes: Set<String> = immutableCopy(unobservedRelationshipTypes)

    /** The stamp inside [declared]; its hash is what a drift report would record. */
    val declaredVersion: MetamodelVersion get() = declared.version

    /** `true` when the graph holds any type or relationship that was never declared. */
    val hasDrift: Boolean
        get() = driftedEntityTypes.isNotEmpty() || driftedRelationshipTypes.isNotEmpty()

    override fun equals(other: Any?): Boolean =
        other is DeclaredObservedDiff &&
            declared == other.declared &&
            observedSchema == other.observedSchema &&
            driftedEntityTypes == other.driftedEntityTypes &&
            driftedRelationshipTypes == other.driftedRelationshipTypes &&
            unobservedEntityTypes == other.unobservedEntityTypes &&
            unobservedRelationshipTypes == other.unobservedRelationshipTypes

    override fun hashCode(): Int = Objects.hash(
        declared,
        observedSchema,
        driftedEntityTypes,
        driftedRelationshipTypes,
        unobservedEntityTypes,
        unobservedRelationshipTypes,
    )

    override fun toString(): String =
        "DeclaredObservedDiff(declared=$declared, observedSchema=$observedSchema, " +
            "driftedEntityTypes=$driftedEntityTypes, driftedRelationshipTypes=$driftedRelationshipTypes, " +
            "unobservedEntityTypes=$unobservedEntityTypes, " +
            "unobservedRelationshipTypes=$unobservedRelationshipTypes)"
}
