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

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.storage.model.PropositionView
import com.embabel.dice.store.CapabilityReporting
import com.embabel.dice.store.ReadBudget
import com.embabel.dice.store.ReadCriteria
import com.embabel.dice.store.ReadCriterion
import com.embabel.dice.store.ReadKeywordQuery
import com.embabel.dice.store.ReadOrder
import com.embabel.dice.store.ReadQuery
import com.embabel.dice.store.ScopedReadSpec
import com.embabel.dice.store.ScopedReads
import com.embabel.dice.store.StoreCapabilities
import com.embabel.dice.store.StoreCapability
import org.drivine.manager.PersistenceManager
import org.drivine.query.GraphViewQueryBuilder

/**
 * Scope and criteria are part of the database query that hydrates the views. Loading ids and
 * then looking up the propositions in a second query could return a concurrent replacement
 * that no longer satisfies the original scope or filters.
 */
internal class DrivineScopedReads(private val persistence: PersistenceManager) : ScopedReads, CapabilityReporting {

    override val capabilities: StoreCapabilities = StoreCapabilities.of(capability)

    private val view = GraphViewQueryBuilder.forView(PropositionView::class.java, persistence.grammar)
    private val root = view.nodeAlias

    override fun query(spec: ScopedReadSpec, query: ReadQuery, budget: ReadBudget): List<Proposition> {
        spec.requireOnly(criteria, capability)
        query.requireOnly(criteria, orders, capability)
        query.limit?.let(budget::requireRows)
        return rows(Filter(spec, query.criteria), query.order, query.limit, budget)
    }

    override fun count(spec: ScopedReadSpec, criteria: ReadCriteria, budget: ReadBudget): Int {
        spec.requireOnly(Companion.criteria, capability)
        criteria.requireOnly(Companion.criteria, capability)
        val filter = Filter(spec, criteria)
        val count = persistence.query(
            cypherQuery(view.buildCountQuery(filter.where, emptyList(), emptyList()))
                .bind(filter.params)
                .transform(Number::class.java),
        ).single().toLong()
        budget.requireRows(count)
        return count.toInt()
    }

    override fun matchKeywords(spec: ScopedReadSpec, request: ReadKeywordQuery, budget: ReadBudget): List<Proposition> {
        spec.requireOnly(criteria, capability)
        request.requireOnly(criteria, orders, capability)
        request.limit?.let(budget::requireRows)
        return rows(Filter(spec, request.criteria, request.tokens), request.order, request.limit, budget)
    }

    private fun rows(filter: Filter, order: ReadOrder, limit: Int?, budget: ReadBudget): List<Proposition> {
        val ranking = listOfNotNull(
            filter.overlap?.let { "$it DESC" },
            when (order) {
                ReadOrder.NONE -> null
                ReadOrder.CREATED_DESC -> "$root.created DESC"
                ReadOrder.REINFORCE_COUNT_DESC -> "coalesce($root.reinforceCount, 0) DESC"
                ReadOrder.IMPORTANCE_DESC -> "coalesce($root.importance, 0.5) DESC"
                else -> error("Scoped order validation did not reject $order")
            },
            "$root.id ASC",
        ).joinToString(", ")
        val statement = view.buildQuery(filter.where, ranking) + "\nLIMIT \$rowLimit"
        val rows = persistence.query(
            cypherQuery(statement).bind(filter.params + ("rowLimit" to (limit ?: budget.maxRows + 1)))
                .transform(PropositionView::class.java),
        )
        budget.requireRows(rows.size)
        return rows.map(PropositionGraphMapper::toProposition)
    }

    private inner class Filter(spec: ScopedReadSpec, criteria: ReadCriteria, tokens: List<String>? = null) {
        val params = mutableMapOf<String, Any>("contexts" to spec.scope.contextIdValues.toList())

        val overlap: String? = tokens?.let {
            params["tokens"] = it
            "size([token IN \$tokens WHERE toLower($root.text) CONTAINS token])"
        }

        val where = buildList {
            add("$root.contextId IN \$contexts")
            for (filter in spec.filters + criteria) {
                fun bind(expression: String, operator: String, value: Any?) {
                    if (value == null) return
                    val param = "criterion${params.size}"
                    params[param] = value
                    add("$expression $operator \$$param")
                }
                bind("coalesce($root.status, 'ACTIVE')", "IN", filter.statuses?.map { it.name })
                bind("coalesce($root.level, 0)", ">=", filter.minLevel)
                bind("coalesce($root.level, 0)", "<=", filter.maxLevel)
                bind("$root.confidence", ">=", filter.minConfidence)
                bind("coalesce($root.importance, 0.5)", ">=", filter.minImportance)
                bind("coalesce($root.reinforceCount, 0)", ">=", filter.minReinforceCount)
                bind("coalesce($root.pinned, false)", "=", filter.pinned)
            }
            overlap?.let { add("$it > 0") }
        }.joinToString(" AND ")
    }

    private companion object {
        val capability = StoreCapability.SCOPED_READS

        // Deliberately omit live decay filters/order until all supported dialects can evaluate
        // effectiveConfidenceAt before ranking. The materialized score can be stale.
        val criteria = setOf(
            ReadCriterion.STATUSES,
            ReadCriterion.MIN_LEVEL,
            ReadCriterion.MAX_LEVEL,
            ReadCriterion.MIN_CONFIDENCE,
            ReadCriterion.EFFECTIVE_CONFIDENCE_AS_OF,
            ReadCriterion.DECAY_K,
            ReadCriterion.MIN_IMPORTANCE,
            ReadCriterion.MIN_REINFORCE_COUNT,
            ReadCriterion.PINNED,
        )
        val orders = setOf(
            ReadOrder.NONE,
            ReadOrder.CREATED_DESC,
            ReadOrder.REINFORCE_COUNT_DESC,
            ReadOrder.IMPORTANCE_DESC,
        )
    }
}
