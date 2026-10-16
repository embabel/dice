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

/** Proposition IDs are unique within a context, so grants always use both values. */
data class PropositionKey(val contextIdValue: String, val propositionId: String)

/** Immutable conjunctions and row grants for a read at one captured instant. */
class ScopedReadSpec private constructor(
    val fragments: List<PropositionQuery>,
    val includedKeys: Set<PropositionKey>?,
    val excludedKeys: Set<PropositionKey>,
    val asOf: Instant,
    val rankingDecayK: Double,
) {

    fun restrict(scope: ContextReadScope, criteria: PropositionQuery): ScopedReadSpec {
        require(criteria.contextId == null || criteria.contextId.value in scope.contextIdValues) {
            "Query context is outside the read scope"
        }
        require(criteria.effectiveConfidenceAsOf == null || criteria.effectiveConfidenceAsOf == asOf) {
            "Query effective confidence instant conflicts with the read instant"
        }
        // Spell out every membership field so new query fields require an explicit policy decision.
        val snapshot = PropositionQuery(
            contextId = criteria.contextId,
            entityId = criteria.entityId,
            anyEntityIds = criteria.anyEntityIds?.let { immutableList(it) },
            allEntityIds = criteria.allEntityIds?.let { immutableList(it) },
            statuses = criteria.statuses?.let { immutableSet(it) },
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
            effectiveConfidenceAsOf = asOf,
            decayK = criteria.decayK,
            minImportance = criteria.minImportance,
            minReinforceCount = criteria.minReinforceCount,
            minTrustScore = criteria.minTrustScore,
            pinned = criteria.pinned,
            orderBy = PropositionQuery.OrderBy.NONE,
            limit = null,
            belowEffectiveConfidence = criteria.belowEffectiveConfidence,
        )
        return ScopedReadSpec(
            immutableList(fragments + snapshot), includedKeys, excludedKeys, asOf, rankingDecayK,
        )
    }

    fun includingOnly(scope: ContextReadScope, keys: Collection<PropositionKey>): ScopedReadSpec {
        val snapshot = checkedKeys(scope, keys)
        val intersection = includedKeys?.intersect(snapshot) ?: snapshot
        return ScopedReadSpec(fragments, immutableSet(intersection), excludedKeys, asOf, rankingDecayK)
    }

    fun excluding(scope: ContextReadScope, keys: Collection<PropositionKey>): ScopedReadSpec {
        val snapshot = checkedKeys(scope, keys)
        return ScopedReadSpec(fragments, includedKeys, immutableSet(excludedKeys + snapshot), asOf, rankingDecayK)
    }

    private fun checkedKeys(scope: ContextReadScope, keys: Collection<PropositionKey>): Set<PropositionKey> {
        val snapshot = immutableSet(keys)
        require(snapshot.all { it.contextIdValue in scope.contextIdValues }) {
            "Proposition key context is outside the read scope"
        }
        return snapshot
    }

    companion object {
        @JvmStatic
        fun at(asOf: Instant, rankingDecayK: Double): ScopedReadSpec =
            ScopedReadSpec(immutableList(emptyList()), null, immutableSet(emptySet()), asOf, rankingDecayK)

        private fun <T> immutableList(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))

        private fun <T> immutableSet(values: Collection<T>): Set<T> =
            Collections.unmodifiableSet(LinkedHashSet(values))
    }
}
