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
package com.embabel.dice.proposition

import java.time.Instant
import java.util.Collections

/** One proposition named by a grant or an exclusion. IDs are UUIDs; the context is in the key so a grant cannot name a row outside the scope. */
data class PropositionKey(val contextIdValue: String, val propositionId: String)

/** The instant a read is evaluated at, and the decay rate it ranks effective confidence with. */
data class ReadClock(val asOf: Instant, val rankingDecayK: Double) {
    init {
        require(rankingDecayK.isFinite() && rankingDecayK >= 0) { "Ranking decay must be finite and not negative" }
    }
}

/** Which rows a scoped read may return: its scope, criteria every row matches, optional grants and exclusions. No clock. */
class ScopedReadSpec private constructor(
    /** The contexts this spec reads from. */
    val scope: ContextReadScope,
    val fragments: List<PropositionQuery>,
    val includedKeys: Set<PropositionKey>?,
    val excludedKeys: Set<PropositionKey>,
) {

    /** Adds criteria every row must also match. Ordering, limits and the instant belong to the read. */
    fun restrict(criteria: PropositionQuery): ScopedReadSpec {
        require(criteria.orderBy == PropositionQuery.OrderBy.NONE) { "Criteria must not carry an order; the read chooses it" }
        require(criteria.limit == null) { "Criteria must not carry a limit; the read chooses it" }
        require(criteria.effectiveConfidenceAsOf == null) { "Criteria must not carry an instant; the read clock sets it" }
        require(criteria.contextId == null || criteria.contextId.value in scope.contextIdValues) {
            "Query context is outside the read scope"
        }
        // Copy rather than list fields, so a field added to the query later is kept, never dropped.
        val snapshot = criteria.copy(
            anyEntityIds = criteria.anyEntityIds?.let { immutableList(it) },
            allEntityIds = criteria.allEntityIds?.let { immutableList(it) },
            statuses = criteria.statuses?.let { immutableSet(it) },
        )
        return ScopedReadSpec(scope, immutableList(fragments + snapshot), includedKeys, excludedKeys)
    }

    /** Keeps only the rows named here. Grants intersect, so an empty grant denies every row. */
    fun includingOnly(keys: Collection<PropositionKey>): ScopedReadSpec {
        val snapshot = checkedKeys(keys)
        val intersection = includedKeys?.intersect(snapshot) ?: snapshot
        return ScopedReadSpec(scope, fragments, immutableSet(intersection), excludedKeys)
    }

    /** Leaves out the rows named here. Exclusions accumulate. */
    fun excluding(keys: Collection<PropositionKey>): ScopedReadSpec {
        val snapshot = checkedKeys(keys)
        return ScopedReadSpec(scope, fragments, includedKeys, immutableSet(excludedKeys + snapshot))
    }

    private fun checkedKeys(keys: Collection<PropositionKey>): Set<PropositionKey> {
        val snapshot = immutableSet(keys)
        require(snapshot.all { it.contextIdValue in scope.contextIdValues }) {
            "Proposition key context is outside the read scope"
        }
        return snapshot
    }

    companion object {
        /** An unrestricted spec over [scope]. */
        @JvmStatic
        fun of(scope: ContextReadScope): ScopedReadSpec =
            ScopedReadSpec(scope, immutableList(emptyList()), null, immutableSet(emptySet()))

        private fun <T> immutableList(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))

        private fun <T> immutableSet(values: Collection<T>): Set<T> =
            Collections.unmodifiableSet(LinkedHashSet(values))
    }
}
