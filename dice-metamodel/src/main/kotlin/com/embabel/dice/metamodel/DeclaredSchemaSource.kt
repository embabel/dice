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
 * The schema as declared: the stamped [version] plus the bare relationship type names it allows.
 *
 * The bare names travel alongside the stamp rather than being recovered from it, because
 * [MetamodelVersion.relationshipNames] holds rendered `From-[name]->To` descriptors and
 * reverse-parsing one is ambiguous: these names come from free-text and LLM extraction, and can
 * themselves contain a `-[...]->`-shaped substring. Whoever builds a declaration holds the
 * un-rendered names before anything is stamped, so it passes them straight through. Comparing
 * declared relationships against what a graph holds needs those bare names, and that comparison is
 * the next slice.
 *
 * @property version The stamped declared schema.
 * @property relationshipTypeNames The bare relationship type names [version] allows.
 */
@ApiStatus.Experimental
class DeclaredSchema(
    val version: MetamodelVersion,
    relationshipTypeNames: Set<String>,
) {

    /** Copied into a JVM-immutable set so the declaration can't drift from its stamped [version]. */
    val relationshipTypeNames: Set<String> = java.util.Set.copyOf(relationshipTypeNames)

    override fun equals(other: Any?): Boolean =
        other is DeclaredSchema &&
            version == other.version &&
            relationshipTypeNames == other.relationshipTypeNames

    override fun hashCode(): Int = 31 * version.hashCode() + relationshipTypeNames.hashCode()

    override fun toString(): String =
        "DeclaredSchema(version=$version, relationshipTypeNames=$relationshipTypeNames)"

    companion object {

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
