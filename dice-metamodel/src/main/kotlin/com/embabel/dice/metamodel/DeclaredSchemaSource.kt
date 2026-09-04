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

import com.embabel.agent.core.DataDictionary
import org.jetbrains.annotations.ApiStatus

/**
 * The schema as declared: the stamped [version] plus the bare relationship type names it allows,
 * and the entity type and relationship names the host declared but chose to leave outside
 * governance.
 *
 * The bare names travel alongside the stamp rather than being recovered from it, because
 * [MetamodelVersion.relationshipNames] holds rendered `From-[name]->To` descriptors and
 * reverse-parsing one is ambiguous: these names come from free-text and LLM extraction, and can
 * themselves contain a `-[...]->`-shaped substring. Whoever builds a declaration holds the
 * un-rendered names before anything is stamped, so it passes them straight through. Comparing
 * declared relationships against what a graph holds needs those bare names, and that comparison is
 * the next slice.
 *
 * An ungoverned type is still a known one: the host's dictionary named it, and a
 * [GovernedTypeSelector] simply chose not to version it. That is a different thing from a type
 * nobody ever declared, and a drift check needs to tell the two apart — see
 * [ungovernedEntityTypeNames].
 *
 * @property version The stamped declared schema.
 * @property relationshipTypeNames The bare relationship type names [version] allows.
 * @property ungovernedEntityTypeNames Entity type names the host's dictionary declares, left out of
 *   [version] because the selector doesn't govern them. Still known types.
 * @property ungovernedRelationshipTypeNames Relationship type names an ungoverned type declares,
 *   left out of [relationshipTypeNames] the same way.
 */
@ApiStatus.Experimental
class DeclaredSchema(
    val version: MetamodelVersion,
    relationshipTypeNames: Set<String>,
    ungovernedEntityTypeNames: Set<String> = emptySet(),
    ungovernedRelationshipTypeNames: Set<String> = emptySet(),
) {

    /** Copied into a JVM-immutable set so the declaration can't drift from its stamped [version]. */
    val relationshipTypeNames: Set<String> = java.util.Set.copyOf(relationshipTypeNames)

    val ungovernedEntityTypeNames: Set<String> = java.util.Set.copyOf(ungovernedEntityTypeNames)

    val ungovernedRelationshipTypeNames: Set<String> = java.util.Set.copyOf(ungovernedRelationshipTypeNames)

    /**
     * The simple labels the declared entity types carry into a graph: each name in
     * [MetamodelVersion.entityTypeNames] cut down to the part after its final dot.
     *
     * A declared name can be fully qualified. A JVM-backed type is named by its class name, so the
     * stamp holds `com.example.Person`, while extraction records a mention of it as `Person` and a
     * graph reports `Person` as the label on the node. A comparison that put those two spellings
     * side by side would call every declared type unobserved and read a same-named observed type as
     * drift, so the comparison runs on these labels.
     *
     * The cut is textual: it takes the name apart at its last dot and changes nothing else. Two
     * spellings sit outside it — a JVM nested class carries its outer class in the class name after
     * a `$`, and the agent platform uppercases the first character of a label it derives this way,
     * where this keeps every character as declared. A host that meets either one maps its own
     * names; the `TypeIdentity` SPI in this package specifies that mapping.
     *
     * Two declared names differing only in their package share one label, and this set holds it
     * once. A graph does the same: a label carries no package, so nothing reading one back can tell
     * those two types apart.
     *
     * Derived from [version], so [equals] and [toString] stay on the fields a caller handed in.
     */
    val entityTypeOwnLabels: Set<String> =
        java.util.Set.copyOf(version.entityTypeNames.mapTo(mutableSetOf()) { ownLabelOf(it) })

    override fun equals(other: Any?): Boolean =
        other is DeclaredSchema &&
            version == other.version &&
            relationshipTypeNames == other.relationshipTypeNames &&
            ungovernedEntityTypeNames == other.ungovernedEntityTypeNames &&
            ungovernedRelationshipTypeNames == other.ungovernedRelationshipTypeNames

    override fun hashCode(): Int = java.util.Objects.hash(
        version,
        relationshipTypeNames,
        ungovernedEntityTypeNames,
        ungovernedRelationshipTypeNames,
    )

    override fun toString(): String =
        "DeclaredSchema(version=$version, relationshipTypeNames=$relationshipTypeNames, " +
            "ungovernedEntityTypeNames=$ungovernedEntityTypeNames, " +
            "ungovernedRelationshipTypeNames=$ungovernedRelationshipTypeNames)"

    companion object {

        /**
         * The label a declared entity type name writes onto a node: the part after its final dot,
         * or the whole name when it holds no dot.
         *
         * This is the cut the agent platform makes when it turns a type name into a label, so a
         * stamp holding `com.example.Person` lines up with the `Person` a graph reports. A name
         * ending in a dot has nothing after it and stands as its own label; that spelling is
         * malformed, and folding it into an empty label would quietly match every other malformed
         * name.
         *
         * @param entityTypeName A declared entity type name.
         * @return Its own label.
         */
        @JvmStatic
        fun ownLabelOf(entityTypeName: String): String =
            entityTypeName.substringAfterLast('.').ifEmpty { entityTypeName }

        /**
         * Declare the governed part of [dataDictionary]: stamp it and carry through the bare
         * relationship names the same governed types declare.
         *
         * Use this rather than building the two halves separately. Picking a governed subset for
         * the stamp while taking relationship names from the whole dictionary would declare
         * relationships the stamp never covered, and the mismatch would only surface much later,
         * as phantom disagreement in a drift check.
         *
         * @param dataDictionary The schema to declare.
         * @param selector Which types are under governance. Defaults to all of them.
         * @return The declaration.
         */
        @JvmStatic
        @JvmOverloads
        fun from(
            dataDictionary: DataDictionary,
            selector: GovernedTypeSelector = GovernedTypeSelector.ALL,
        ): DeclaredSchema = from(dataDictionary, selector, SchemaAliases.NONE)

        /**
         * Declare the governed part of [dataDictionary], carrying the former names [aliases]
         * declares for its types and properties.
         *
         * This is how a rename gets recorded as one. The stamp carries the former names, and a
         * later comparison pairs the old name with the new one rather than reading the change as a
         * type or property disappearing.
         *
         * [aliases] has no default, and the shorter form above stays a separate function. Adding a
         * third defaulted parameter to it would replace its synthetic `from$default` descriptor
         * with a wider one, which is a link error for any caller already compiled against it.
         *
         * @param dataDictionary The schema to declare.
         * @param selector Which types are under governance. [GovernedTypeSelector.ALL] governs all
         *   of them.
         * @param aliases Former names for the schema's types and properties. [SchemaAliases.NONE]
         *   declares none. Experimental: shape may change before 1.0.
         * @return The declaration.
         * @throws IllegalArgumentException when a declared type name appears in another type's
         *   alias set, or when aliases are declared for a property name the governed types hold
         *   more than one signature for.
         */
        @JvmStatic
        fun from(
            dataDictionary: DataDictionary,
            selector: GovernedTypeSelector,
            aliases: SchemaAliases,
        ): DeclaredSchema = DeclaredSchema(
            version = MetamodelVersion.from(dataDictionary, selector, aliases),
            relationshipTypeNames = MetamodelVersion.governedRelationshipTypeNames(dataDictionary, selector),
            ungovernedEntityTypeNames = MetamodelVersion.ungovernedEntityTypeNames(dataDictionary, selector),
            ungovernedRelationshipTypeNames =
                MetamodelVersion.ungovernedRelationshipTypeNames(dataDictionary, selector),
        )
    }
}

/**
 * Supplies the schema an application has declared.
 *
 * This interface is the versioning opt-in: with no declared schema, nothing is stamped. The Spring
 * wiring that arrives in a later slice activates only when a `DeclaredSchemaSource` bean is
 * present, so an application that hasn't decided what it governs is left alone.
 *
 * A declared schema can come from anywhere. A consuming app implements this over whatever it
 * already uses to define its types (a `DataDictionary`, a config file, a registry...) and wires it
 * as a bean. There is no default implementation, because there is no default declared schema.
 */
@ApiStatus.Experimental
fun interface DeclaredSchemaSource {

    /**
     * @return the current [DeclaredSchema].
     */
    fun declare(): DeclaredSchema
}
