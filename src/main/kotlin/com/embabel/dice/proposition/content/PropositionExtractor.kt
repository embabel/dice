package com.embabel.dice.proposition.content

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.fasterxml.jackson.annotation.JsonPropertyDescription

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
    ): List<Proposition> = suggestions.map { suggestion ->
        Proposition(
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

/**
 * LLM-based implementation of [PropositionExtractor].
 * Uses structured output to extract propositions from content.
 *
 * @param ai The AI service for LLM calls
 * @param llmOptions LLM configuration options
 * @param templateName The prompt template to use (default: "content_propose")
 */
class LlmPropositionExtractor(
    private val ai: Ai,
    private val llmOptions: LlmOptions = LlmOptions(),
    private val templateName: String = "content_propose",
) : PropositionExtractor {

    override fun propose(content: ProposableContent): List<SuggestedContentProposition> {
        val context = content.toProposalContext()
        if (context.isBlank()) return emptyList()

        val response = ai
            .withLlm(llmOptions)
            .withId("content-propose")
            .creating(ContentPropositionsResponse::class.java)
            .fromTemplate(
                templateName,
                mapOf(
                    "context" to context,
                    "timestamp" to content.timestamp.toString(),
                    "sourceId" to content.sourceId,
                )
            )

        return response.propositions.map { prop ->
            SuggestedContentProposition(
                text = prop.text,
                confidence = prop.confidence.coerceIn(0.0, 1.0),
                decay = prop.decay.coerceIn(0.0, 1.0),
                reasoning = prop.reasoning,
                mentions = prop.mentions.map { mention ->
                    SuggestedContentMention(
                        span = mention.span,
                        type = mention.type,
                        role = when (mention.role.uppercase()) {
                            "SUBJECT" -> MentionRole.SUBJECT
                            "OBJECT" -> MentionRole.OBJECT
                            else -> MentionRole.SUBJECT
                        },
                    )
                },
            )
        }
    }
}

/**
 * Response structure for LLM extraction.
 */
data class ContentPropositionsResponse(
    @param:JsonPropertyDescription("List of propositions extracted from the content")
    val propositions: List<ContentPropositionItem> = emptyList(),
)

data class ContentPropositionItem(
    @param:JsonPropertyDescription("The proposition text as a clear statement")
    val text: String,
    @param:JsonPropertyDescription("Confidence score 0.0-1.0")
    val confidence: Double,
    @param:JsonPropertyDescription("Decay rate 0.0-1.0 (how quickly this becomes stale)")
    val decay: Double,
    @param:JsonPropertyDescription("Brief reasoning for this proposition")
    val reasoning: String,
    @param:JsonPropertyDescription("Entity mentions in this proposition")
    val mentions: List<ContentMentionItem> = emptyList(),
)

data class ContentMentionItem(
    @param:JsonPropertyDescription("The entity name/span as it appears in the text. DO NOT ENCLOSE IN QUOTES--must be legal JSON string content")
    val span: String,
    @param:JsonPropertyDescription("The entity type (e.g., Person, Project, Technology)")
    val type: String,
    @param:JsonPropertyDescription("Role: SUBJECT or OBJECT")
    val role: String = "SUBJECT",
)
