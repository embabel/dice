package com.embabel.dice.common.resolver.matcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.RetrievableEntity
import com.embabel.common.ai.model.ByNameModelSelectionCriteria
import com.embabel.common.ai.model.ModelProvider
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.MatchResult
import com.embabel.dice.common.resolver.MatchStrategy
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.prompt.Prompt

/**
 * Uses a cheap/fast LLM to verify if a candidate entity matches a suggested entity.
 * This is the final arbiter after other strategies have narrowed down candidates.
 *
 * The LLM is given:
 * - The suggested entity name and type
 * - The candidate entity's full details (name, description, labels)
 * - Context about what we're looking for
 *
 * This provides semantic matching that text search and simple heuristics cannot achieve.
 */
class LlmMatchVerificationStrategy(
    private val modelProvider: ModelProvider,
    private val modelName: String = "gpt-4.1-mini",
) : MatchStrategy {

    private val logger = LoggerFactory.getLogger(LlmMatchVerificationStrategy::class.java)

    override fun evaluate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): MatchResult {
        val promptText = buildVerificationPrompt(suggested, candidate)

        return try {
            val llm = modelProvider.getLlm(ByNameModelSelectionCriteria(modelName))
            val response = llm.model.call(Prompt(promptText))
            val answer = response.result.output.text?.trim()?.lowercase() ?: ""

            logger.debug(
                "LLM verification for '{}' vs '{}': {}",
                suggested.name, candidate.name, answer
            )

            when {
                answer.startsWith("yes") -> MatchResult.Match
                answer.startsWith("no") -> MatchResult.NoMatch
                else -> MatchResult.Inconclusive
            }
        } catch (e: Exception) {
            logger.warn("LLM verification failed for '{}': {}", suggested.name, e.message)
            MatchResult.Inconclusive
        }
    }

    private fun buildVerificationPrompt(suggested: SuggestedEntity, candidate: NamedEntityData): String {
        val suggestedType = suggested.labels.firstOrNull() ?: "Entity"
        val candidateLabels = candidate.labels().filter {
            it != RetrievableEntity.ENTITY_LABEL && it != "Entity" && it != "Reference"
        }.joinToString(", ")

        return """You are verifying if a database entity matches what was mentioned in a conversation.

MENTIONED IN CONVERSATION:
- Name: "${suggested.name}"
- Expected type: $suggestedType
- Context: ${suggested.summary.ifBlank { "No additional context" }}

DATABASE CANDIDATE:
- Name: "${candidate.name}"
- Type(s): $candidateLabels
- Description: ${candidate.description.ifBlank { "No description" }}
- ID: ${candidate.id}

QUESTION: Is the database candidate the same entity as what was mentioned in the conversation?

Consider:
1. Do the names refer to the same thing? (e.g., "Brahms" = "Johannes Brahms", but "Wagner" ≠ "Paraphrase on Wagner's Meistersinger")
2. Are the types compatible? (e.g., if looking for a Composer, a Work is NOT a match even if the composer's name appears in it)
3. Is this an exact match or just a coincidental word overlap?

Answer with ONLY "Yes" or "No" followed by a brief reason.
"""
    }
}
