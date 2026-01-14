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
package com.embabel.dice.projection.prolog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PrologEngineTest {

    @Nested
    inner class BasicQueries {

        @Test
        fun `query returns true for existing fact`() {
            val engine = PrologEngine.fromTheory("""
                friend(alice, bob).
                friend(bob, charlie).
            """.trimIndent())

            assertTrue(engine.query("friend(alice, bob)"))
        }

        @Test
        fun `query returns false for non-existing fact`() {
            val engine = PrologEngine.fromTheory("""
                friend(alice, bob).
            """.trimIndent())

            assertFalse(engine.query("friend(alice, charlie)"))
        }

        @Test
        fun `query handles empty theory`() {
            val engine = PrologEngine.empty()

            assertFalse(engine.query("friend(alice, bob)"))
        }

        @Test
        fun `query handles malformed query gracefully`() {
            val engine = PrologEngine.fromTheory("friend(alice, bob).")

            // Malformed query should return false, not throw
            assertFalse(engine.query("friend(alice,"))
        }
    }

    @Nested
    inner class QueryAllTests {

        @Test
        fun `queryAll returns all matching bindings`() {
            val engine = PrologEngine.fromTheory("""
                expert_in(alice, kubernetes).
                expert_in(alice, docker).
                expert_in(bob, python).
            """.trimIndent())

            val results = engine.queryAll("expert_in(alice, X)")

            val successResults = results.filter { it.success }
            assertEquals(2, successResults.size)

            val skills = successResults.mapNotNull { it.bindings["X"] }.toSet()
            assertEquals(setOf("kubernetes", "docker"), skills)
        }

        @Test
        fun `queryAll returns failure result when no matches`() {
            val engine = PrologEngine.fromTheory("expert_in(alice, kubernetes).")

            val results = engine.queryAll("expert_in(charlie, X)")

            assertEquals(1, results.size)
            assertFalse(results.first().success)
        }

        @Test
        fun `queryAll with multiple variables returns all bindings`() {
            val engine = PrologEngine.fromTheory("""
                works_at(alice, techcorp).
                works_at(bob, techcorp).
                works_at(charlie, startup).
            """.trimIndent())

            val results = engine.queryAll("works_at(X, Y)")

            val successResults = results.filter { it.success }
            assertEquals(3, successResults.size)

            val pairs = successResults.map {
                it.bindings["X"] to it.bindings["Y"]
            }.toSet()
            assertTrue(pairs.contains("alice" to "techcorp"))
            assertTrue(pairs.contains("bob" to "techcorp"))
            assertTrue(pairs.contains("charlie" to "startup"))
        }

        @Test
        fun `queryAll with ground query returns true with empty bindings`() {
            val engine = PrologEngine.fromTheory("friend(alice, bob).")

            val results = engine.queryAll("friend(alice, bob)")

            assertEquals(1, results.size)
            assertTrue(results.first().success)
            assertTrue(results.first().bindings.isEmpty())
        }
    }

    @Nested
    inner class QueryFirstTests {

        @Test
        fun `queryFirst returns a matching result`() {
            val engine = PrologEngine.fromTheory("""
                color(red).
                color(blue).
            """.trimIndent())

            val result = engine.queryFirst("color(X)")

            assertTrue(result.success)
            assertNotNull(result.bindings["X"])
            // Verify it's one of the defined colors
            val color = result.bindings["X"]
            assertTrue(color == "red" || color == "blue", "Expected red or blue but got: $color")
        }

        @Test
        fun `queryFirst returns failure when no match`() {
            val engine = PrologEngine.fromTheory("number(1).")

            val result = engine.queryFirst("letter(X)")

            assertFalse(result.success)
            assertTrue(result.bindings.isEmpty())
        }
    }

    @Nested
    inner class FindAllTests {

        @Test
        fun `findAll returns all values for variable`() {
            val engine = PrologEngine.fromTheory("""
                color(red).
                color(green).
                color(blue).
            """.trimIndent())

            val colors = engine.findAll("color(X)", "X")

            assertEquals(listOf("red", "green", "blue"), colors)
        }

        @Test
        fun `findAll returns empty list when no matches`() {
            val engine = PrologEngine.fromTheory("color(red).")

            val shapes = engine.findAll("shape(X)", "X")

            assertTrue(shapes.isEmpty())
        }

        @Test
        fun `findAll ignores unbound variable`() {
            val engine = PrologEngine.fromTheory("""
                pair(a, 1).
                pair(b, 2).
            """.trimIndent())

            val firstElements = engine.findAll("pair(X, Y)", "X")

            assertEquals(listOf("a", "b"), firstElements)
        }
    }

    @Nested
    inner class RuleTests {

        @Test
        fun `query evaluates simple rule`() {
            val engine = PrologEngine.fromTheory("""
                parent(alice, bob).
                parent(bob, charlie).
                grandparent(X, Z) :- parent(X, Y), parent(Y, Z).
            """.trimIndent())

            assertTrue(engine.query("grandparent(alice, charlie)"))
            assertFalse(engine.query("grandparent(bob, charlie)"))
        }

        @Test
        fun `query evaluates recursive rule`() {
            val engine = PrologEngine.fromTheory("""
                parent(alice, bob).
                parent(bob, charlie).
                parent(charlie, david).
                ancestor(X, Y) :- parent(X, Y).
                ancestor(X, Y) :- parent(X, Z), ancestor(Z, Y).
            """.trimIndent())

            assertTrue(engine.query("ancestor(alice, bob)"))
            assertTrue(engine.query("ancestor(alice, charlie)"))
            assertTrue(engine.query("ancestor(alice, david)"))
            assertFalse(engine.query("ancestor(david, alice)"))
        }

        @Test
        fun `queryAll with rule returns derived facts`() {
            val engine = PrologEngine.fromTheory("""
                works_at(alice, techcorp).
                works_at(bob, techcorp).
                works_at(charlie, startup).
                coworker(X, Y) :- works_at(X, C), works_at(Y, C), X \= Y.
            """.trimIndent())

            val results = engine.queryAll("coworker(alice, X)")

            val coworkers = results.filter { it.success }.mapNotNull { it.bindings["X"] }
            assertEquals(listOf("bob"), coworkers)
        }
    }

    @Nested
    inner class QuotedAtomTests {

        @Test
        fun `query handles quoted atoms with special characters`() {
            val engine = PrologEngine.fromTheory("""
                entity('alice_123', 'Alice Smith').
                entity('bob_456', 'Bob Jones').
            """.trimIndent())

            assertTrue(engine.query("entity('alice_123', 'Alice Smith')"))
        }

        @Test
        fun `queryAll returns quoted atoms correctly`() {
            val engine = PrologEngine.fromTheory("""
                name('person_1', 'John Doe').
                name('person_2', 'Jane Doe').
            """.trimIndent())

            val results = engine.queryAll("name(Id, Name)")
            val successResults = results.filter { it.success }

            assertEquals(2, successResults.size)
        }
    }

    @Nested
    inner class FromProjectionTests {

        @Test
        fun `fromProjection creates engine with facts and rules`() {
            val facts = listOf(
                PrologFact("expert_in", listOf("alice", "kubernetes"), 0.9, sourcePropositionIds = listOf("prop-1")),
                PrologFact("works_at", listOf("alice", "techcorp"), 0.85, sourcePropositionIds = listOf("prop-2")),
            )

            val prologResult = PrologProjectionResult(
                facts = facts,
                confidenceFacts = facts.map { ConfidenceFact(it) },
                groundingFacts = facts.flatMap { f ->
                    f.sourcePropositionIds.map { GroundingFact(f, it) }
                },
            )

            val schema = PrologSchema.withRules("""
                can_help(X, Topic) :- expert_in(X, Topic).
            """.trimIndent())

            val engine = PrologEngine.fromProjection(prologResult, schema)

            assertTrue(engine.query("expert_in('alice', 'kubernetes')"))
            assertTrue(engine.query("works_at('alice', 'techcorp')"))
            assertTrue(engine.query("can_help('alice', 'kubernetes')"))
        }

        @Test
        fun `fromProjection creates working engine`() {
            val facts = listOf(
                PrologFact("works_at", listOf("alice-uuid", "techcorp-uuid"), 0.9, sourcePropositionIds = listOf("prop-1")),
            )

            val prologResult = PrologProjectionResult(
                facts = facts,
                confidenceFacts = emptyList(),
                groundingFacts = emptyList(),
            )

            val schema = PrologSchema.withRules("")

            val engine = PrologEngine.fromProjection(prologResult, schema)

            // Facts should be queryable
            assertTrue(engine.query("works_at('alice_uuid', 'techcorp_uuid')"))
        }
    }
}
