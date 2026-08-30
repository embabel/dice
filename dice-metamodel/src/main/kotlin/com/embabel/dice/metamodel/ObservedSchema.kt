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
 * A snapshot of what a live graph actually holds, as opposed to what a [DeclaredSchema] says it
 * should hold.
 *
 * Names only, and deliberately so. A graph can tell you which labels and relationship types exist
 * in it; it cannot tell you what a property was *declared* to be. Two nodes with the same label can
 * carry different property sets, a property can be absent on most of them, and a value that looks
 * like an integer today may be a string tomorrow — so anything richer than a name would be a
 * sample, not a schema, and would make a diff read as authoritative when it isn't. The declared
 * side keeps full [PropertySignature]s; the observed side stays at names, and
 * [DeclaredObservedDiff] is honest about the asymmetry.
 *
 * This is a plain value type — nothing in this module knows how to go and read a graph. A storage
 * layer (Neo4j or otherwise) builds one by querying the live database for its distinct labels and
 * relationship types, then hands it here to be compared. That split is deliberate: schemas in the
 * wild are often partly dynamic, with integrations contributing types that come and go, so what is
 * actually in the graph can drift from what was ever declared, and this module needs a way to talk
 * about that without depending on any particular graph driver.
 *
 * @property entityTypeNames Entity type (label) names actually observed in the graph.
 * @property relationshipTypeNames Relationship type names actually observed in the graph.
 * @property capturedAt When this snapshot was taken.
 */
class ObservedSchema(
    entityTypeNames: Set<String>,
    relationshipTypeNames: Set<String>,
    val capturedAt: Instant,
) {

    // Both sets are copied into genuinely immutable ones, keeping the order they arrived in. A
    // snapshot is a statement about a moment, so it must not change afterwards — and a backend
    // typically builds these from a mutable set it keeps filling as it walks query results. Kotlin's
    // read-only `Set` is a compile-time promise only; a Java caller sees straight through it. Plain
    // class rather than a `data class` because a generated `copy()` would skip the copying.

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
