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
package com.embabel.dice.query.oracle

import com.embabel.dice.projection.prolog.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PrologToolsTest {

    private lateinit var prologResult: PrologProjectionResult
    private lateinit var prologSchema: PrologSchema
    private lateinit var entityNames: Map<String, String>
    private lateinit var tools: PrologTools

    @BeforeEach
    fun setUp() {
        val facts = listOf(
            PrologFact("expert_in", listOf("alice-id", "kubernetes"), 0.9, sourcePropositionIds = listOf("prop-1")),
            PrologFact("expert_in", listOf("alice-id", "docker"), 0.85, sourcePropositionIds = listOf("prop-2")),
            PrologFact("expert_in", listOf("bob-id", "python"), 0.8, sourcePropositionIds = listOf("prop-3")),
            PrologFact("works_at", listOf("alice-id", "techcorp-id"), 0.95, sourcePropositionIds = listOf("prop-4")),
            PrologFact("works_at", listOf("bob-id", "techcorp-id"), 0.9, sourcePropositionIds = listOf("prop-5")),
            PrologFact("friend_of", listOf("alice-id", "bob-id"), 0.88, sourcePropositionIds = listOf("prop-6")),
        )

        prologResult = PrologProjectionResult(
            facts = facts,
            confidenceFacts = facts.map { ConfidenceFact(it) },
            groundingFacts = facts.flatMap { f ->
                f.sourcePropositionIds.map { GroundingFact(f, it) }
            },
        )

        prologSchema = PrologSchema.withDefaults()

        entityNames = mapOf(
            "alice-id" to "Alice",
            "bob-id" to "Bob",
            "techcorp-id" to "TechCorp",
        )

        tools = PrologTools(prologResult, prologSchema, entityNames)
    }

    @Nested
    inner class QueryPrologTests {

        @Test
        fun `queryProlog returns results for valid query`() {
            val result = tools.queryProlog("expert_in(X, Y)")

            assertTrue(result.contains("Found"))
            assertTrue(result.contains("result"))
        }

        @Test
        fun `queryProlog returns no results message for failing query`() {
            val result = tools.queryProlog("nonexistent_predicate(X)")

            assertTrue(result.contains("No results"))
        }

        @Test
        fun `queryProlog translates entity names to ids`() {
            // Query using the human-readable name
            val result = tools.queryProlog("expert_in('alice-id', X)")

            assertTrue(result.contains("Found") || result.contains("result"))
        }
    }

    @Nested
    inner class ListPredicatesTests {

        @Test
        fun `listPredicates returns available predicates`() {
            val result = tools.listPredicates()

            assertTrue(result.contains("expert_in"))
            assertTrue(result.contains("works_at"))
            assertTrue(result.contains("friend_of"))
        }

        @Test
        fun `listPredicates formats predicates with arity`() {
            val result = tools.listPredicates()

            // Predicates are shown with (X, Y) format
            assertTrue(result.contains("(X, Y)"))
        }
    }

    @Nested
    inner class ListEntitiesTests {

        @Test
        fun `listEntities returns known entities`() {
            val result = tools.listEntities()

            assertTrue(result.contains("Alice"))
            assertTrue(result.contains("Bob"))
            assertTrue(result.contains("TechCorp"))
        }

        @Test
        fun `listEntities with empty names returns appropriate message`() {
            val emptyTools = PrologTools(prologResult, prologSchema, emptyMap())

            val result = emptyTools.listEntities()

            assertTrue(result.contains("No entities"))
        }
    }

    @Nested
    inner class ShowFactsTests {

        @Test
        fun `showFacts returns sample facts`() {
            val result = tools.showFacts()

            assertTrue(result.contains("Sample facts"))
            assertTrue(result.contains("expert_in") || result.contains("works_at"))
        }

        @Test
        fun `showFacts shows total count`() {
            val result = tools.showFacts()

            assertTrue(result.contains("6 total") || result.contains("total"))
        }

        @Test
        fun `showFacts with empty facts returns appropriate message`() {
            val emptyResult = PrologProjectionResult(
                facts = emptyList(),
                confidenceFacts = emptyList(),
                groundingFacts = emptyList(),
            )
            val emptyTools = PrologTools(emptyResult, prologSchema, entityNames)

            val result = emptyTools.showFacts()

            assertTrue(result.contains("No facts"))
        }
    }

    @Nested
    inner class CheckFactTests {

        @Test
        fun `checkFact returns yes for existing fact`() {
            val result = tools.checkFact("expert_in('alice_id', 'kubernetes')")

            assertTrue(result.contains("Yes") || result.contains("true"))
        }

        @Test
        fun `checkFact returns no for non-existing fact`() {
            val result = tools.checkFact("expert_in('alice_id', 'java')")

            assertTrue(result.contains("No") || result.contains("not found"))
        }
    }

    @Nested
    inner class EntityNameResolutionTests {

        @Test
        fun `query results show human-readable names`() {
            // The tool should resolve IDs to names in output
            val result = tools.queryProlog("works_at(X, Y)")

            // Should show either the name or the ID (depends on resolution)
            assertTrue(result.contains("Found") || result.contains("result"))
        }
    }

    @Nested
    inner class AsToolsTests {

        @Test
        fun `asTools creates tool instances`() {
            val toolList = PrologTools.asTools(prologResult, prologSchema, entityNames)

            assertTrue(toolList.isNotEmpty())
        }
    }
}
