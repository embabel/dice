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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.dice.agent.MemoryRetriever
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus

/**
 * Shared retrieval and formatting helpers for [DiceMcpTools].
 *
 * Delegates hybrid recall to [MemoryRetriever] so MCP and in-process agent tools stay aligned
 * on vector + keyword + entity-expansion behaviour without duplicating ranking logic.
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
        PropositionQuery.forContextId(ContextId(contextId))
            .withMinEffectiveConfidence(minConfidence)
            .withStatuses(setOf(PropositionStatus.ACTIVE))

    fun recall(
        repository: PropositionRepository,
        contextId: String,
        query: String?,
        limit: Int,
        minConfidence: Double,
    ): String {
        val base = baseQuery(contextId, minConfidence)
        val retriever = MemoryRetriever(repository, provenanceResolver = null, topic = contextId, eagerIds = emptySet())
        val result = if (query.isNullOrBlank()) {
            retriever.listAll(base, limit)
        } else {
            retriever.search(query.trim(), base, limit)
        }
        return (result as? Tool.Result.Text)?.content ?: result.toString()
    }

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
