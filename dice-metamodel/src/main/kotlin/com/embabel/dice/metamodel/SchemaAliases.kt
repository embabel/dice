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

/**
 * The former names a schema's types and properties have gone by, declared alongside the schema
 * itself.
 *
 * A rename with no alias behind it looks like a removal and an addition, which reads as loss. An
 * alias tells the comparison that the two names are the same thing, so the rename pairs up.
 * Iceberg and Delta get this from stable field ids assigned when a column is created; DICE
 * extracts its schema from LLM output and has no id to assign, so the declaration carries the old
 * name instead.
 *
 * Names are exact and case-sensitive. LLM extraction drifts on case, and treating `worksAt` and
 * `worksat` as the same name here would quietly pair two properties an operator never said were
 * the same one.
 *
 * Aliases accumulate. A type renamed `A` to `B` to `C` declares `{A, B}`, so a comparison across
 * non-adjacent stamps still pairs. Retiring a name means deleting it from the declaration.
 *
 * Empty alias sets are dropped on the way in: declaring no former names for a type means the same
 * thing as saying nothing about it, and an empty entry would otherwise change the content hash
 * while meaning nothing.
 *
 * Experimental: shape may change before 1.0.
 *
 * @property typeAliases Current entity type name to the names it used to have.
 * @property propertyAliases Entity type name, then current property name, to the names that
 *   property used to have.
 */
@ApiStatus.Experimental
class SchemaAliases @JvmOverloads constructor(
    typeAliases: Map<String, Set<String>> = emptyMap(),
    propertyAliases: Map<String, Map<String, Set<String>>> = emptyMap(),
) {

    val typeAliases: Map<String, Set<String>> = copyDroppingEmpty(typeAliases)

    val propertyAliases: Map<String, Map<String, Set<String>>> = java.util.Map.copyOf(
        propertyAliases
            .mapValues { (_, byProperty) -> copyDroppingEmpty(byProperty) }
            .filterValues { it.isNotEmpty() },
    )

    /** The former names declared for [propertyName] on [typeName], empty when none are. */
    fun propertyAliasesFor(typeName: String, propertyName: String): Set<String> =
        propertyAliases[typeName]?.get(propertyName).orEmpty()

    override fun equals(other: Any?): Boolean =
        other is SchemaAliases &&
            typeAliases == other.typeAliases &&
            propertyAliases == other.propertyAliases

    override fun hashCode(): Int = 31 * typeAliases.hashCode() + propertyAliases.hashCode()

    override fun toString(): String =
        "SchemaAliases(typeAliases=$typeAliases, propertyAliases=$propertyAliases)"

    companion object {

        /** No former names declared anywhere. What the stamping entry points use by default. */
        @JvmField
        val NONE: SchemaAliases = SchemaAliases()

        /**
         * Copy into a JVM-immutable map of JVM-immutable sets, leaving out keys whose alias set is
         * empty.
         */
        private fun copyDroppingEmpty(values: Map<String, Set<String>>): Map<String, Set<String>> =
            java.util.Map.copyOf(
                values
                    .filterValues { it.isNotEmpty() }
                    .mapValues { (_, aliases) -> java.util.Set.copyOf(aliases) },
            )
    }
}
