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

import com.embabel.dice.proposition.ApprovedSearchText
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictReadRow
import java.time.Duration
import java.util.Locale

/** Strict local selection; raw rows are returned only for trusted policy projection. */
internal class LocalPolicyReadOperations(private val scoped: LocalScopedReadOperations) {

    fun queryByGrantOrder(
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        rows: List<StrictReadRow>,
        limit: Int,
        timeout: Duration,
    ): List<Proposition> {
        val grants = rows.toList()
        StrictReadRow.validateRequest(scope, spec, grants, limit, timeout)
        if (limit == 0 || grants.isEmpty()) return emptyList()
        val checkDeadline = deadline(timeout)
        checkDeadline()
        val byKey = grants.associateBy { it.key }
        val restricted = spec.includingOnly(scope, byKey.keys)
        val result = scoped.eligibleRows(scope, restricted, checkDeadline)
            .sortedBy { byKey.getValue(PropositionKey(it.contextId.value, it.id)).ordinal }
            .take(limit).toList()
        checkDeadline()
        return result
    }

    fun keywordByApprovedText(
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        rows: List<StrictReadRow>,
        tokens: List<String>,
        limit: Int,
        timeout: Duration,
    ): List<Proposition> {
        val grants = rows.toList()
        StrictReadRow.validateRequest(scope, spec, grants, limit, timeout)
        if (limit == 0 || grants.isEmpty()) return emptyList()
        val checkDeadline = deadline(timeout)
        checkDeadline()
        val normalized = tokens.map {
            checkDeadline()
            it.trim().lowercase(Locale.ROOT)
        }.filter { it.isNotEmpty() }.distinct()
        checkDeadline()
        if (normalized.isEmpty()) return emptyList()
        val byKey = grants.associateBy { it.key }
        val restricted = spec.includingOnly(scope, byKey.keys)
        val result = scoped.eligibleRows(scope, restricted, checkDeadline)
            .mapNotNull { proposition ->
                checkDeadline()
                val grant = byKey.getValue(PropositionKey(proposition.contextId.value, proposition.id))
                val approved = when (val text = grant.text) {
                    ApprovedSearchText.Stored -> proposition.text
                    is ApprovedSearchText.Literal -> text.value
                    ApprovedSearchText.Omitted -> return@mapNotNull null
                }.lowercase(Locale.ROOT)
                val score = normalized.count {
                    checkDeadline()
                    it in approved
                }
                if (score > 0) Triple(proposition, score, grant.ordinal) else null
            }
            .sortedWith(compareByDescending<Triple<Proposition, Int, Int>> { it.second }.thenBy { it.third })
            .take(limit).map { it.first }.toList()
        checkDeadline()
        return result
    }

    private fun deadline(timeout: Duration): () -> Unit {
        val started = System.nanoTime()
        val timeoutNanos = timeout.toNanos()
        return {
            if (System.nanoTime() - started >= timeoutNanos) throw ScopedReadException("SCOPED_READ_FAILED")
        }
    }
}
