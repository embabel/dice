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
import java.util.Objects

/**
 * A question for a rule engine, written as a goal such as `can_help('alice', T)`, with the context to ask it in
 * and how many answers to return at most.
 *
 * The goal is plain text in the engine's own syntax, so this type does not tie a caller to one engine. Each `with`
 * method returns a copy. A new goal has no context and a limit of 10.
 *
 * ```java
 * RuleGoal goal = RuleGoal.of("can_help('alice', T)")
 *     .withContext("ctx-main")
 *     .withLimit(5);
 * ```
 *
 * ```kotlin
 * val goal = RuleGoal.of("can_help('alice', T)")
 *     .withContext("ctx-main")
 *     .withLimit(5)
 * ```
 */
@ApiStatus.Experimental
class RuleGoal private constructor(
    /** The goal text, such as `can_help('alice', T)`, trimmed and without a closing period. */
    val goal: String,
    /** The context to ask the goal in. Null when the caller set none. */
    val contextId: String?,
    /** The most answers to return. Always above zero. */
    val limit: Int,
) {

    init {
        require(goal.isNotBlank()) { "goal must not be blank" }
        require(!goal.endsWith('.')) { "goal must not end with a period" }
        require(contextId == null || contextId.isNotBlank()) { "contextId must not be blank" }
        require(limit > 0) { "limit must be greater than zero" }
    }

    private val shape = GoalShape.parse(goal)

    /** The name of the goal: the text before its opening bracket, or the whole goal when it has no arguments. */
    val predicate: String get() = shape.predicate

    /**
     * How many arguments the goal has, or 0 when it has none. Commas inside quoted atoms and inside nested
     * `()`, `[]` or `{}` are not counted, so `p(f(a, b), 'x,y')` has 2.
     */
    val arity: Int get() = shape.arity

    /**
     * Returns a copy asked in [contextId].
     *
     * @param contextId the context to ask the goal in
     * @return the updated goal
     * @throws IllegalArgumentException if [contextId] is blank
     */
    fun withContext(contextId: String): RuleGoal = RuleGoal(goal, contextId, limit)

    /**
     * Returns a copy that returns at most [limit] answers.
     *
     * @param limit the most answers to return
     * @return the updated goal
     * @throws IllegalArgumentException if [limit] is zero or less
     */
    fun withLimit(limit: Int): RuleGoal = RuleGoal(goal, contextId, limit)

    /** Two goals are equal when their text, context and limit are equal. */
    override fun equals(other: Any?): Boolean =
        other is RuleGoal && goal == other.goal && contextId == other.contextId && limit == other.limit

    override fun hashCode(): Int = Objects.hash(goal, contextId, limit)

    /**
     * Returns the predicate, the arity, the context and the limit. The goal text is left out, because its
     * arguments can name people or other things a log should not show.
     */
    override fun toString(): String =
        "RuleGoal(predicate=$predicate, arity=$arity, contextId=$contextId, limit=$limit)"

    companion object {

        private const val DEFAULT_LIMIT = 10

        /**
         * Creates a goal with no context and a limit of 10.
         *
         * @param goal the goal text, such as `can_help('alice', T)`. Trimmed before it is stored.
         * @return the goal
         * @throws IllegalArgumentException if [goal] is blank or ends with a period
         */
        @JvmStatic
        fun of(goal: String): RuleGoal = RuleGoal(goal.trim(), null, DEFAULT_LIMIT)
    }
}

/**
 * The name and argument count of a goal or rule head, read straight from its text without a Prolog parser.
 *
 * The name is whatever comes before the first opening bracket outside a quoted atom. The arguments are the
 * top-level commas inside that bracket plus one; commas inside quoted atoms or strings, or nested in `()`, `[]`
 * or `{}`, don't count. A goal with no bracket is an atom: its name is the whole text and it has no arguments.
 *
 * @property predicate the goal's name
 * @property arity how many arguments it has, 0 for an atom
 * @property compound true when the text is exactly `name(args)`: non-empty arguments and a closing bracket at the end
 */
internal class GoalShape private constructor(val predicate: String, val arity: Int, val compound: Boolean) {

    companion object {

        private val QUOTES = setOf('\'', '"', '`')

        fun parse(text: String): GoalShape {
            val goal = text.trim()
            val open = firstOpenBracket(goal)
            if (open < 0) return GoalShape(goal, 0, false)

            var depth = 0
            var commas = 0
            var close = -1
            var hasArgs = false
            var quote: Char? = null
            var i = open + 1
            while (i < goal.length) {
                val c = goal[i]
                if (quote != null) {
                    hasArgs = true
                    when {
                        c == '\\' -> i++
                        c == quote && goal.getOrNull(i + 1) == quote -> i++
                        c == quote -> quote = null
                    }
                } else when {
                    startsCharacterCode(goal, i) -> { i = characterCodeEnd(goal, i + 1); continue }
                    c in QUOTES -> { quote = c; hasArgs = true }
                    c == '(' || c == '[' || c == '{' -> { depth++; hasArgs = true }
                    c == ')' -> if (depth == 0) { close = i; break } else depth--
                    c == ']' || c == '}' -> if (depth > 0) depth--
                    c == ',' -> if (depth == 0) commas++
                    !c.isWhitespace() -> hasArgs = true
                }
                i++
            }
            val arity = if (hasArgs) commas + 1 else 0
            val compound = hasArgs && close == goal.length - 1
            return GoalShape(goal.substring(0, open).trim(), arity, compound)
        }

        // The first '(' that is not inside a quoted atom, or -1 when there is none.
        private fun firstOpenBracket(goal: String): Int {
            var quote: Char? = null
            var i = 0
            while (i < goal.length) {
                val c = goal[i]
                if (quote != null) {
                    when {
                        c == '\\' -> i++
                        c == quote && goal.getOrNull(i + 1) == quote -> i++
                        c == quote -> quote = null
                    }
                } else if (startsCharacterCode(goal, i)) {
                    i = characterCodeEnd(goal, i + 1)
                    continue
                } else if (c in QUOTES) {
                    quote = c
                } else if (c == '(') {
                    return i
                }
                i++
            }
            return -1
        }
    }
}

// True when the quote at [index] belongs to a character code such as 0'A rather than opening a quoted atom: it
// follows a lone 0, one that isn't the tail of a longer name or number like a0 or 10.
private fun startsCharacterCode(text: String, index: Int): Boolean =
    text[index] == '\'' && text.getOrNull(index - 1) == '0' &&
        text.getOrNull(index - 2).let { it == null || !(it.isLetterOrDigit() || it == '_') }

/**
 * Where the character after `0'` ends, starting at [start]: a doubled quote such as `0'''`, an escape such as
 * `0'\n` or `0'\x41\` (up to its closing backslash), or else a single character.
 */
internal fun characterCodeEnd(text: String, start: Int): Int = when {
    start >= text.length -> start
    text[start] == '\'' && text.getOrNull(start + 1) == '\'' -> start + 2
    text[start] != '\\' -> start + 1
    text.getOrNull(start + 1)?.let { it == 'x' || it.isDigit() } == true ->
        text.indexOf('\\', start + 2).let { if (it < 0) text.length else it + 1 }
    else -> minOf(start + 2, text.length)
}
