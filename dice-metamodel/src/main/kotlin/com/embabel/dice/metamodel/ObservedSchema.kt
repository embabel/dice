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

import java.time.Instant
import java.util.Objects

/**
 * A snapshot of what a live graph holds, where a [DeclaredSchema] says what it should hold.
 *
 * Names only. A graph can report which labels and relationship types exist in it. It cannot report
 * what a property was *declared* to be: two nodes with the same label can carry different property
 * sets, a property can be absent on most of them, and a value that looks like an integer today may
 * be a string tomorrow. Anything richer than a name would be a sample of the data rather than the
 * schema. The declared side keeps full [PropertySignature]s, the observed side stays at names, and
 * [DeclaredObservedDiff] states that asymmetry.
 *
 * This is a plain value type; nothing in this module knows how to read a graph. A storage layer
 * builds one by querying its live database for distinct labels and relationship types, then hands
 * it here to be compared. Schemas in the wild are often partly dynamic, with integrations
 * contributing types that come and go, so what is in the graph can drift from what was declared,
 * and this module needs a way to talk about that without depending on a particular graph driver.
 *
 * @property entityTypeNames Entity type names observed. What counts as a name depends on
 *   [entityTypeBasis]: a Neo4j graph label, or a mention's own `type` field.
 * @property relationshipTypeNames Relationship type names observed in the graph.
 * @property capturedAt When this snapshot was taken.
 * @property entityTypeBasis What kind of name [entityTypeNames] holds, which decides what the
 *   declared side of a comparison has to be. See [EntityTypeBasis].
 */
@ApiStatus.Experimental
class ObservedSchema @JvmOverloads constructor(
    entityTypeNames: Set<String>,
    relationshipTypeNames: Set<String>,
    val capturedAt: Instant,
    val entityTypeBasis: EntityTypeBasis = EntityTypeBasis.GRAPH_LABELS,
) {

    /**
     * What kind of name [ObservedSchema.entityTypeNames] holds. A [DeclaredObservedDiffer] needs to
     * know this, because the two kinds of name compare against different declared sets.
     */
    enum class EntityTypeBasis {

        /**
         * A Neo4j label, read off `db.labels()` on a whole-graph observation. A node carries every
         * label in its declared type's hierarchy, so declaring `Person` with parent `Agent` puts both
         * labels on every `Person` node. The declared side of a comparison against this basis has to
         * include every label the declaration's types carry, on top of the type names themselves, or
         * an inherited parent label reads as undeclared drift on a schema nobody touched.
         */
        GRAPH_LABELS,

        /**
         * The `type` a mention was extracted as, read off `Mention.type` on a context-scoped
         * observation. This is domain data an extractor wrote, living in its own namespace apart from graph labels, and a graph's
         * label hierarchy has no bearing on it: a mention typed `Agent` claimed to be an `Agent`, and
         * that claim stands or falls on whether `Agent` is itself a declared type, whatever labels a
         * governed `Person` happens to carry. The declared side of a comparison against this basis
         * stays on declared type names (plus their declared former names — old data can still carry a
         * type's pre-rename spelling); it must not widen to include inherited labels, or an undeclared
         * mention type escapes detection by riding a governed type's parent label.
         */
        MENTION_TYPES,
    }

    // Both sets are copied into immutable ones, keeping the order they arrived in. A snapshot
    // describes one moment and must not change afterwards, and a backend typically builds these from
    // a mutable set it fills as it walks query results. Kotlin's read-only `Set` is a compile-time
    // promise only; a Java caller sees straight through it. Plain class rather than a `data class`
    // because a generated `copy()` would skip the copying.

    val entityTypeNames: Set<String> = immutableCopy(entityTypeNames)

    val relationshipTypeNames: Set<String> = immutableCopy(relationshipTypeNames)

    override fun equals(other: Any?): Boolean =
        other is ObservedSchema &&
            entityTypeNames == other.entityTypeNames &&
            relationshipTypeNames == other.relationshipTypeNames &&
            capturedAt == other.capturedAt &&
            entityTypeBasis == other.entityTypeBasis

    override fun hashCode(): Int = Objects.hash(entityTypeNames, relationshipTypeNames, capturedAt, entityTypeBasis)

    override fun toString(): String =
        "ObservedSchema(entityTypeNames=$entityTypeNames, relationshipTypeNames=$relationshipTypeNames, " +
            "capturedAt=$capturedAt, entityTypeBasis=$entityTypeBasis)"

    private companion object {

        private fun <T> immutableCopy(values: Set<T>): Set<T> =
            java.util.Collections.unmodifiableSet(LinkedHashSet(values))
    }
}
