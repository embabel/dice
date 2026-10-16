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

import com.embabel.dice.proposition.CodePointOrder
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.matchesFilters
import com.embabel.dice.proposition.rowComparator
import java.util.Locale

/**
 * The scoped read engine both local stores share. Every call reads the store's live map, so two
 * reads in one session can see different rows when a write lands between them.
 */
internal class LocalScopedReadOperations(private val rows: () -> Collection<Proposition>) {

    fun query(spec: ScopedReadSpec, clock: ReadClock, order: PropositionQuery.OrderBy, limit: Int): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        if (limit == 0) return emptyList()
        val ranking = order.rowComparator(clock.asOf, clock.rankingDecayK)?.then(keyOrder) ?: keyOrder
        return eligible(spec, clock).sortedWith(ranking).take(limit)
    }

    fun count(spec: ScopedReadSpec, clock: ReadClock): Long = eligible(spec, clock).size.toLong()

    fun keywordOverlap(
        spec: ScopedReadSpec,
        clock: ReadClock,
        tokens: List<String>,
        limit: Int,
    ): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        val normalized = keywordTokens(tokens)
        if (limit == 0 || normalized.isEmpty()) return emptyList()
        return eligible(spec, clock)
            .map { proposition -> proposition to keywordHits(proposition.text, normalized) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Proposition, Int>> { it.second }
                    .thenByDescending { it.first.effectiveConfidenceAt(clock.asOf, clock.rankingDecayK) }
                    .thenBy(keyOrder) { it.first },
            )
            .take(limit)
            .map { it.first }
    }

    fun eligible(spec: ScopedReadSpec, clock: ReadClock): List<Proposition> = rows().toList()
        .filter { proposition ->
            val key = PropositionKey(proposition.contextId.value, proposition.id)
            key.contextIdValue in spec.scope.contextIdValues &&
                (spec.includedKeys == null || key in spec.includedKeys) &&
                key !in spec.excludedKeys &&
                spec.fragments.all { it.matchesFilters(proposition, clock.asOf) }
        }
        .distinctBy { PropositionKey(it.contextId.value, it.id) }

    private val keyOrder = compareBy(CodePointOrder) { p: Proposition -> p.contextId.value }.thenBy(CodePointOrder) { it.id }
}

/** Trims, lowercases and de-duplicates search tokens and drops blank ones, the way the native stores match them. */
internal fun keywordTokens(tokens: List<String>): List<String> =
    tokens.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()

/** How many of the already normalised [tokens] appear in [text], ignoring case. */
internal fun keywordHits(text: String, tokens: List<String>): Int {
    val lowered = text.lowercase(Locale.ROOT)
    return tokens.count { it in lowered }
}
