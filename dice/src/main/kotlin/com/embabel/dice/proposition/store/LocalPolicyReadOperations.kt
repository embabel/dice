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

import com.embabel.dice.proposition.ApprovedText
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictReadRow
import java.time.Duration

/**
 * Strict reads for the local stores. Granted rows are fetched by key, never by scanning the store,
 * and raw rows are returned only for trusted policy projection.
 *
 * @param lookup finds the row stored under a proposition id, or null. The local stores key rows by
 *   id alone, so the row it returns may belong to another context; this class checks that.
 */
internal class LocalPolicyReadOperations(
    private val scoped: LocalScopedReadOperations,
    private val lookup: (String) -> Proposition?,
) {

    fun queryByGrantOrder(
        spec: ScopedReadSpec,
        clock: ReadClock,
        rows: List<StrictReadRow>,
        limit: Int,
        timeout: Duration,
    ): List<Proposition> {
        val grants = rows.toList()
        StrictReadRow.validateRequest(spec, grants, limit, timeout)
        if (limit == 0 || grants.isEmpty()) return emptyList()
        val deadline = LocalReadDeadline(timeout, ScopedReadFailure.SCOPED_READ_FAILED, scoped.nanoTime)
        val result = granted(spec, clock, grants, deadline).map { it.second }.take(limit)
        deadline.check()
        return result
    }

    fun keywordByApprovedText(
        spec: ScopedReadSpec,
        clock: ReadClock,
        rows: List<StrictReadRow>,
        tokens: List<String>,
        limit: Int,
        timeout: Duration,
    ): List<Proposition> {
        val grants = rows.toList()
        StrictReadRow.validateRequest(spec, grants, limit, timeout)
        if (limit == 0 || grants.isEmpty()) return emptyList()
        val normalized = keywordTokens(tokens)
        if (normalized.isEmpty()) return emptyList()
        val deadline = LocalReadDeadline(timeout, ScopedReadFailure.SCOPED_READ_FAILED, scoped.nanoTime)
        // Rows come back in grant order and sortedByDescending is stable, so equal scores keep grant order.
        val result = granted(spec, clock, grants, deadline)
            .mapNotNull { (grant, proposition) ->
                val approved = when (val text = grant.text) {
                    ApprovedText.Stored -> proposition.text
                    is ApprovedText.Literal -> text.value
                    ApprovedText.Omitted -> return@mapNotNull null
                }
                val score = keywordHits(approved, normalized)
                if (score > 0) proposition to score else null
            }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
        deadline.check()
        return result
    }

    /** Each grant's stored row, in grant order, when the spec admits it; one deadline check per grant. */
    private fun granted(
        spec: ScopedReadSpec,
        clock: ReadClock,
        grants: List<StrictReadRow>,
        deadline: LocalReadDeadline,
    ): List<Pair<StrictReadRow, Proposition>> = grants.mapNotNull { grant ->
        deadline.check()
        val row = lookup(grant.key.propositionId) ?: return@mapNotNull null
        // A grant for one context must never return a row another context stored under the same id.
        if (row.contextId.value == grant.key.contextIdValue && scoped.admits(spec, clock, row)) grant to row else null
    }
}
