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
 * Scopes the metamodel builders so a nested block can't reach the enclosing one by accident. Writing
 * `governedBy(...)` inside an `aliases { }` block is a compile error, not a silent surprise.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class MetamodelDsl

/** Runs [block] against a fresh builder and hands back the immutable stamping it collected. */
internal fun stampingOf(
    dataDictionary: DataDictionary,
    block: MetamodelStampingBuilder.() -> Unit,
): MetamodelStamping = MetamodelStampingBuilder(dataDictionary).apply(block).build()

/**
 * Collects what to stamp while a `MetamodelVersion(dictionary) { }` or
 * `DeclaredSchema(dictionary) { }` block runs.
 *
 * ```kotlin
 * val version = MetamodelVersion(dictionary) {
 *     governedBy("Person", "Company")
 *     aliases {
 *         type("Organisation", formerly = setOf("Company"))
 *         property("Person", "emailAddress", formerly = setOf("email"))
 *     }
 * }
 * ```
 *
 * The block is a plain sequence of statements against this builder, so nothing needs a receiver
 * prefix or a chain. An empty block governs everything and declares no former names, which is the
 * same stamp [MetamodelVersion.from] gives.
 *
 * Mutable, and scoped to the block that configures it: it is built into an immutable
 * [MetamodelStamping] the moment the block returns, and changing it afterwards affects nothing. The
 * constructor is internal, so the only way to hold one is inside a block.
 *
 * Later calls win. `governedBy` twice governs whatever the second call said.
 *
 * Java callers want [MetamodelVersion.stamping], which takes the same three inputs as a chain.
 *
 * EXPERIMENTAL. The shape may still change before 1.0.
 */
@MetamodelDsl
@ApiStatus.Experimental
class MetamodelStampingBuilder internal constructor(
    private val dataDictionary: DataDictionary,
) {

    private var governedTypes: GovernedTypeSelector = GovernedTypeSelector.ALL

    private var aliases: SchemaAliases = SchemaAliases.NONE

    /**
     * Govern the types [selector] picks out.
     *
     * @param selector The predicate deciding which types the stamp covers.
     */
    fun governedBy(selector: GovernedTypeSelector) {
        governedTypes = selector
    }

    /**
     * Govern the types named in [typeNames], matched against `DomainType.name`. A name no type
     * carries governs nothing and is not an error.
     *
     * @param typeNames The names of the types to govern.
     */
    fun governedBy(typeNames: Set<String>) {
        governedTypes = GovernedTypeSelector { it.name in typeNames }
    }

    /**
     * Govern the types named, for the common case of writing them out at the call site.
     *
     * @param typeNames The names of the types to govern.
     */
    fun governedBy(vararg typeNames: String) {
        governedBy(typeNames.toSet())
    }

    /**
     * Carry former names already held as a [SchemaAliases].
     *
     * @param aliases Former names for the schema's types and properties.
     */
    fun aliases(aliases: SchemaAliases) {
        this.aliases = aliases
    }

    /**
     * Declare former names inline:
     *
     * ```kotlin
     * aliases {
     *     type("Organisation", formerly = setOf("Company"))
     *     property("Person", "emailAddress", formerly = setOf("email"))
     * }
     * ```
     *
     * Replaces whatever aliases were set before it, so a block and a [SchemaAliases] don't merge.
     *
     * @param block Applied to the alias builder.
     */
    fun aliases(block: SchemaAliasesBuilder.() -> Unit) {
        aliases = SchemaAliasesBuilder().apply(block).build()
    }

    internal fun build(): MetamodelStamping =
        MetamodelStamping(dataDictionary, governedTypes, aliases)
}

/**
 * Collects declared renames while an `aliases { }` block runs.
 *
 * Names accumulate per type and per property, so declaring the same one twice adds to it rather
 * than replacing it. A type renamed `A` to `B` to `C` therefore declares both older names, which is
 * what lets a comparison across non-adjacent stamps still pair them.
 *
 * EXPERIMENTAL. The shape may still change before 1.0.
 */
@MetamodelDsl
@ApiStatus.Experimental
class SchemaAliasesBuilder internal constructor() {

    private val typeAliases = mutableMapOf<String, MutableSet<String>>()

    private val propertyAliases = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()

    /**
     * Declare the names an entity type used to go by.
     *
     * @param typeName The type's current name.
     * @param formerly The names it used to have. Exact and case-sensitive.
     */
    fun type(typeName: String, formerly: Set<String>) {
        typeAliases.getOrPut(typeName) { mutableSetOf() } += formerly
    }

    /**
     * Declare the names a property on a type used to go by.
     *
     * @param typeName The current name of the type holding the property.
     * @param propertyName The property's current name.
     * @param formerly The names it used to have. Exact and case-sensitive.
     */
    fun property(typeName: String, propertyName: String, formerly: Set<String>) {
        propertyAliases
            .getOrPut(typeName) { mutableMapOf() }
            .getOrPut(propertyName) { mutableSetOf() } += formerly
    }

    internal fun build(): SchemaAliases = SchemaAliases(
        typeAliases = typeAliases.mapValues { (_, names) -> names.toSet() },
        propertyAliases = propertyAliases.mapValues { (_, byProperty) ->
            byProperty.mapValues { (_, names) -> names.toSet() }
        },
    )
}
