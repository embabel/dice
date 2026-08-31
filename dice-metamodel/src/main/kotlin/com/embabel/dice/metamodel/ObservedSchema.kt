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
 * @property entityTypeNames Entity type (label) names observed in the graph.
 * @property relationshipTypeNames Relationship type names observed in the graph.
 * @property capturedAt When this snapshot was taken.
 */
class ObservedSchema(
    entityTypeNames: Set<String>,
    relationshipTypeNames: Set<String>,
    val capturedAt: Instant,
) {

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
            capturedAt == other.capturedAt

    override fun hashCode(): Int = Objects.hash(entityTypeNames, relationshipTypeNames, capturedAt)

    override fun toString(): String =
        "ObservedSchema(entityTypeNames=$entityTypeNames, relationshipTypeNames=$relationshipTypeNames, " +
            "capturedAt=$capturedAt)"

    private companion object {

        private fun <T> immutableCopy(values: Set<T>): Set<T> =
            java.util.Collections.unmodifiableSet(LinkedHashSet(values))
    }
}
