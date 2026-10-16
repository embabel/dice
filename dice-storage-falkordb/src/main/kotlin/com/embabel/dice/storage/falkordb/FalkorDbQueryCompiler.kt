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
package com.embabel.dice.storage.falkordb

import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import java.time.Instant
import java.util.Locale

internal data class FalkorDbRead(val statement: String, val parameters: Map<String, Any?>)

/** Compiles DICE membership and presentation into a single parameterized native read. */
internal class FalkorDbQueryCompiler {
    private val parameters = linkedMapOf<String, Any?>()
    private val predicates = mutableListOf<String>()

    private fun bind(value: Any?): String {
        val name = "v${parameters.size}"
        parameters[name] = value
        return "$$name"
    }

    private fun compareInstant(field: String, value: Instant, operator: String): String {
        val seconds = bind(value.epochSecond)
        val nanos = bind(value.nano)
        val strict = if (operator == ">=") ">" else "<"
        return "(p.${field}S $strict $seconds OR (p.${field}S = $seconds AND p.${field}N $operator $nanos))"
    }

    private fun effective(asOf: Instant, k: Double): String {
        val atOrAfterInvalidation = compareInstant("invalidated", asOf, "<=")
        val beforeStart = compareInstant("validFrom", asOf, ">=")
        val afterEnd = compareInstant("validTo", asOf, "<=")
        val seconds = bind(asOf.epochSecond)
        val nanos = bind(asOf.nano)
        val rate = bind(k)
        fun decayed(anchor: String): String {
            val elapsed = "($seconds - p.${anchor}S - CASE WHEN $nanos < p.${anchor}N THEN 1 ELSE 0 END)"
            val days = "CASE WHEN $elapsed < 0 THEN 0 ELSE floor($elapsed / 86400.0) END"
            return "p.confidence * exp(-p.decay * $rate * ($days))"
        }
        return "CASE WHEN p.invalidatedS IS NOT NULL AND $atOrAfterInvalidation THEN 0.0 " +
            "WHEN p.validFromS IS NOT NULL THEN CASE " +
            "WHEN ($beforeStart AND NOT (p.validFromS = $seconds AND p.validFromN = $nanos)) " +
            "OR (p.validToS IS NOT NULL AND $afterEnd) THEN 0.0 " +
            "WHEN p.validToS IS NOT NULL THEN p.confidence ELSE ${decayed("validFrom")} END " +
            "ELSE ${decayed("anchor")} END"
    }

    private fun filters(q: PropositionQuery, asOf: Instant) {
        q.contextId?.let { predicates += "p.context = ${bind(it.value)}" }
        q.entityId?.let { predicates += "${bind(it)} IN p.entities" }
        q.anyEntityIds?.let { predicates += "any(entity IN ${bind(it)} WHERE entity IN p.entities)" }
        q.allEntityIds?.let { predicates += "all(entity IN ${bind(it)} WHERE entity IN p.entities)" }
        q.statuses?.takeIf { it.isNotEmpty() }?.let { predicates += "p.status IN ${bind(it.map { s -> s.name })}" }
        q.minLevel?.let { predicates += "p.level >= ${bind(it)}" }
        q.maxLevel?.let { predicates += "p.level <= ${bind(it)}" }
        listOf("created" to (q.createdAfter to q.createdBefore), "revised" to (q.revisedAfter to q.revisedBefore),
            "accessed" to (q.accessedAfter to q.accessedBefore)).forEach { (name, bounds) ->
            bounds.first?.let { predicates += compareInstant(name, it, ">=") }
            bounds.second?.let { predicates += compareInstant(name, it, "<=") }
        }
        q.minConfidence?.let { predicates += "p.confidence >= ${bind(it)}" }
        q.minEffectiveConfidence?.let { predicates += "(${effective(asOf, q.decayK)}) >= ${bind(it)}" }
        q.belowEffectiveConfidence?.let { predicates += "(${effective(asOf, q.decayK)}) < ${bind(it)}" }
        q.minImportance?.let { predicates += "p.importance >= ${bind(it)}" }
        q.minReinforceCount?.let { predicates += "p.reinforceCount >= ${bind(it)}" }
        q.minTrustScore?.let { predicates += "(p.trust IS NULL OR p.trust >= ${bind(it)})" }
        q.pinned?.let { predicates += "p.pinned = ${bind(it)}" }
    }

    fun legacy(q: PropositionQuery, count: Boolean = false, tokens: List<String>? = null, limit: Int? = q.limit): FalkorDbRead {
        val asOf = q.effectiveConfidenceAsOf ?: Instant.now()
        filters(q, asOf)
        return finish(ReadOrder.valueOf(q.orderBy.name), asOf, q.decayK, limit, count, tokens, false, q.limit)
    }

    fun scoped(
        scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder = ReadOrder.NONE,
        limit: Int? = null, count: Boolean = false, tokens: List<String>? = null,
    ): FalkorDbRead {
        predicates += "p.context IN ${bind(scope.contextIdValues.toList())}"
        spec.fragments.forEach { filters(it, spec.asOf) }
        spec.includedKeys?.let { keys ->
            predicates += if (keys.isEmpty()) "false" else keys.joinToString(" OR ", "(", ")") {
                "(p.context = ${bind(it.contextIdValue)} AND p.id = ${bind(it.propositionId)})"
            }
        }
        spec.excludedKeys.forEach { predicates += "NOT (p.context = ${bind(it.contextIdValue)} AND p.id = ${bind(it.propositionId)})" }
        return finish(order, spec.asOf, spec.rankingDecayK, limit, count, tokens, true)
    }

    private fun finish(
        order: ReadOrder, asOf: Instant, k: Double, limit: Int?, count: Boolean, tokens: List<String>?, scoped: Boolean, baseLimit: Int? = null,
    ): FalkorDbRead {
        require(limit == null || limit >= 0) { "Limit must not be negative" }
        var statement = "MATCH (p:DiceProposition)"
        if (predicates.isNotEmpty()) statement += " WHERE " + predicates.joinToString(" AND ")
        if (tokens != null) {
            if (!scoped && baseLimit != null) {
                require(baseLimit >= 0) { "Limit must not be negative" }
                statement += " WITH p ORDER BY (${effective(asOf, k)}) DESC, id(p) LIMIT ${bind(baseLimit)}"
            }
            val captured = if (scoped) tokens.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
            else tokens.map(FalkorDbPropositionCodec::foldCase)
            val text = if (scoped) "p.text" else "p.legacyText"
            statement += " WITH p, size([token IN ${bind(captured)} WHERE $text CONTAINS token]) AS overlap WHERE overlap > 0"
        }
        if (count) {
            // Legacy count includes presentation limits, unlike scoped membership-only count.
            if (limit != null) statement += " WITH p LIMIT ${bind(limit)}"
            statement += " RETURN {value: count(p)} AS result"
        } else {
            val primary = when (order) {
                ReadOrder.NONE -> emptyList()
                ReadOrder.EFFECTIVE_CONFIDENCE_DESC -> listOf("(${effective(asOf, k)}) DESC")
                ReadOrder.CREATED_DESC -> listOf("p.createdS DESC", "p.createdN DESC")
                ReadOrder.REVISED_DESC -> listOf("p.revisedS DESC", "p.revisedN DESC")
                ReadOrder.LAST_ACCESSED_DESC -> listOf("p.accessedS DESC", "p.accessedN DESC")
                ReadOrder.REINFORCE_COUNT_DESC -> listOf("p.reinforceCount DESC")
                ReadOrder.IMPORTANCE_DESC -> listOf("p.importance DESC")
            }
            val ordering = (if (tokens != null) listOf("overlap DESC") else emptyList()) + primary +
                if (scoped) listOf("p.contextOrder", "p.idOrder") else listOf("id(p)")
            statement += " WITH p" + (if (tokens != null) ", overlap" else "") + " ORDER BY " + ordering.joinToString(", ")
            if (limit != null) statement += " LIMIT ${bind(limit)}"
            statement += " RETURN {aggregate: p.aggregate} AS result"
        }
        return FalkorDbRead(statement, parameters.toMap())
    }
}
