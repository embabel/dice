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
 * What to stamp, and how, built up a step at a time.
 *
 * The three inputs a stamp needs are the same three a declaration needs: a dictionary, which of its
 * types are governed, and the former names those types go by. This carries all three and hands them
 * to whichever of the two you finish with, [stamp] or [declare]:
 *
 * ```kotlin
 * val version = MetamodelVersion.stamping(dictionary)
 *     .governedBy(setOf("Person", "Company"))
 *     .withAliases(aliases)
 *     .stamp()
 * ```
 *
 * Reads the same from Java, because every step is an ordinary method taking one argument:
 *
 * ```java
 * MetamodelVersion version = MetamodelVersion.stamping(dictionary)
 *     .governedBy(Set.of("Person", "Company"))
 *     .withAliases(aliases)
 *     .stamp();
 * ```
 *
 * Every step returns a new instance and leaves the one it was called on alone, so a half-built
 * stamping is safe to hold onto and finish more than once. That is what makes a shared base worth
 * keeping in a field:
 *
 * ```kotlin
 * val governed = MetamodelVersion.stamping(dictionary).governedBy(governedTypes)
 * val plain = governed.stamp()
 * val renamed = governed.withAliases(aliases).stamp()
 * ```
 *
 * Nothing is validated here. The rules about aliases live where the stamp is built, so a bad
 * declaration fails at [stamp] or [declare] with the same message it would have given the
 * three-argument factory.
 *
 * Equality is the data-class default, which compares the three fields. [governedTypes] is usually a
 * lambda, and lambdas compare by identity, so two stampings built the same way from two separate
 * lambdas are not equal. Compare what they produce when that matters.
 *
 * EXPERIMENTAL. The shape may still change before 1.0.
 *
 * @property dataDictionary The schema to stamp or declare.
 * @property governedTypes Which of its types are under governance.
 * @property aliases Former names for those types and their properties.
 */
@ApiStatus.Experimental
data class MetamodelStamping @JvmOverloads constructor(
    val dataDictionary: DataDictionary,
    val governedTypes: GovernedTypeSelector = GovernedTypeSelector.ALL,
    val aliases: SchemaAliases = SchemaAliases.NONE,
) {

    /**
     * Govern the types [selector] picks out.
     *
     * @param selector The predicate deciding which types the stamp covers.
     * @return A new stamping governed by [selector].
     */
    fun governedBy(selector: GovernedTypeSelector): MetamodelStamping =
        copy(governedTypes = selector)

    /**
     * Govern the types named in [typeNames], which is the common case: a set of names the
     * application already holds.
     *
     * The names are matched against `DomainType.name`. A name no type in the dictionary carries
     * governs nothing and is not an error, the same as any other selector that matches nothing.
     *
     * @param typeNames The names of the types to govern.
     * @return A new stamping governed by those names.
     */
    fun governedBy(typeNames: Set<String>): MetamodelStamping =
        copy(governedTypes = GovernedTypeSelector { it.name in typeNames })

    /**
     * Carry the former names [aliases] declares.
     *
     * @param aliases Former names for the schema's types and properties.
     * @return A new stamping carrying those aliases.
     */
    fun withAliases(aliases: SchemaAliases): MetamodelStamping =
        copy(aliases = aliases)

    /**
     * @return The version stamp, the same one [MetamodelVersion.from] builds from these three
     *   arguments.
     * @throws IllegalArgumentException when the aliases are not declarable against the governed
     *   types.
     */
    fun stamp(): MetamodelVersion =
        MetamodelVersion.from(dataDictionary, governedTypes, aliases)

    /**
     * Finish as a declaration, which is the stamp plus the relationship names the same governed
     * types declare.
     *
     * @return The declaration, the same one [DeclaredSchema.from] builds from these three
     *   arguments.
     * @throws IllegalArgumentException when the aliases are not declarable against the governed
     *   types.
     */
    fun declare(): DeclaredSchema =
        DeclaredSchema.from(dataDictionary, governedTypes, aliases)
}
