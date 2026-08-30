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

import java.util.Objects

/**
 * Copy into a collection nothing can change afterwards — not even a Java caller mutating what a
 * getter handed back, or the builder still holding the original.
 *
 * `java.util.Set.copyOf` would also be genuinely immutable, but its iteration order is deliberately
 * randomised, and these results are sorted on the way out so a diff reads and logs the same way
 * every run. Wrapping a fresh `LinkedHashSet` keeps that order and is just as unmodifiable: the
 * mutable copy inside never escapes.
 */
private fun <T> immutableCopy(values: Set<T>): Set<T> =
    java.util.Collections.unmodifiableSet(LinkedHashSet(values))

/** The list equivalent: a genuinely immutable copy that keeps its order. */
private fun <T> immutableCopy(values: List<T>): List<T> = java.util.List.copyOf(values)

/**
 * One structural change between two metamodel versions.
 *
 * Sealed, so a caller can handle every change kind in a `when` and have the compiler tell them when
 * a new kind arrives. That matters more than usual here: the next slice decides which changes are
 * lossy enough to quarantine data over, and a change kind nobody noticed would quietly be treated
 * as harmless.
 *
 * The kinds don't overlap. A given difference is reported exactly once, by whichever kind describes
 * it most precisely — a property that keeps its name but changes shape is a
 * [PropertySignatureChanged], never an add plus a remove.
 */
sealed interface MetamodelChange {

    /**
     * An entity type present in the newer schema but absent from the older one.
     *
     * @property typeName The name of the added entity type.
     */
    data class EntityTypeAdded(val typeName: String) : MetamodelChange

    /**
     * An entity type present in the older schema but gone from the newer one. Propositions whose
     * entity mentions reference it are the obvious quarantine candidates later.
     *
     * @property typeName The name of the removed entity type.
     */
    data class EntityTypeRemoved(val typeName: String) : MetamodelChange

    /**
     * An entity type that exists in both versions but gained or lost labels or whole properties.
     * One entry carries both deltas for the type; at least one of the four sets is non-empty.
     *
     * Properties are matched by **name**. A property that appears or disappears lands here, with
     * its full signature so a reader can see what was gained or lost. A property whose name
     * survives but whose shape moved is a [PropertySignatureChanged] instead — it isn't a removal
     * and an addition, and reporting it as one would lose the before/after pairing that makes the
     * change readable.
     *
     * The one exception is a type that declares the same property name more than once with
     * different signatures, which a `DataDictionary` permits when two same-named domain types are
     * merged into one stamp. There is no single before and after to pair up, so the differing
     * signatures are reported here as added and removed instead. Rare, and better than inventing a
     * pairing.
     *
     * @property typeName The entity type name (unchanged).
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
     * A property that kept its name on a type present in both versions, but changed shape: its
     * value type narrowed or widened, its cardinality moved, or it turned from a plain value into a
     * reference to another type (or back).
     *
     * This is the change the name-only taxonomy used to miss entirely. Turning `age` from a string
     * into an integer, or a single `worksAt` into a list of them, is a real change to what the
     * graph can hold, and data extracted under the old shape may no longer fit the new one — but
     * with only names on both sides, nothing moved and nothing was reported.
     *
     * Whether a particular move is *lossy* is deliberately not decided here. A diff states what
     * changed; deciding that string→integer strands existing values while integer→string doesn't is
     * a policy question, and it belongs to the quarantine slice that comes next.
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
 * Both sides are declarations — two things somebody decided — so the comparison is symmetric and
 * every difference is a change. Comparing a declaration against a live graph is a different
 * question with a different answer shape; that's [DeclaredObservedDiff].
 *
 * A diff is **empty** when the two schemas are structurally equivalent. That is exactly the
 * condition [MetamodelVersion.hasSameContentAs] reports, and the two agree by construction: the
 * differ walks the same fields the content hash is built from.
 *
 * @property fromVersion The baseline (older) version.
 * @property toVersion The target (newer) version.
 * @property changes Every change, in a deterministic order: entity-type changes first (by type
 *   name), then relationship changes. The same pair of versions always produces the same list.
 */
class MetamodelDiff(
    val fromVersion: MetamodelVersion,
    val toVersion: MetamodelVersion,
    changes: List<MetamodelChange>,
) {

    /**
     * Copied into a genuinely immutable list. A diff is a result, and results don't change: a Java
     * caller doing `getChanges().clear()`, or the differ still holding the builder list, must not be
     * able to reshape one after the fact. Kotlin's `List` is a compile-time promise only, so this
     * has to refuse at runtime. Plain class rather than a `data class` for the same reason — a
     * generated `copy()` would hand its argument straight to the field and skip the copying.
     */
    val changes: List<MetamodelChange> = immutableCopy(changes)

    /** `true` when nothing changed. */
    val isEmpty: Boolean get() = changes.isEmpty()

    /** Names from every [MetamodelChange.EntityTypeRemoved] — the quarantine candidates. */
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
     * Every entity type this diff says something about — added, removed, modified, or holding a
     * property whose signature moved. A reshaped type shows up in both [modifiedEntityTypes] and
     * [propertySignatureChanges], so answering "did anything about `Person` change?" otherwise
     * means checking several lists and forgetting one.
     */
    val touchedEntityTypes: Set<String>
        get() = changes.mapNotNullTo(mutableSetOf()) { change ->
            when (change) {
                is MetamodelChange.EntityTypeAdded -> change.typeName
                is MetamodelChange.EntityTypeRemoved -> change.typeName
                is MetamodelChange.EntityTypeModified -> change.typeName
                is MetamodelChange.PropertySignatureChanged -> change.typeName
                is MetamodelChange.RelationshipAdded -> null
                is MetamodelChange.RelationshipRemoved -> null
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
 * A different question from [MetamodelDiff], which compares two declarations to each other. Here
 * one side is what was decided and the other is a snapshot of reality, and the two can legitimately
 * disagree in either direction — so the result isn't a symmetric change list, it's two clearly
 * separated buckets:
 *
 * - **Drift** ([driftedEntityTypes] / [driftedRelationshipTypes]): observed in the graph, never
 *   declared. This is the actionable case. Concretely it means data is sitting in the graph whose
 *   declaring integration has since been removed, or never registered one, so nothing here can tell
 *   that data apart as valid or explain its shape.
 * - **Unobserved** ([unobservedEntityTypes] / [unobservedRelationshipTypes]): declared, but with
 *   zero instances in the graph right now. Purely informational — a declared type with no data yet
 *   is a completely normal state, not drift.
 *
 * **Names only, both ways.** The declaration knows each property's kind, type and cardinality; the
 * graph doesn't, and can't be asked. So this comparison stops at type and relationship names, and
 * says nothing about whether a declared property's shape matches what the graph stores. Pretending
 * otherwise would mean sampling nodes and calling the sample a schema. Property signatures are
 * compared where both sides genuinely have them: declared against declared, in [MetamodelDiff].
 *
 * **A declared label counts as declared.** What a graph reports is labels, and a type usually
 * carries more than one: declaring `Person` with parent `Agent` puts both labels on every `Person`
 * node. So the declared side of the drift comparison is every entity type name *plus* every label
 * those types declare — otherwise an inherited label would be reported as undeclared drift on a
 * schema nobody had changed. Going the other way, [unobservedEntityTypes] stays on the type names
 * alone: "declared but with no data" is a statement about types, and listing a parent label as an
 * unobserved type would be noise about something that was never a type in its own right.
 *
 * @property declared The schema as declared at snapshot time, stamp and bare relationship names.
 * @property observedSchema What the live graph actually held at snapshot time.
 * @property driftedEntityTypes Observed labels matching neither a declared type name nor a declared
 *   label.
 * @property driftedRelationshipTypes Relationship type names observed with no matching declaration.
 * @property unobservedEntityTypes Declared entity type names with no observed instances.
 * @property unobservedRelationshipTypes Declared relationship type names with no observed instances.
 */
class DeclaredObservedDiff(
    val declared: DeclaredSchema,
    val observedSchema: ObservedSchema,
    driftedEntityTypes: Set<String>,
    driftedRelationshipTypes: Set<String>,
    unobservedEntityTypes: Set<String>,
    unobservedRelationshipTypes: Set<String>,
) {

    // Copied into genuinely immutable sets, and a plain class rather than a `data class`, for the
    // same reason as MetamodelDiff: a result must not be reshapeable after the fact, and a
    // generated copy() would hand its arguments straight to the fields and skip the copying.

    val driftedEntityTypes: Set<String> = immutableCopy(driftedEntityTypes)

    val driftedRelationshipTypes: Set<String> = immutableCopy(driftedRelationshipTypes)

    val unobservedEntityTypes: Set<String> = immutableCopy(unobservedEntityTypes)

    val unobservedRelationshipTypes: Set<String> = immutableCopy(unobservedRelationshipTypes)

    /** The stamp inside [declared] — its hash is what a drift report would record. */
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
