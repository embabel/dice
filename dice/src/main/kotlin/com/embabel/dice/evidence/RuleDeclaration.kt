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

import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.Objects

/**
 * Says which recorded facts one clause of a rule reads, so a conclusion the rule proves can cite them.
 *
 * The [head] is the clause's head with its variables, such as `can_help(X, Topic)`. Each input is a fact the
 * clause reads, written in the head's variables, such as `expert_in(X, Topic)`. Each guard is a test the clause
 * also runs, such as `X \= Y`; it narrows the answers but is never cited as a fact. A rule with several clauses
 * gets one declaration per clause. Inputs and guards keep the order they were added in, and each `with` method
 * returns a copy.
 *
 * The inputs are declared rather than traced from the engine, so the same declaration works whichever engine
 * runs the rule. A rule that calls itself, such as a chain of managers, can't be declared this way.
 *
 * ```java
 * RuleDeclaration declaration = RuleDeclaration.of("coworker(X, Y)")
 *     .withInput("works_at(X, C)")
 *     .withInput("works_at(Y, C)")
 *     .withGuard("X \\= Y");
 * ```
 *
 * ```kotlin
 * val declaration = RuleDeclaration.of("coworker(X, Y)")
 *     .withInput("works_at(X, C)")
 *     .withInput("works_at(Y, C)")
 *     .withGuard("X \\= Y")
 * ```
 */
@ApiStatus.Experimental
class RuleDeclaration private constructor(
    /** The clause's head, such as `can_help(X, Topic)`. */
    val head: String,
    inputs: List<String>,
    guards: List<String>,
) {

    /** The facts the clause reads, such as `expert_in(X, Topic)`, in the order added. */
    val inputs: List<String> = Collections.unmodifiableList(ArrayList(inputs))

    /** The tests the clause runs that are not facts, such as `X \= Y`, in the order added. */
    val guards: List<String> = Collections.unmodifiableList(ArrayList(guards))

    private val shape = GoalShape.parse(head)

    init {
        require(isNamedTerm(head)) { "head must be a lower-case name with arguments, such as can_help(X, Topic)" }
        require(this.inputs.all { isNamedTerm(it) }) {
            "each input must be a lower-case name with arguments, such as expert_in(X, Topic)"
        }
        require(this.guards.all { it.isNotBlank() && !it.endsWith('.') }) {
            "each guard must not be blank or end with a period"
        }
    }

    /** The name of the head, such as `can_help`. */
    val predicate: String get() = shape.predicate

    /** How many arguments the head has, counted the same way as [RuleGoal.arity]. */
    val arity: Int get() = shape.arity

    /**
     * Returns a copy with [input] added after the inputs already there.
     *
     * @param input a fact the clause reads, such as `expert_in(X, Topic)`. Trimmed before it is stored.
     * @return the updated declaration
     * @throws IllegalArgumentException if [input] is not a lower-case name followed by its arguments in brackets,
     * such as a test like `X \= Y`, which belongs in [withGuard]
     */
    fun withInput(input: String): RuleDeclaration = RuleDeclaration(head, inputs + input.trim(), guards)

    /**
     * Returns a copy with [guard] added after the guards already there.
     *
     * @param guard a test the clause runs, such as `X \= Y`. Trimmed before it is stored.
     * @return the updated declaration
     * @throws IllegalArgumentException if [guard] is blank or ends with a period
     */
    fun withGuard(guard: String): RuleDeclaration = RuleDeclaration(head, inputs, guards + guard.trim())

    /** Two declarations are equal when their heads, inputs and guards are equal, in the same order. */
    override fun equals(other: Any?): Boolean =
        other is RuleDeclaration && head == other.head && inputs == other.inputs && guards == other.guards

    override fun hashCode(): Int = Objects.hash(head, inputs, guards)

    /** Returns the predicate, the arity and how many inputs and guards there are. */
    override fun toString(): String =
        "RuleDeclaration(predicate=$predicate, arity=$arity, inputs=${inputs.size}, guards=${guards.size})"

    companion object {

        private val NAME = Regex("[a-z][A-Za-z0-9_]*")

        private fun isNamedTerm(text: String): Boolean {
            val shape = GoalShape.parse(text)
            return shape.compound && NAME.matches(shape.predicate)
        }

        /**
         * Creates a declaration for one clause, with no inputs or guards yet.
         *
         * @param head the clause's head, such as `can_help(X, Topic)`. Trimmed before it is stored.
         * @return the declaration
         * @throws IllegalArgumentException if [head] is not a lower-case name followed by its arguments in brackets
         */
        @JvmStatic
        fun of(head: String): RuleDeclaration = RuleDeclaration(head.trim(), emptyList(), emptyList())
    }
}
