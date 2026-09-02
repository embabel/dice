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
 * How a host says which declared entity type an outside name means.
 *
 * A DICE schema is declared in the agent platform's terms, and a declared name is often a JVM class
 * name: `com.example.Person`. The names that come back at it are spelled by whatever produced them.
 * A graph reports the label `Person`. Extraction records a mention type of `Person`. A TypeScript
 * API calls it `PersonDTO`, and an OpenAPI document calls the same thing `PersonRecord`. Every one
 * of those has to land on the declared type before a drift check, a quarantine decision or a
 * provenance record can mean anything.
 *
 * The built-in answer covers the graph, and only the graph:
 * [DeclaredSchema.entityTypeOwnLabels] cuts a declared name down at its last dot, which is how a
 * label is derived, and the shipped differ compares on that. A name system whose spelling
 * is anything else needs a host to say what maps to what, and this interface is the shape of that
 * statement.
 *
 * ## Contract
 *
 * - **Total.** Any string can arrive, including one the host has never heard of.
 * [declaredNameFor] answers `null` for a name this mapping claims nothing about, and
 * [externalNamesFor] answers an empty set. An unknown name is an ordinary answer; throwing would
 * turn a drift check into an outage.
 * - **Deterministic and free of side effects.** A drift check calls this once per observed name on
 * every pass, so an implementation reads a table it already holds and touches no network.
 * - **Round trip.** When `declaredNameFor(x)` answers `d`, then `x` is in `externalNamesFor(d)`.
 * The two directions answer the same drift check: one decides whether an observed name is
 * declared, the other decides whether a declared type was seen. A mapping that accepted a name one
 * way and dropped it the other would report one type as both undeclared and missing.
 * - **Many to one.** Several outside names may map to one declared type — a DTO, a record and a
 * label for the same thing. One outside name maps to at most one declared type: a name that could
 * mean two declared types identifies nothing, and the host resolves it before answering.
 * - **Exact strings.** Names are compared character for character. A mapping that wants case
 * folding or trimming does it inside its own answers.
 * - **Declared names only.** What [declaredNameFor] returns is a name that appears in
 * [MetamodelVersion.entityTypeNames], spelled the way the stamp spells it. A mapping that answers
 * something else names a type nobody declared, which reads downstream as the drift it was meant to
 * explain.
 *
 * ## Example
 *
 * A host serves an OpenAPI-described API whose schema names differ from its JVM class names, and
 * maps the two:
 *
 * ```kotlin
 * class OpenApiTypeIdentity(private val schemaNames: Map<String, String>) : TypeIdentity {
 *
 *     override val typeSystem: String = "openapi"
 *
 *     override fun declaredNameFor(externalName: String): String? = schemaNames[externalName]
 *
 *     override fun externalNamesFor(declaredTypeName: String): Set<String> =
 *         schemaNames.filterValues { it == declaredTypeName }.keys
 * }
 *
 * val identity = OpenApiTypeIdentity(
 *     mapOf(
 *         "PersonRecord" to "com.example.Person",
 *         "PersonSummary" to "com.example.Person",
 *     ),
 * )
 *
 * identity.declaredNameFor("PersonRecord")  // "com.example.Person"
 * identity.declaredNameFor("Widget")        // null: nothing here claims it
 * identity.externalNamesFor("com.example.Person")  // ["PersonRecord", "PersonSummary"]
 * ```
 *
 * ## Status
 *
 * A specification, and nothing more. Nothing in DICE implements it, calls it or wires it: this file
 * holds the contract so the host mapping has a written shape to be built against, and the slice
 * that reads it lands separately. Experimental: shape may change before 1.0.
 */
@ApiStatus.Experimental
interface TypeIdentity {

    /**
     * Which name system this mapping speaks for, as a short stable id: `openapi`, `typescript`,
     * `graph-label`. Free-form, and used to say where a name came from when several mappings are in
     * play at once.
     */
    val typeSystem: String

    /**
     * The declared entity type an outside name means.
     *
     * @param externalName A type name as [typeSystem] spells it.
     * @return The declared entity type name, or `null` when this mapping claims nothing about
     *   [externalName].
     */
    fun declaredNameFor(externalName: String): String?

    /**
     * Every name a declared entity type can arrive under in [typeSystem].
     *
     * @param declaredTypeName A name from [MetamodelVersion.entityTypeNames].
     * @return The outside names that map to it, empty when this mapping knows the type by no name
     *   of its own.
     */
    fun externalNamesFor(declaredTypeName: String): Set<String>
}
