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
package com.embabel.dice.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [DiceOwnedSchema]: no database, just the shapes it reads out of the storage
 * definitions it was given.
 *
 * These pin the derivation itself. `DrivineObservedSchemaSourceIntegrationTest` pins the thing that
 * matters downstream — nodes dice really wrote stay out of an observation, and domain nodes sharing
 * a label stay in it — which is what would catch a shape that drifted away from the writers.
 * `DiceStorageSchemaRegistrationTest` pins that every dice store on the classpath is in the list an
 * application passes to [DiceOwnedSchema.of].
 */
class DiceOwnedSchemaTest {

    /** The registered set a graph-backed application wiring every dice store would hand over. */
    private val owned = DiceOwnedSchema.of(listOf(MetamodelSchema, CollectorTraceSchema, LineageSchema))

    @Test
    fun `a source's shape holds every property dice writes on one`() {
        // The reviewer's case in one line: `key` alone is dice's uniqueness key, and a host is free
        // to key its own Source type the same way. `kind` is what dice also always writes.
        assertEquals(listOf("key", "kind"), owned.nodeShapes["Source"])
    }

    @Test
    fun `a mention's shape is what an extractor always records`() {
        assertEquals(listOf("id", "span", "type", "role"), owned.nodeShapes["Mention"])
    }

    @Test
    fun `a property dice can leave out stays out of the shape`() {
        val proposition = owned.nodeShapes.getValue("Proposition")

        assertEquals(listOf("id", "contextId", "text", "confidence", "created"), proposition)
        assertFalse(proposition.contains("status"), "status carries a default, so an older node can lack it")
        assertFalse(proposition.contains("embedding"), "embedding is nullable")
        assertFalse(proposition.contains("metadata"), "the property bag is spread over metadata.<key>")
        assertFalse(proposition.contains("grounding"), "an empty list leaves no property behind")
    }

    @Test
    fun `a Cypher-backed store's shape is every property its keys name`() {
        // These stores MERGE on their natural keys, so a node they created carries all of them. The
        // version node takes its sequence in the same statement that creates it.
        assertEquals(
            listOf("schemaName", "contentHash", "sequence"),
            owned.nodeShapes["MetamodelVersion"],
        )
        assertEquals(
            listOf("propositionId", "runId", "target"),
            owned.nodeShapes["ProjectionRecord"],
        )
        assertEquals(listOf("runId"), owned.nodeShapes["CollectorTraceRun"])
    }

    @Test
    fun `the core proposition store is owned whatever an application registered`() {
        // The four node fragments are how the observer reads propositions and mentions at all, so
        // they are in every ownership set, including one built from no registrations.
        val bare = DiceOwnedSchema.of(emptyList())

        assertEquals(
            setOf("Proposition", "Mention", "Source", "ProcessedChunk"),
            bare.labels,
        )
        assertEquals(setOf("HAS_MENTION", "DERIVED_FROM"), bare.bookkeepingRelationshipTypes)
    }

    @Test
    fun `a store the application registered is owned, and one it did not is not`() {
        // The whole fix in one assertion pair. Ownership follows the registered list, so a schema
        // this application wired is dice's own, and the same schema left out of another
        // application's wiring stays visible to that application's observation.
        val withCollectorTrace = DiceOwnedSchema.of(listOf(CollectorTraceSchema))
        val withoutCollectorTrace = DiceOwnedSchema.of(listOf(MetamodelSchema))

        CollectorTraceSchema.LABELS.forEach { label ->
            assertTrue(
                withCollectorTrace.nodeShapes.containsKey(label),
                "'$label' was registered and carries no ownership shape",
            )
            assertFalse(
                withoutCollectorTrace.nodeShapes.containsKey(label),
                "'$label' was never registered, so this application knows nothing about it",
            )
        }
    }

    @Test
    fun `a registered store contributes its bookkeeping edges too`() {
        assertEquals(
            setOf("HAS_MENTION", "DERIVED_FROM", "SCORED", "RETIRED_IN"),
            DiceOwnedSchema.of(listOf(CollectorTraceSchema)).bookkeepingRelationshipTypes,
        )
        assertEquals(
            setOf("HAS_MENTION", "DERIVED_FROM"),
            DiceOwnedSchema.of(listOf(MetamodelSchema)).bookkeepingRelationshipTypes,
            "an unregistered trace store's edges are nothing this application can vouch for",
        )
    }

    @Test
    fun `every label a registered store declares has a shape`() {
        // A label a registered store declares and this map misses would be reported as domain drift
        // on every whole-graph check, which is the failure this derivation exists to rule out.
        (CollectorTraceSchema.LABELS + MetamodelSchema.LABELS + LineageSchema.LABELS).forEach { label ->
            assertTrue(
                owned.nodeShapes.containsKey(label),
                "'$label' is written by a registered dice store and carries no ownership shape",
            )
        }
    }

    @Test
    fun `the ownership predicate asks for every property of the shape`() {
        assertEquals(
            "s.key IS NOT NULL AND s.kind IS NOT NULL",
            owned.ownedNodePredicate("s", "Source"),
        )
    }

    @Test
    fun `a label dice never writes has no ownership predicate`() {
        val thrown = assertThrows<IllegalArgumentException> { owned.ownedNodePredicate("n", "Ghost") }

        assertTrue(thrown.message!!.contains("Ghost"), "got ${thrown.message}")
    }
}
