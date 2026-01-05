package com.embabel.dice.proposition.content

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition

/**
 * Suggested proposition before entity resolution.
 * Contains raw text and mentions with suggested types.
 */
data class SuggestedContentProposition(
    val text: String,
    val confidence: ZeroToOne,
    val decay: ZeroToOne,
    val reasoning: String,
    val mentions: List<SuggestedContentMention> = emptyList(),
)

/**
 * Suggested entity mention within a proposition.
 */
data class SuggestedContentMention(
    val span: String,
    val type: String,
    val role: MentionRole = MentionRole.SUBJECT,
)

/**
 * Extracts propositions from [ProposableContent].
 *
 * This is a generalized proposer that works on any content type that
 * implements [ProposableContent], including sensor observations and document chunks.
 *
 * The proposer analyzes the content's text representation and generates
 * natural language propositions about entities, relationships, facts, and behaviors.
 */
interface PropositionExtractor {

    /**
     * Generate propositions from content.
     *
     * @param content The content to analyze
     * @return List of suggested propositions with confidence and decay scores
     */
    fun propose(content: ProposableContent): List<SuggestedContentProposition>

    /**
     * Convert suggested propositions to final propositions.
     * Creates entity mentions and assigns IDs.
     *
     * @param suggestions The suggested propositions
     * @param sourceId ID to use for grounding
     * @return Final propositions ready for storage
     */
    fun toPropositions(
        suggestions: List<SuggestedContentProposition>,
        sourceId: String,
        contextId: ContextId,
    ): List<Proposition> = suggestions.map { suggestion ->
        Proposition(
            contextId = contextId,
            text = suggestion.text,
            mentions = suggestion.mentions.map { mention ->
                EntityMention(
                    span = mention.span,
                    type = mention.type,
                    role = mention.role,
                    resolvedId = null, // Will be resolved later if needed
                )
            },
            confidence = suggestion.confidence,
            decay = suggestion.decay,
            reasoning = suggestion.reasoning,
            grounding = listOf(sourceId),
        )
    }
}
