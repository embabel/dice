package com.embabel.dice.proposition.content

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.proposition.MentionRole
import com.fasterxml.jackson.annotation.JsonPropertyDescription

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
private data class ContentPropositionsResponse(
    @param:JsonPropertyDescription("List of propositions extracted from the content")
    val propositions: List<ContentPropositionItem> = emptyList(),
)

private data class ContentPropositionItem(
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

private data class ContentMentionItem(
    @param:JsonPropertyDescription("The entity name/span as it appears in the text. Must be legal JSON string content")
    val span: String,
    @param:JsonPropertyDescription("The entity type (e.g., Person, Project, Technology)")
    val type: String,
    @param:JsonPropertyDescription("Role: SUBJECT or OBJECT")
    val role: String = "SUBJECT",
)
