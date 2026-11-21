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
package com.embabel.dice.evidence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RuleGoalTest {

    @Nested
    inner class Parsing {

        @Test
        fun `a goal with a quoted atom and a variable has its predicate and two arguments`() {
            val goal = RuleGoal.of("can_help('alice', T)")

            assertEquals("can_help", goal.predicate)
            assertEquals(2, goal.arity)
        }

        @Test
        fun `commas inside a nested term or a quoted atom do not count as arguments`() {
            val goal = RuleGoal.of("p(f(a, b), 'x,y')")

            assertEquals("p", goal.predicate)
            assertEquals(2, goal.arity)
        }

        @Test
        fun `commas inside a list or braces do not count as arguments`() {
            val goal = RuleGoal.of("p([a, b], {c, d})")

            assertEquals("p", goal.predicate)
            assertEquals(2, goal.arity)
        }

        @Test
        fun `brackets and doubled quotes inside a quoted atom do not confuse the count`() {
            val goal = RuleGoal.of("p('it''s (a, b', c, \"q, r\")")

            assertEquals("p", goal.predicate)
            assertEquals(3, goal.arity)
        }

        @Test
        fun `an atom has itself as its predicate and no arguments`() {
            val goal = RuleGoal.of("ready")

            assertEquals("ready", goal.predicate)
            assertEquals(0, goal.arity)
        }

        @Test
        fun `a declaration parses its head the same way as a goal`() {
            val declaration = RuleDeclaration.of("can_consult(X, [A, B], Topic)")

            assertEquals("can_consult", declaration.predicate)
            assertEquals(3, declaration.arity)
        }

        @Test
        fun `a character code such as 0'A is not an opening quote`() {
            val goal = RuleGoal.of("help(X, 0'A, T)")

            assertEquals("help", goal.predicate)
            assertEquals(3, goal.arity)
        }

        @Test
        fun `a declaration whose head holds a character code is accepted`() {
            val declaration = RuleDeclaration.of("help(X, 0'A, T)")

            assertEquals("help", declaration.predicate)
            assertEquals(3, declaration.arity)
        }

        @Test
        fun `the character code for a quote, 0''', is one argument`() {
            assertEquals(2, RuleGoal.of("p(0''', b)").arity)
        }

        @Test
        fun `a name ending in 0 followed by a quoted atom is not a character code`() {
            assertEquals(2, RuleGoal.of("p(a0, 'x')").arity)
        }

        @Test
        fun `an escaped character code such as 0'backslash-n is one argument`() {
            assertEquals(2, RuleGoal.of("p(0'\\n, b)").arity)
        }
    }

    @Nested
    inner class Goals {

        @Test
        fun `a new goal has no context and a limit of ten`() {
            val goal = RuleGoal.of("can_help('alice', T)")

            assertEquals("can_help('alice', T)", goal.goal)
            assertNull(goal.contextId)
            assertEquals(10, goal.limit)
        }

        @Test
        fun `a blank goal is rejected`() {
            listOf("", "   ").forEach { text ->
                assertThrows(IllegalArgumentException::class.java) { RuleGoal.of(text) }
            }
        }

        @Test
        fun `a goal ending in a period is rejected`() {
            assertThrows(IllegalArgumentException::class.java) { RuleGoal.of("can_help('alice', T).") }
            assertThrows(IllegalArgumentException::class.java) { RuleGoal.of("ready. ") }
        }

        @Test
        fun `a zero or negative limit is rejected`() {
            val goal = RuleGoal.of("ready")

            assertThrows(IllegalArgumentException::class.java) { goal.withLimit(0) }
            assertThrows(IllegalArgumentException::class.java) { goal.withLimit(-1) }
        }

        @Test
        fun `a blank context is rejected`() {
            val goal = RuleGoal.of("ready")

            listOf("", "  ").forEach { context ->
                assertThrows(IllegalArgumentException::class.java) { goal.withContext(context) }
            }
        }

        @Test
        fun `with methods return a new goal and leave the old one as it was`() {
            val goal = RuleGoal.of("ready")

            val scoped = goal.withContext("ctx-main")
            val limited = scoped.withLimit(3)

            assertNull(goal.contextId)
            assertEquals(10, goal.limit)
            assertEquals("ctx-main", scoped.contextId)
            assertEquals(10, scoped.limit)
            assertEquals("ctx-main", limited.contextId)
            assertEquals(3, limited.limit)
            assertEquals("ready", limited.goal)
        }

        @Test
        fun `goals with the same text, context and limit are equal`() {
            val a = RuleGoal.of("can_help('alice', T)").withContext("ctx").withLimit(5)
            val b = RuleGoal.of("can_help('alice', T)").withContext("ctx").withLimit(5)

            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
            assertNotEquals(a, b.withLimit(6))
            assertNotEquals(a, RuleGoal.of("can_help('alice', T)").withLimit(5))
            assertNotEquals(a, RuleGoal.of("can_help('bob', T)").withContext("ctx").withLimit(5))
        }

        @Test
        fun `toString names the predicate and leaves the goal text out`() {
            val text = RuleGoal.of("can_help('alice', T)").withContext("ctx").toString()

            assertTrue(text.contains("can_help"))
            assertTrue(text.contains("ctx"))
            assertFalse(text.contains("alice"))
        }
    }

    @Nested
    inner class Declarations {

        @Test
        fun `a new declaration has no inputs or guards`() {
            val declaration = RuleDeclaration.of("can_help(X, Topic)")

            assertEquals("can_help(X, Topic)", declaration.head)
            assertEquals(emptyList<String>(), declaration.inputs)
            assertEquals(emptyList<String>(), declaration.guards)
        }

        @Test
        fun `a head that is not a name with arguments is rejected`() {
            listOf("", "  ", "ready", "X \\= Y", "can_help(X, Topic).", "Can_help(X)", "can_help()", "can_help(X")
                .forEach { head ->
                    assertThrows(IllegalArgumentException::class.java, { RuleDeclaration.of(head) }, head)
                }
        }

        @Test
        fun `an input that is not a name with arguments is rejected`() {
            val declaration = RuleDeclaration.of("can_help(X, Topic)")

            listOf("X \\= Y", "", "ready", "Expert(X)", "expert_in(X, Topic).", "expert_in(X), works_at(X, C)")
                .forEach { input ->
                    assertThrows(IllegalArgumentException::class.java, { declaration.withInput(input) }, input)
                }
        }

        @Test
        fun `a built-in test is kept as a guard`() {
            val declaration = RuleDeclaration.of("coworker(X, Y)").withGuard("X \\= Y")

            assertEquals(listOf("X \\= Y"), declaration.guards)
            assertEquals(emptyList<String>(), declaration.inputs)
        }

        @Test
        fun `a blank guard or one ending in a period is rejected`() {
            val declaration = RuleDeclaration.of("coworker(X, Y)")

            assertThrows(IllegalArgumentException::class.java) { declaration.withGuard(" ") }
            assertThrows(IllegalArgumentException::class.java) { declaration.withGuard("X \\= Y.") }
        }

        @Test
        fun `a declaration with two inputs keeps them in the order added`() {
            val declaration = RuleDeclaration.of("coworker(X, Y)")
                .withInput("works_at(X, C)")
                .withInput("works_at(Y, C)")
                .withGuard("X \\= Y")

            assertEquals(listOf("works_at(X, C)", "works_at(Y, C)"), declaration.inputs)
            assertEquals(listOf("X \\= Y"), declaration.guards)
        }

        @Test
        fun `with methods return a new declaration and leave the old one as it was`() {
            val declaration = RuleDeclaration.of("coworker(X, Y)")

            val withInput = declaration.withInput("works_at(X, C)")
            val withGuard = withInput.withGuard("X \\= Y")

            assertEquals(emptyList<String>(), declaration.inputs)
            assertEquals(emptyList<String>(), declaration.guards)
            assertEquals(listOf("works_at(X, C)"), withInput.inputs)
            assertEquals(emptyList<String>(), withInput.guards)
            assertEquals(listOf("X \\= Y"), withGuard.guards)
        }

        @Test
        fun `declarations with the same head, inputs and guards are equal, and order matters`() {
            val a = RuleDeclaration.of("coworker(X, Y)").withInput("works_at(X, C)").withInput("works_at(Y, C)")
            val b = RuleDeclaration.of("coworker(X, Y)").withInput("works_at(X, C)").withInput("works_at(Y, C)")
            val swapped = RuleDeclaration.of("coworker(X, Y)").withInput("works_at(Y, C)").withInput("works_at(X, C)")

            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
            assertNotEquals(a, swapped)
            assertNotEquals(a, b.withGuard("X \\= Y"))
        }

        @Test
        fun `the inputs and guards lists cannot be changed from outside`() {
            val declaration = RuleDeclaration.of("coworker(X, Y)").withInput("works_at(X, C)")

            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (declaration.inputs as MutableList<String>).add("works_at(Y, C)")
            }
        }

        @Test
        fun `toString names the predicate and counts`() {
            val text = RuleDeclaration.of("coworker(X, Y)").withInput("works_at(X, C)").withGuard("X \\= Y").toString()

            assertEquals("RuleDeclaration(predicate=coworker, arity=2, inputs=1, guards=1)", text)
        }
    }
}
