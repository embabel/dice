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

import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import java.time.Instant

internal data class CompiledScopedPredicate(val predicate: String, val parameters: Map<String, Any?>)

/** A WHERE predicate over one proposition root; correlated EXISTS never expands its identity. */
internal object ScopedReadCypher {
    fun compile(scope: ContextReadScope, spec: ScopedReadSpec): CompiledScopedPredicate {
        val parameters = linkedMapOf<String, Any?>(
            "scopeContexts" to scope.contextIdValues.toList(),
            "rankAsOf" to spec.asOf.toString(),
            "rankDecayK" to spec.rankingDecayK,
        )
        val predicates = mutableListOf("p.contextId IN $" + "scopeContexts")
        fun bind(name: String, value: Any): String {
            parameters[name] = if (value is Instant) value.toString() else value
            return if (value is Instant) "datetime($" + name + ")" else "$" + name
        }
        fun keys(keys: Set<PropositionKey>): List<Map<String, String>> = keys.map {
            mapOf("contextId" to it.contextIdValue, "propositionId" to it.propositionId)
        }
        spec.includedKeys?.let {
            predicates += "any(key IN ${bind("includedKeys", keys(it))} " +
                "WHERE key.contextId = p.contextId AND key.propositionId = p.id)"
        }
        if (spec.excludedKeys.isNotEmpty()) {
            predicates += "none(key IN ${bind("excludedKeys", keys(spec.excludedKeys))} " +
                "WHERE key.contextId = p.contextId AND key.propositionId = p.id)"
        }
        spec.fragments.forEachIndexed { index, query ->
            fun parameter(field: String, value: Any) = bind("f${index}_$field", value)
            fun compare(field: String, property: String, operator: String, value: Any?) {
                if (value != null) {
                    val compared = if (value is Instant) ScopedConfidenceCypher.instant(property) else property
                    predicates += "$compared $operator ${parameter(field, value)}"
                }
            }
            fun acceptUnless(field: String, property: String, rejectedBy: String, value: Double?) {
                // Kotlin rejects on this comparison. Its logical complement preserves NaN;
                // the opposite comparison operator would silently exclude accepted rows.
                if (value != null) predicates += "NOT ($property $rejectedBy ${parameter(field, value)})"
            }
            compare("contextId", "p.contextId", "=", query.contextId?.value)
            query.entityId?.let {
                predicates += "EXISTS { MATCH (p)-[:HAS_MENTION]->(m:Mention) " +
                    "WHERE m.resolvedId = ${parameter("entityId", it)} }"
            }
            query.anyEntityIds?.let {
                predicates += "EXISTS { MATCH (p)-[:HAS_MENTION]->(m:Mention) " +
                    "WHERE m.resolvedId IN ${parameter("anyEntityIds", it)} }"
            }
            query.allEntityIds?.let {
                predicates += "all(entity IN ${parameter("allEntityIds", it)} WHERE " +
                    "EXISTS { MATCH (p)-[:HAS_MENTION]->(m:Mention) WHERE m.resolvedId = entity })"
            }
            query.statuses?.takeIf { it.isNotEmpty() }?.let {
                predicates += "coalesce(p.status, 'ACTIVE') IN ${parameter("statuses", it.map { status -> status.name })}"
            }
            compare("minLevel", "coalesce(p.level, 0)", ">=", query.minLevel)
            compare("maxLevel", "coalesce(p.level, 0)", "<=", query.maxLevel)
            compare("createdAfter", "p.created", ">=", query.createdAfter)
            compare("createdBefore", "p.created", "<=", query.createdBefore)
            val revised = revised()
            compare("revisedAfter", revised, ">=", query.revisedAfter)
            compare("revisedBefore", revised, "<=", query.revisedBefore)
            compare("accessedAfter", "coalesce(p.lastAccessed, p.created)", ">=", query.accessedAfter)
            compare("accessedBefore", "coalesce(p.lastAccessed, p.created)", "<=", query.accessedBefore)
            acceptUnless("minConfidence", "p.confidence", "<", query.minConfidence)
            if (query.minEffectiveConfidence != null || query.belowEffectiveConfidence != null) {
                parameter("asOf", spec.asOf)
                parameter("decayK", query.decayK)
                val confidence = ScopedConfidenceCypher.expression("p", "f${index}_asOf", "f${index}_decayK")
                acceptUnless("minEffectiveConfidence", confidence, "<", query.minEffectiveConfidence)
                acceptUnless("belowEffectiveConfidence", confidence, ">=", query.belowEffectiveConfidence)
            }
            acceptUnless("minImportance", "coalesce(p.importance, 0.5)", "<", query.minImportance)
            compare("minReinforceCount", "coalesce(p.reinforceCount, 0)", ">=", query.minReinforceCount)
            compare("pinned", "coalesce(p.pinned, false)", "=", query.pinned)
            query.minTrustScore?.let {
                // PropertyBag writes metadata as flat metadata.<key> properties. A string that
                // looks numeric is still unscored; only finite Numbers constrain membership.
                val score = "p.`metadata.dice.trust.score`"
                val finite = parameter("finiteMax", Double.MAX_VALUE)
                val threshold = parameter("minTrustScore", it)
                predicates += "(CASE WHEN $score IS :: INTEGER OR $score IS :: FLOAT THEN " +
                    "CASE WHEN abs($score) <= $finite THEN $score >= $threshold ELSE true END ELSE true END)"
            }
        }
        return CompiledScopedPredicate(predicates.joinToString(" AND ") { "($it)" }, parameters)
    }

    private fun revised(): String {
        val content = "coalesce(p.contentRevised, p.created)"
        val metadata = "coalesce(p.metadataRevised, p.created)"
        return "coalesce(p.lastTouched, CASE WHEN ${ScopedConfidenceCypher.instant(content)} >= " +
            "${ScopedConfidenceCypher.instant(metadata)} THEN $content ELSE $metadata END)"
    }

    fun order(order: ReadOrder, spec: ScopedReadSpec): String {
        val primary = when (order) {
            ReadOrder.NONE -> null
            ReadOrder.EFFECTIVE_CONFIDENCE_DESC ->
                ScopedConfidenceCypher.expression("p", "rankAsOf", "rankDecayK")
            ReadOrder.CREATED_DESC -> ScopedConfidenceCypher.instant("p.created")
            ReadOrder.REVISED_DESC -> ScopedConfidenceCypher.instant(revised())
            ReadOrder.LAST_ACCESSED_DESC -> ScopedConfidenceCypher.instant("coalesce(p.lastAccessed, p.created)")
            ReadOrder.REINFORCE_COUNT_DESC -> "coalesce(p.reinforceCount, 0)"
            ReadOrder.IMPORTANCE_DESC -> "coalesce(p.importance, 0.5)"
        }
        return (primary?.let { "$it DESC, " } ?: "") + "p.contextId ASC, p.id ASC"
    }
}
