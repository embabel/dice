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

import com.embabel.dice.proposition.ApprovedSearchText
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictReadRow
import java.time.Duration
import java.util.Locale

/** Native strict selection returns raw rows only to the trusted projection adapter. */
internal class DrivinePolicyReadOperations(
    private val executor: Neo4jScopedReadExecutor,
    private val reads: DrivineScopedReadOperations,
) {
    fun queryByGrantOrder(
        scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>, limit: Int, timeout: Duration,
    ): List<Proposition> {
        val grants = rows.toList()
        StrictReadRow.validateRequest(scope, spec, grants, limit, timeout)
        if (limit == 0 || grants.isEmpty()) return emptyList()
        return selected(scope, spec, grants, timeout, queryStatement(scope, spec, grants, limit))
    }

    fun keywordByApprovedText(
        scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>,
        tokens: List<String>, limit: Int, timeout: Duration,
    ): List<Proposition> {
        val grants = rows.toList()
        StrictReadRow.validateRequest(scope, spec, grants, limit, timeout)
        if (limit == 0 || grants.isEmpty()) return emptyList()
        val normalized = tokens.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty() || grants.all { it.text == ApprovedSearchText.Omitted }) return emptyList()
        return selected(scope, spec, grants, timeout, keywordStatement(scope, spec, grants, normalized, limit))
    }

    private fun selected(
        scope: ContextReadScope, spec: ScopedReadSpec, grants: List<StrictReadRow>, timeout: Duration,
        selection: Pair<String, Map<String, Any?>>,
    ): List<Proposition> = executor.read(timeout) { tx ->
        val (statement, parameters) = selection
        val keys = tx.run(statement, parameters).list {
            PropositionKey(it["contextId"].asString(), it["id"].asString())
        }
        reads.hydrate(tx, scope, spec.includingOnly(scope, grants.map { it.key }), keys)
    }

    companion object {
        private fun row(row: StrictReadRow): Map<String, Any> = mapOf(
            "contextId" to row.key.contextIdValue, "id" to row.key.propositionId, "ordinal" to row.ordinal,
            "text" to ((row.text as? ApprovedSearchText.Literal)?.value ?: ""),
        )

        internal fun queryStatement(
            scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>, limit: Int,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(scope, spec)
            return ("UNWIND $" + "grantRows AS row " +
                "MATCH (p:Proposition {contextId: row.contextId, id: row.id}) WHERE ${compiled.predicate} " +
                "RETURN p.contextId AS contextId, p.id AS id ORDER BY row.ordinal ASC LIMIT $" + "limit") to
                (compiled.parameters + mapOf("grantRows" to rows.map(::row), "limit" to limit))
        }

        internal fun keywordStatement(
            scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>, tokens: List<String>, limit: Int,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(scope, spec)
            val literals = rows.filter { it.text is ApprovedSearchText.Literal }
            val stored = rows.filter { it.text == ApprovedSearchText.Stored }
            fun branch(parameter: String, text: String): String =
                "UNWIND $" + parameter + " AS row " +
                    "MATCH (p:Proposition {contextId: row.contextId, id: row.id}) WHERE ${compiled.predicate} " +
                    "WITH p, row, size([token IN $" + "tokens WHERE toLower($text) CONTAINS token]) AS overlap " +
                    "WHERE overlap > 0 RETURN p.contextId AS contextId, p.id AS id, row.ordinal AS ordinal, overlap"
            // Separate branches prevent replacement rows from accessing the stored text property for scoring.
            val branches = mutableListOf<String>()
            if (literals.isNotEmpty()) branches += branch("literalRows", "row.text")
            if (stored.isNotEmpty()) branches += branch("storedRows", "p.text")
            require(branches.isNotEmpty()) { "No approved searchable text" }
            return ("CALL { " + branches.joinToString(" UNION ALL ") + " } " +
                "RETURN contextId, id ORDER BY overlap DESC, ordinal ASC LIMIT $" + "limit") to
                (compiled.parameters + mapOf("literalRows" to literals.map(::row), "storedRows" to stored.map(::row),
                    "tokens" to tokens, "limit" to limit))
        }
    }
}
