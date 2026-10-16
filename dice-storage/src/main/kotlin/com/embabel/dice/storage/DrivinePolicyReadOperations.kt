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

import com.embabel.dice.proposition.ApprovedText
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictReadRow
import com.embabel.dice.proposition.store.ScopedReadRules
import java.time.Duration

/**
 * Strict policy reads over granted rows. Callers pass only approved search text; rows go back to the
 * policy layer, which decides what to show.
 */
internal class DrivinePolicyReadOperations(private val reads: DrivineScopedReadOperations) {
    fun queryByGrantOrder(
        spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>, limit: Int, timeout: Duration,
    ): List<Proposition> {
        StrictReadRow.validateRequest(spec, rows, limit, timeout)
        if (limit == 0 || rows.isEmpty()) return emptyList()
        return reads.rows(queryStatement(spec, clock, rows, limit), timeout)
    }

    fun keywordByApprovedText(
        spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>,
        tokens: List<String>, limit: Int, timeout: Duration,
    ): List<Proposition> {
        StrictReadRow.validateRequest(spec, rows, limit, timeout)
        if (limit == 0 || rows.isEmpty()) return emptyList()
        val normalized = ScopedReadRules.keywordTokens(tokens)
        if (normalized.isEmpty() || rows.all { it.text == ApprovedText.Omitted }) return emptyList()
        return reads.rows(keywordStatement(spec, clock, rows, normalized, limit), timeout)
    }

    companion object {
        private fun row(row: StrictReadRow, ordinal: Int): Map<String, Any> = mapOf(
            "contextId" to row.key.contextIdValue, "id" to row.key.propositionId, "ordinal" to ordinal,
            "text" to ((row.text as? ApprovedText.Literal)?.value ?: ""),
        )

        internal fun queryStatement(
            spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>, limit: Int,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(spec, clock)
            return """
                UNWIND ${'$'}grantRows AS row
                MATCH (p:Proposition {contextId: row.contextId, id: row.id}) WHERE ${compiled.predicate}
                WITH p, row ORDER BY row.ordinal ASC LIMIT ${'$'}limit
                RETURN p, [(p)-[:HAS_MENTION]->(m:Mention) | m] AS mentions
            """.trimIndent() to
                (compiled.parameters + mapOf("grantRows" to rows.mapIndexed { index, grant -> row(grant, index) }, "limit" to limit))
        }

        internal fun keywordStatement(
            spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>, tokens: List<String>, limit: Int,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(spec, clock)
            val literals = rows.withIndex().filter { it.value.text is ApprovedText.Literal }.map { row(it.value, it.index) }
            val stored = rows.withIndex().filter { it.value.text == ApprovedText.Stored }.map { row(it.value, it.index) }
            fun branch(parameter: String, text: String): String = """
                UNWIND ${'$'}$parameter AS row
                MATCH (p:Proposition {contextId: row.contextId, id: row.id}) WHERE ${compiled.predicate}
                WITH p, row, size([token IN ${'$'}tokens WHERE toLower($text) CONTAINS token]) AS overlap
                WHERE overlap > 0
                RETURN p, row.ordinal AS ordinal, overlap
            """.trimIndent()
            // Literal and stored rows score in separate branches, so a replacement row never reads the stored text.
            val branches = buildList {
                if (literals.isNotEmpty()) add(branch("literalRows", "row.text"))
                if (stored.isNotEmpty()) add(branch("storedRows", "p.text"))
            }
            return """
                |CALL {
                |${branches.joinToString("\nUNION ALL\n")}
                |}
                |WITH p, ordinal, overlap ORDER BY overlap DESC, ordinal ASC LIMIT ${'$'}limit
                |RETURN p, [(p)-[:HAS_MENTION]->(m:Mention) | m] AS mentions
            """.trimMargin() to
                (compiled.parameters + mapOf("literalRows" to literals, "storedRows" to stored,
                    "tokens" to tokens, "limit" to limit))
        }
    }
}
