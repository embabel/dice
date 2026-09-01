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
 * definitions.
 *
 * These pin the derivation itself. `DrivineObservedSchemaSourceIntegrationTest` pins the thing that
 * matters downstream — nodes dice really wrote stay out of an observation, and domain nodes sharing
 * a label stay in it — which is what would catch a shape that drifted away from the writers.
 */
class DiceOwnedSchemaTest {

    @Test
    fun `a source's shape holds every property dice writes on one`() {
        // The reviewer's case in one line: `key` alone is dice's uniqueness key, and a host is free
        // to key its own Source type the same way. `kind` is what dice also always writes.
        assertEquals(listOf("key", "kind"), DiceOwnedSchema.NODE_SHAPES["Source"])
    }

    @Test
    fun `a mention's shape is what an extractor always records`() {
        assertEquals(listOf("id", "span", "type", "role"), DiceOwnedSchema.NODE_SHAPES["Mention"])
    }

    @Test
    fun `a property dice can leave out stays out of the shape`() {
        val proposition = DiceOwnedSchema.NODE_SHAPES.getValue("Proposition")

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
            DiceOwnedSchema.NODE_SHAPES["MetamodelVersion"],
        )
        assertEquals(
            listOf("propositionId", "runId", "target"),
            DiceOwnedSchema.NODE_SHAPES["ProjectionRecord"],
        )
        assertEquals(listOf("runId"), DiceOwnedSchema.NODE_SHAPES["CollectorTraceRun"])
    }

    @Test
    fun `every label a dice store declares has a shape`() {
        // The derivation has to reach all three schema objects. A label declared by a store and
        // missing here would be reported as domain drift on every whole-graph check.
        (CollectorTraceSchema.LABELS + MetamodelSchema.LABELS + LineageSchema.LABELS).forEach { label ->
            assertTrue(
                DiceOwnedSchema.NODE_SHAPES.containsKey(label),
                "'$label' is written by a dice store and carries no ownership shape",
            )
        }
    }

    @Test
    fun `the ownership predicate asks for every property of the shape`() {
        assertEquals(
            "s.key IS NOT NULL AND s.kind IS NOT NULL",
            DiceOwnedSchema.ownedNodePredicate("s", "Source"),
        )
    }

    @Test
    fun `a label dice never writes has no ownership predicate`() {
        val thrown = assertThrows<IllegalArgumentException> { DiceOwnedSchema.ownedNodePredicate("n", "Ghost") }

        assertTrue(thrown.message!!.contains("Ghost"), "got ${thrown.message}")
    }
}
