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

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.ZeroToOne
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * A mention suggested by the LLM during proposition extraction.
 * Lighter weight than EntityMention - contains only what the LLM provides.
 *
 * @property span The text as it appears in the proposition
 * @property type Entity type label from schema (e.g., "Person")
 * @property suggestedId Entity ID if the LLM can identify a specific entity
 * @property role The role of this mention in the proposition
 */
data class SuggestedMention(
    @param:JsonPropertyDescription("The text as it appears in the proposition (e.g., Jim). No quotes. Must be legal in a JSON string")
    val span: String,
    @param:JsonPropertyDescription("Suggested entity type from schema (e.g., 'Person', 'Technology')")
    val type: String,
    @param:JsonPropertyDescription("Entity ID if identifiable, null otherwise")
    val suggestedId: String? = null,
    @param:JsonPropertyDescription("Role: SUBJECT, OBJECT, or OTHER")
    val role: String = "OTHER",
) {
    /**
     * Convert to EntityMention with the resolved ID (if any).
     */
    fun toEntityMention(resolvedId: String? = suggestedId): EntityMention =
        EntityMention(
            span = span,
            type = type,
            resolvedId = resolvedId,
            role = parseRole(),
            hints = buildHints(),
        )

    private fun parseRole(): MentionRole = try {
        MentionRole.valueOf(role.uppercase())
    } catch (e: IllegalArgumentException) {
        MentionRole.OTHER
    }

    private fun buildHints(): Map<String, Any> = buildMap {
        suggestedId?.let { put("suggestedId", it) }
    }
}

/**
 * A proposition suggested by the LLM from chunk analysis.
 * This is the output type from the propose_facts prompt.
 *
 * @property text The factual statement in natural language
 * @property mentions Entities referenced in the statement
 * @property confidence LLM's certainty (0.0-1.0)
 * @property decay How quickly this information becomes stale (0.0-1.0)
 * @property importance How much this fact matters to remember (0.0-1.0)
 * @property reasoning LLM's explanation for extracting this
 */
data class SuggestedProposition(
    @param:JsonPropertyDescription("The factual statement in natural language (e.g., 'Jim is an expert in GOAP')")
    val text: String,
    @param:JsonPropertyDescription("Entities mentioned in this statement. Don't include if you're not sure of any.")
    val mentions: List<SuggestedMention> = emptyList(),
    @param:JsonPropertyDescription("Certainty of this fact (0.0-1.0)")
    val confidence: ZeroToOne,
    @param:JsonPropertyDescription("How quickly this becomes stale (0.0=permanent, 1.0=very temporary)")
    val decay: ZeroToOne = 0.0,
    @param:JsonPropertyDescription("How much this fact matters to remember (0.0=trivial, 1.0=critical). Independent of confidence.")
    val importance: ZeroToOne = 0.5,
    @param:JsonPropertyDescription("Explanation for why this was extracted")
    val reasoning: String = "",
) {
    /**
     * Convert to a Proposition with the given chunk grounding.
     * Entity resolution happens separately.
     */
    fun toProposition(chunkIds: List<String>, contextId: ContextId): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = mentions.map { it.toEntityMention() },
            confidence = confidence.coerceIn(0.0, 1.0),
            decay = decay.coerceIn(0.0, 1.0),
            importance = importance.coerceIn(0.0, 1.0),
            reasoning = reasoning.ifBlank { null },
            grounding = chunkIds,
        )
}

/**
 * Container for propositions suggested from a single chunk.
 */
data class SuggestedPropositions(
    val chunkId: String,
    val propositions: List<SuggestedProposition>,
)
