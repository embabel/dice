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

import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.matchesFilters
import java.util.Locale

/** Read operations over an already resident snapshot, shared by both local backends. */
internal class LocalScopedReadOperations(private val rows: () -> Collection<Proposition>) {

    fun query(scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        if (limit == 0) return emptyList()
        val primary = when (order) {
            ReadOrder.NONE -> keyOrder
            ReadOrder.EFFECTIVE_CONFIDENCE_DESC -> compareByDescending<Proposition> {
                it.effectiveConfidenceAt(spec.asOf, spec.rankingDecayK)
            }.then(keyOrder)
            ReadOrder.CREATED_DESC -> compareByDescending<Proposition> { it.created }.then(keyOrder)
            ReadOrder.REVISED_DESC -> compareByDescending<Proposition> { it.lastTouched }.then(keyOrder)
            ReadOrder.LAST_ACCESSED_DESC -> compareByDescending<Proposition> { it.lastAccessed }.then(keyOrder)
            ReadOrder.REINFORCE_COUNT_DESC -> compareByDescending<Proposition> { it.reinforceCount }.then(keyOrder)
            ReadOrder.IMPORTANCE_DESC -> compareByDescending<Proposition> { it.importance }.then(keyOrder)
        }
        return eligible(scope, spec).sortedWith(primary).take(limit)
    }

    fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long = eligible(scope, spec).size.toLong()

    fun keywordOverlap(
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        tokens: List<String>,
        limit: Int,
    ): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        val normalized = tokens.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
        if (limit == 0 || normalized.isEmpty()) return emptyList()
        return eligible(scope, spec)
            .map { proposition ->
                val text = proposition.text.lowercase(Locale.ROOT)
                proposition to normalized.count { it in text }
            }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Proposition, Int>> { it.second }
                    .thenByDescending { it.first.effectiveConfidenceAt(spec.asOf, spec.rankingDecayK) }
                    .thenBy { it.first.contextId.value }
                    .thenBy { it.first.id },
            )
            .take(limit)
            .map { it.first }
    }

    fun eligible(scope: ContextReadScope, spec: ScopedReadSpec): List<Proposition> = rows().toList()
        .filter { proposition ->
            val key = PropositionKey(proposition.contextId.value, proposition.id)
            key.contextIdValue in scope.contextIdValues &&
                (spec.includedKeys == null || key in spec.includedKeys) &&
                key !in spec.excludedKeys &&
                spec.fragments.all { it.matchesFilters(proposition, spec.asOf) }
        }
        .distinctBy { PropositionKey(it.contextId.value, it.id) }

    private val keyOrder = compareBy<Proposition> { it.contextId.value }.thenBy { it.id }
}
