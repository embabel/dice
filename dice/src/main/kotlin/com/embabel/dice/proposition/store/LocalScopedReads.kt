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
package com.embabel.dice.proposition.store

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.matchesFilters
import com.embabel.dice.store.ReadBudget
import com.embabel.dice.store.ReadCriteria
import com.embabel.dice.store.ReadCriterion
import com.embabel.dice.store.ReadKeywordQuery
import com.embabel.dice.store.ReadOrder
import com.embabel.dice.store.ReadQuery
import com.embabel.dice.store.ScopedReadSpec
import com.embabel.dice.store.ScopedReads
import com.embabel.dice.store.StoreCapability
import java.time.Instant
import java.util.Locale

/**
 * Scoped selection for stores that hold their propositions in memory.
 *
 * @param propositions the store's current propositions
 */
internal class LocalScopedReads(private val propositions: () -> Collection<Proposition>) : ScopedReads {

    override fun query(spec: ScopedReadSpec, query: ReadQuery, budget: ReadBudget): List<Proposition> {
        spec.requireOnly(supportedCriteria, capability)
        query.requireOnly(supportedCriteria, orders, capability)
        query.limit?.let(budget::requireRows)
        val asOf = query.criteria.effectiveConfidenceAsOf ?: Instant.now()
        val matches = select(spec, query.criteria, asOf)
        val order = orderBy(query.order, asOf, query.criteria.decayK)
        return orderedRows(matches, query.limit, budget, order)
    }

    override fun count(spec: ScopedReadSpec, criteria: ReadCriteria, budget: ReadBudget): Int {
        spec.requireOnly(supportedCriteria, capability)
        criteria.requireOnly(supportedCriteria, capability)
        val asOf = criteria.effectiveConfidenceAsOf ?: Instant.now()
        val count = select(spec, criteria, asOf).take(budget.maxRows + 1).count()
        budget.requireRows(count)
        return count
    }

    override fun matchKeywords(spec: ScopedReadSpec, request: ReadKeywordQuery, budget: ReadBudget): List<Proposition> {
        spec.requireOnly(supportedCriteria, capability)
        request.requireOnly(supportedCriteria, orders, capability)
        request.limit?.let(budget::requireRows)
        val asOf = request.criteria.effectiveConfidenceAsOf ?: Instant.now()
        val order = orderBy(request.order, asOf, request.criteria.decayK)
        val ranked = select(spec, request.criteria, asOf).mapNotNull { proposition ->
            val text = proposition.text.lowercase(Locale.ROOT)
            val matches = request.tokens.count { token -> token in text }
            if (matches == 0) null else Match(proposition, matches)
        }
        val comparator = compareByDescending<Match> { it.tokenCount }.thenBy(order) { it.proposition }
        return orderedRows(ranked, request.limit, budget, comparator).map { it.proposition }
    }

    /**
     * Selects by context and every criteria set before ordering or counting.
     *
     * @param spec the authorized contexts and the filters carried with them
     * @param request criteria specific to this call
     * @param asOf one instant for every effective confidence calculation
     * @return matching rows in the store's iteration order
     */
    private fun select(spec: ScopedReadSpec, request: ReadCriteria, asOf: Instant): Sequence<Proposition> {
        val filters = (spec.filters + request).map(::filtersOnly)
        return propositions().asSequence()
            .filter { it.contextId in spec.scope }
            .filter { proposition -> filters.all { it.matchesFilters(proposition, asOf) } }
    }

    private data class Match(val proposition: Proposition, val tokenCount: Int)

    private companion object {
        val supportedCriteria = setOf(
            ReadCriterion.ENTITY_ID,
            ReadCriterion.ANY_ENTITY_IDS,
            ReadCriterion.ALL_ENTITY_IDS,
            ReadCriterion.STATUSES,
            ReadCriterion.MIN_LEVEL,
            ReadCriterion.MAX_LEVEL,
            ReadCriterion.CREATED_AFTER,
            ReadCriterion.CREATED_BEFORE,
            ReadCriterion.REVISED_AFTER,
            ReadCriterion.REVISED_BEFORE,
            ReadCriterion.ACCESSED_AFTER,
            ReadCriterion.ACCESSED_BEFORE,
            ReadCriterion.MIN_CONFIDENCE,
            ReadCriterion.MIN_EFFECTIVE_CONFIDENCE,
            ReadCriterion.BELOW_EFFECTIVE_CONFIDENCE,
            ReadCriterion.EFFECTIVE_CONFIDENCE_AS_OF,
            ReadCriterion.DECAY_K,
            ReadCriterion.MIN_IMPORTANCE,
            ReadCriterion.MIN_REINFORCE_COUNT,
            ReadCriterion.MIN_TRUST_SCORE,
            ReadCriterion.PINNED,
        )
        val orders = setOf(
            ReadOrder.NONE,
            ReadOrder.EFFECTIVE_CONFIDENCE_DESC,
            ReadOrder.CREATED_DESC,
            ReadOrder.REVISED_DESC,
            ReadOrder.LAST_ACCESSED_DESC,
            ReadOrder.REINFORCE_COUNT_DESC,
            ReadOrder.IMPORTANCE_DESC,
        )
        val capability = StoreCapability.SCOPED_READS

        /**
         * Sorts the candidates. A read with no limit checks the row cap before sorting.
         *
         * @param rows filtered candidates
         * @param limit the requested limit, already checked against the budget
         * @param budget the maximum result size
         * @param order the total order of candidates
         * @return the ordered result
         */
        fun <T> orderedRows(rows: Sequence<T>, limit: Int?, budget: ReadBudget, order: Comparator<T>): List<T> {
            if (limit != null) return rows.sortedWith(order).take(limit).toList()
            val capped = rows.take(budget.maxRows + 1).toList()
            budget.requireRows(capped.size)
            return capped.sortedWith(order)
        }

        /**
         * Reuses the proposition predicate for one criteria set while the scope stays on the read.
         *
         * @param criteria one filter or the request's criteria
         * @return the predicate inputs
         */
        fun filtersOnly(criteria: ReadCriteria): PropositionQuery = PropositionQuery(
            entityId = criteria.entityId,
            anyEntityIds = criteria.anyEntityIds,
            allEntityIds = criteria.allEntityIds,
            statuses = criteria.statuses,
            minLevel = criteria.minLevel,
            maxLevel = criteria.maxLevel,
            createdAfter = criteria.createdAfter,
            createdBefore = criteria.createdBefore,
            revisedAfter = criteria.revisedAfter,
            revisedBefore = criteria.revisedBefore,
            accessedAfter = criteria.accessedAfter,
            accessedBefore = criteria.accessedBefore,
            minConfidence = criteria.minConfidence,
            minEffectiveConfidence = criteria.minEffectiveConfidence,
            belowEffectiveConfidence = criteria.belowEffectiveConfidence,
            decayK = criteria.decayK,
            minImportance = criteria.minImportance,
            minReinforceCount = criteria.minReinforceCount,
            minTrustScore = criteria.minTrustScore,
            pinned = criteria.pinned,
        )

        /**
         * Compares tied rows by their stable IDs.
         *
         * @param order the request order
         * @param asOf the instant at which confidence is evaluated
         * @param decayK the request's decay rate
         * @return the total order of candidate propositions
         */
        fun orderBy(order: ReadOrder, asOf: Instant, decayK: Double): Comparator<Proposition> {
            val ranking: Comparator<Proposition> = when (order) {
                ReadOrder.NONE -> return compareBy { it.id }
                ReadOrder.EFFECTIVE_CONFIDENCE_DESC -> compareByDescending { it.effectiveConfidenceAt(asOf, decayK) }
                ReadOrder.CREATED_DESC -> compareByDescending { it.created }
                ReadOrder.REVISED_DESC -> compareByDescending { it.lastTouched }
                ReadOrder.LAST_ACCESSED_DESC -> compareByDescending { it.lastAccessed }
                ReadOrder.REINFORCE_COUNT_DESC -> compareByDescending { it.reinforceCount }
                ReadOrder.IMPORTANCE_DESC -> compareByDescending { it.importance }
            }
            return ranking.thenBy { it.id }
        }
    }
}
