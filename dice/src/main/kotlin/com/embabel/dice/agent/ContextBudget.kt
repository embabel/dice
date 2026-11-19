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
package com.embabel.dice.agent

import com.embabel.common.ai.model.TokenCountEstimator
import com.embabel.dice.proposition.Proposition
import org.jetbrains.annotations.ApiStatus
import java.util.Collections

/**
 * A rough limit on how many tokens of facts go into a prompt, and the rule for picking which facts fit.
 *
 * A fact costs whatever the [estimator] says its text costs. Nothing else is counted: not the
 * heading around the facts, not any source or entity labels printed next to them. The default
 * estimator guesses about four characters per token, which can be off by a fifth or more, so treat
 * the budget as a soft target rather than a hard cap.
 *
 * [select] keeps every pinned fact first, in the order given, whatever they cost. It then adds the
 * other facts in the order given, or in [ranking] order when one is set, and stops at the first one
 * that does not fit. Facts after that are dropped too, even small ones that would have fitted, so
 * the selection is always a prefix of the order you asked for.
 *
 * ```java
 * ContextBudget budget = ContextBudget.ofTokens(500)
 *     .withRanking(Comparator.comparingDouble(Proposition::getImportance).reversed());
 * BudgetedSelection selection = budget.select(facts);
 * ```
 *
 * EXPERIMENTAL. The API may change before 1.0.
 */
@ApiStatus.Experimental
class ContextBudget private constructor(
    /** How many tokens the selected facts may use. Always greater than zero. */
    val tokens: Int,
    /** Works out how many tokens a fact's text costs. */
    val estimator: TokenCountEstimator<String>,
    /** The order to consider unpinned facts in, or null to keep the order they are given in. */
    val ranking: Comparator<Proposition>?,
) {

    /**
     * Returns a copy that costs facts with [estimator] instead.
     *
     * @param estimator counts the tokens in a fact's text; a negative count is treated as zero
     * @return the updated budget. This one is unchanged.
     */
    fun withEstimator(estimator: TokenCountEstimator<String>): ContextBudget =
        ContextBudget(tokens, estimator, ranking)

    /**
     * Returns a copy that considers unpinned facts in [ranking] order. Pinned facts are not
     * reordered; they always come first, in the order given. Facts the comparator ranks equal
     * keep the order they were given in.
     *
     * @param ranking puts the facts you most want kept first
     * @return the updated budget. This one is unchanged.
     */
    fun withRanking(ranking: Comparator<Proposition>): ContextBudget =
        ContextBudget(tokens, estimator, ranking)

    /**
     * Picks the facts that fit in this budget.
     *
     * Pinned facts are all kept, even when they alone go over the budget; the result then reports
     * [BudgetedSelection.isOverBudget] and every unpinned fact is dropped. Unpinned facts are added
     * in order until the first one that does not fit, and that one and everything after it is dropped.
     *
     * @param candidates the facts to choose from, in the order you would like them
     * @return the facts kept, in prompt order, and the facts dropped, in the order they were considered
     */
    fun select(candidates: List<Proposition>): BudgetedSelection {
        val pinned = candidates.filter { it.pinned }
        val unpinned = candidates.filterNot { it.pinned }
        // sortedWith is stable, so ties keep their input order.
        val ordered = ranking?.let { unpinned.sortedWith(it) } ?: unpinned

        val selected = ArrayList<Proposition>(candidates.size)
        // Long so that many large pinned facts cannot overflow the running total.
        var used = 0L
        for (fact in pinned) {
            selected += fact
            used += cost(fact)
        }
        var cut = ordered.size
        for ((index, fact) in ordered.withIndex()) {
            val next = used + cost(fact)
            if (next > tokens) {
                cut = index
                break
            }
            selected += fact
            used = next
        }
        return BudgetedSelection(
            selected = selected,
            dropped = ordered.subList(cut, ordered.size),
            estimatedTokens = used.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            budgetTokens = tokens,
        )
    }

    private fun cost(fact: Proposition): Long = estimator.estimate(fact.text).coerceAtLeast(0).toLong()

    override fun toString(): String = "ContextBudget(tokens=$tokens, ranked=${ranking != null})"

    companion object {

        /**
         * Creates a budget of [tokens] that guesses costs from character counts and keeps facts in
         * the order they are given.
         *
         * @param tokens how many tokens the selected facts may use
         * @return the budget
         * @throws IllegalArgumentException if [tokens] is zero or less
         */
        @JvmStatic
        fun ofTokens(tokens: Int): ContextBudget {
            require(tokens > 0) { "a context budget needs at least one token, was $tokens" }
            return ContextBudget(tokens, TokenCountEstimator.heuristic(), null)
        }
    }
}

/**
 * The result of [ContextBudget.select]: which facts made it into the budget, which did not, and
 * how many tokens the kept ones are estimated to use.
 *
 * EXPERIMENTAL. The API may change before 1.0.
 */
@ApiStatus.Experimental
class BudgetedSelection(
    selected: List<Proposition>,
    dropped: List<Proposition>,
    /** The estimated tokens used by the selected facts. */
    val estimatedTokens: Int,
    /** The budget the facts were selected against. */
    val budgetTokens: Int,
) {

    /** The facts that fit: pinned ones first, then the rest in the order considered. The list cannot be modified. */
    val selected: List<Proposition> = Collections.unmodifiableList(ArrayList(selected))

    /** The facts left out, in the order they were considered. [ContextBudget.select] never drops a pinned fact. The list cannot be modified. */
    val dropped: List<Proposition> = Collections.unmodifiableList(ArrayList(dropped))

    /**
     * True when the selected facts are estimated to use more than the budget. This only happens
     * when the pinned facts alone cost more than the budget, since pinned facts are never dropped.
     */
    val isOverBudget: Boolean
        get() = estimatedTokens > budgetTokens

    override fun toString(): String =
        "BudgetedSelection(selected=${selected.size}, dropped=${dropped.size}, " +
            "estimatedTokens=$estimatedTokens, budgetTokens=$budgetTokens)"
}
