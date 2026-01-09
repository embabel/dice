package com.embabel.dice.common.resolver.matcher

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.RetrievableEntity
import com.embabel.common.ai.model.ByNameModelSelectionCriteria
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.common.SuggestedEntity
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.prompt.Prompt

/**
 * Uses a cheap/fast LLM to select the best match from multiple candidates.
 * This is more efficient than evaluating candidates one-by-one, and allows
 * the LLM to compare and contrast options.
 *
 * Returns the index of the best matching candidate, or -1 if none match.
 */
class LlmCandidateBakeoff(
    private val modelProvider: ModelProvider,
    private val modelName: String = "gpt-4.1-mini",
) {

    private val logger = LoggerFactory.getLogger(LlmCandidateBakeoff::class.java)

    /**
     * Select the best matching candidate from a list.
     *
     * @param suggested The entity we're trying to match
     * @param candidates The search results to evaluate
     * @param sourceText Optional conversation/source text for additional context
     * @return The best matching candidate, or null if none are suitable
     */
    fun selectBestMatch(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        sourceText: String? = null,
    ): NamedEntityData? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) {
            // For single candidate, do a simple yes/no check
            return if (verifySingleCandidate(suggested, candidates[0].match, sourceText)) {
                candidates[0].match
            } else {
                null
            }
        }

        val promptText = buildBakeoffPrompt(suggested, candidates, sourceText)

        return try {
            val llm = modelProvider.getLlm(ByNameModelSelectionCriteria(modelName))
            val response = llm.model.call(Prompt(promptText))
            val answer = response.result.output.text?.trim() ?: ""

            logger.info(
                "LLM bakeoff for '{}' ({} candidates): {}",
                suggested.name, candidates.size, answer.take(100)
            )

            parseSelection(answer, candidates)
        } catch (e: Exception) {
            logger.warn("LLM bakeoff failed for '{}': {}", suggested.name, e.message)
            null
        }
    }

    private fun verifySingleCandidate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        sourceText: String? = null
    ): Boolean {
        val promptText = buildVerificationPrompt(suggested, candidate, sourceText)

        return try {
            val llm = modelProvider.getLlm(ByNameModelSelectionCriteria(modelName))
            val response = llm.model.call(Prompt(promptText))
            val answer = response.result.output.text?.trim()?.lowercase() ?: ""

            logger.debug("LLM verification for '{}' vs '{}': {}", suggested.name, candidate.name, answer)
            answer.startsWith("yes")
        } catch (e: Exception) {
            logger.warn("LLM verification failed: {}", e.message)
            false
        }
    }

    private fun buildBakeoffPrompt(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        sourceText: String? = null,
    ): String {
        val suggestedType = suggested.labels.firstOrNull() ?: "Entity"

        val candidateDescriptions = candidates.mapIndexed { index, result ->
            val c = result.match
            val labels = c.labels().filter {
                it != RetrievableEntity.ENTITY_LABEL && it != "Entity" && it != "Reference"
            }.joinToString(", ")
            """
            |CANDIDATE ${index + 1}:
            |  Name: "${c.name}"
            |  Type(s): $labels
            |  Description: ${c.description.ifBlank { "None" }}
            |  Search Score: ${"%.2f".format(result.score)}
            """.trimMargin()
        }.joinToString("\n")

        val conversationContext = if (!sourceText.isNullOrBlank()) {
            "\n\nCONVERSATION CONTEXT (use this to understand what's being discussed):\n$sourceText"
        } else ""

        return """You are selecting the best database entity match for something mentioned in a conversation.

LOOKING FOR:
- Name: "${suggested.name}"
- Expected type: $suggestedType
- Entity context: ${suggested.summary.ifBlank { "No additional context" }}$conversationContext

CANDIDATES FROM DATABASE:
$candidateDescriptions

TASK: Which candidate (if any) is the SAME entity as what was mentioned?

Rules:
1. The names must refer to the same thing (e.g., "Brahms" = "Johannes Brahms", "The Ring" = "Der Ring des Nibelungen")
2. Types must be compatible (if looking for a Composer, a Work is NOT a match)
3. For WORKS: Use conversation context - if discussing a composer, the work should be BY that composer (check description)
4. Coincidental word overlap is NOT a match (e.g., "Wagner" ≠ "Piece about Wagner")
5. Common alternate names count as matches (e.g., "Glazunov's violin concerto" = "Violin Concerto in A minor" by Glazunov)
6. If NONE of the candidates are a true match, say "NONE"

Answer with ONLY the candidate number (1, 2, 3, etc.) or "NONE", followed by a brief reason.
Example: "2 - Johannes Brahms is the composer commonly known as Brahms"
Example: "3 - This violin concerto is by Glazunov as mentioned in conversation"
Example: "NONE - None of these works are by the composer discussed in conversation"
"""
    }

    private fun buildVerificationPrompt(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        sourceText: String? = null
    ): String {
        val suggestedType = suggested.labels.firstOrNull() ?: "Entity"
        val candidateLabels = candidate.labels().filter {
            it != RetrievableEntity.ENTITY_LABEL && it != "Entity" && it != "Reference"
        }.joinToString(", ")

        val conversationContext = if (!sourceText.isNullOrBlank()) {
            "\n\nCONVERSATION CONTEXT:\n$sourceText"
        } else ""

        return """Is this database entity the same as what was mentioned in conversation?

MENTIONED: "${suggested.name}" (type: $suggestedType)
Context: ${suggested.summary.ifBlank { "No additional context" }}$conversationContext

DATABASE ENTITY:
- Name: "${candidate.name}"
- Type(s): $candidateLabels
- Description: ${candidate.description.ifBlank { "None" }}

Answer "Yes" or "No" with brief reason. Types must match (Composer ≠ Work).
For works, verify the composer matches the conversation context.
"""
    }

    private fun parseSelection(
        answer: String,
        candidates: List<SimilarityResult<NamedEntityData>>,
    ): NamedEntityData? {
        val trimmed = answer.trim().lowercase()

        if (trimmed.startsWith("none")) {
            logger.debug("LLM selected NONE of the candidates")
            return null
        }

        // Try to extract a number from the start of the answer
        val numberMatch = Regex("^(\\d+)").find(trimmed)
        if (numberMatch != null) {
            val index = numberMatch.groupValues[1].toInt() - 1  // Convert to 0-based
            if (index in candidates.indices) {
                logger.debug("LLM selected candidate {}: {}", index + 1, candidates[index].match.name)
                return candidates[index].match
            }
        }

        logger.warn("Could not parse LLM selection: {}", answer.take(50))
        return null
    }
}
