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
package com.embabel.dice.mcp

import com.embabel.agent.core.ContextId
import com.embabel.dice.agent.MemoryRetriever
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus

/**
 * Shared retrieval and formatting helpers for [DiceMcpTools].
 *
 * Delegates hybrid recall to [MemoryRetriever] so MCP and in-process [com.embabel.dice.agent.Memory]
 * stay aligned on vector + keyword + entity-expansion behaviour without duplicating ranking logic.
 */
internal object DiceMcpSupport {

    /**
     * Base query for MCP recall/list: scoped to one [contextId], filtered to [PropositionStatus.ACTIVE]
     * propositions at or above [minConfidence] effective confidence.
     *
     * STALE / SUPERSEDED / CONTRADICTED propositions are excluded by default — the same guard
     * [com.embabel.dice.agent.Memory] applies before results reach an LLM.
     */
    fun baseQuery(contextId: String, minConfidence: Double): PropositionQuery =
        PropositionQuery.forContextId(ContextId(requireContextId(contextId)))
            .withMinEffectiveConfidence(minConfidence)
            .withStatuses(setOf(PropositionStatus.ACTIVE))

    fun requireContextId(contextId: String): String {
        val scoped = contextId.trim()
        require(scoped.isNotBlank()) { "context_id must not be blank" }
        return scoped
    }

    /**
     * Hybrid recall, or confidence-ordered listing when [query] is absent.
     *
     * Ranking is delegated to [MemoryRetriever] so MCP and in-process
     * [com.embabel.dice.agent.Memory] cannot drift, but the results are rendered here with
     * [formatProposition] — the same shape `dice_list` and `dice_get` emit. That keeps one
     * output format across the four tools and, critically, puts the proposition id on every
     * line so a client can follow a recall hit into `dice_get`.
     */
    fun recall(
        repository: PropositionRepository,
        contextId: String,
        query: String?,
        limit: Int,
        minConfidence: Double,
    ): String {
        val scoped = requireContextId(contextId)
        val base = baseQuery(scoped, minConfidence)
        val retriever = MemoryRetriever(repository, provenanceResolver = null, topic = scoped, eagerIds = emptySet())
        val trimmed = query?.trim()?.takeIf { it.isNotBlank() }
        val hits = if (trimmed == null) {
            retriever.listRanked(base, limit)
        } else {
            retriever.rankedPropositions(trimmed, base, limit)
        }
        if (hits.isEmpty()) {
            return if (trimmed == null) "No memories in context '$scoped'."
            else "No memories matched '$trimmed' in context '$scoped'."
        }
        val header =
            if (trimmed == null) "Found ${hits.size} memories in context '$scoped':"
            else "Found ${hits.size} memories matching '$trimmed' in context '$scoped':"
        return render(header, hits)
    }

    /** Numbered rendering shared by `dice_recall` and `dice_list`. */
    fun render(header: String, propositions: List<Proposition>): String =
        buildString {
            appendLine(header)
            propositions.forEachIndexed { index, proposition ->
                appendLine("${index + 1}. ${formatProposition(proposition)}")
            }
        }.trimEnd()

    fun formatProposition(proposition: Proposition): String =
        buildString {
            append("id=${proposition.id}")
            append(" | confidence=${"%.2f".format(proposition.effectiveConfidence())}")
            append(" | ${proposition.text}")
            if (proposition.mentions.isNotEmpty()) {
                val entities = proposition.mentions.joinToString("; ") { mention ->
                    "${mention.span} (${mention.type})"
                }
                append(" | entities: $entities")
            }
        }
}
