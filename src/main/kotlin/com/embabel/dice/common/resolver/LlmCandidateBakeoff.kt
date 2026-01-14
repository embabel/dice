package com.embabel.dice.common.resolver

import com.embabel.agent.api.common.Ai
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.NamedEntityData.Companion.ENTITY_LABEL
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.common.SuggestedEntity
import org.slf4j.LoggerFactory

/**
 * Prompt mode for LLM bakeoff - controls verbosity vs token usage.
 */
enum class PromptMode {
    /**
     * Minimal prompts with compressed candidate info.
     * ~60-80% fewer tokens, suitable for most cases.
     */
    COMPACT,

    /**
     * Full prompts with detailed descriptions and instructions.
     * More tokens but potentially more accurate for complex cases.
     */
    FULL,
}

/**
 * Uses an LLM to select the best match from multiple candidates.
 *
 * This is more efficient than evaluating candidates one-by-one, and allows
 * the LLM to compare and contrast options.
 *
 * Supports two prompt modes:
 * - **COMPACT**: Minimal prompts (~100-200 tokens) for fast, cheap resolution
 * - **FULL**: Detailed prompts (~400-600 tokens) for complex disambiguation
 *
 * Returns the best matching candidate, or null if none match.
 *
 * @param ai The Embabel AI instance for LLM calls
 * @param llmOptions LLM configuration (model, temperature, etc.)
 * @param promptMode Controls prompt verbosity (default: COMPACT)
 */
class LlmCandidateBakeoff(
    private val ai: Ai,
    private val llmOptions: LlmOptions,
    private val promptMode: PromptMode = PromptMode.COMPACT,
) : CandidateBakeoff {

    private val logger = LoggerFactory.getLogger(LlmCandidateBakeoff::class.java)

    override fun selectBestMatch(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        sourceText: String?,
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
            val response = ai.withLlm(llmOptions).generateText(promptText)

            logger.info(
                "LLM bakeoff for '{}' ({} candidates): {}",
                suggested.name, candidates.size, response.take(100)
            )

            parseSelection(response, candidates)
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
            val response = ai.withLlm(llmOptions).generateText(promptText)
            val answer = response.trim().lowercase()

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
        return when (promptMode) {
            PromptMode.COMPACT -> buildCompactBakeoffPrompt(suggested, candidates, sourceText)
            PromptMode.FULL -> buildFullBakeoffPrompt(suggested, candidates, sourceText)
        }
    }

    /**
     * Compact prompt: ~100-200 tokens
     * Focuses on essential info only.
     */
    private fun buildCompactBakeoffPrompt(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        sourceText: String? = null,
    ): String {
        val suggestedType = suggested.labels.firstOrNull() ?: "Entity"

        // One-line per candidate: "1. Name (Type) [score]"
        val candidateList = candidates.mapIndexed { index, result ->
            val c = result.match
            val label = c.labels().firstOrNull {
                it != ENTITY_LABEL && it != "Entity" && it != "Reference"
            } ?: "Entity"
            "${index + 1}. ${c.name} ($label) [${String.format("%.2f", result.score)}]"
        }.joinToString("\n")

        val context = if (!sourceText.isNullOrBlank()) {
            "\nContext: ${sourceText.take(200)}${if (sourceText.length > 200) "..." else ""}"
        } else ""

        return """Match "${suggested.name}" ($suggestedType) to a candidate or NONE.$context

$candidateList

Reply: number or NONE + brief reason"""
    }

    /**
     * Full prompt: ~400-600 tokens
     * Detailed instructions and candidate info.
     */
    private fun buildFullBakeoffPrompt(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        sourceText: String? = null,
    ): String {
        val suggestedType = suggested.labels.firstOrNull() ?: "Entity"

        val candidateDescriptions = candidates.mapIndexed { index, result ->
            val c = result.match
            val labels = c.labels().filter {
                it != ENTITY_LABEL && it != "Entity" && it != "Reference"
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
        return when (promptMode) {
            PromptMode.COMPACT -> buildCompactVerificationPrompt(suggested, candidate, sourceText)
            PromptMode.FULL -> buildFullVerificationPrompt(suggested, candidate, sourceText)
        }
    }

    /**
     * Compact verification: ~50 tokens
     */
    private fun buildCompactVerificationPrompt(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        sourceText: String? = null
    ): String {
        val suggestedType = suggested.labels.firstOrNull() ?: "Entity"
        val candidateType = candidate.labels().firstOrNull {
            it != ENTITY_LABEL && it != "Entity" && it != "Reference"
        } ?: "Entity"

        val context = if (!sourceText.isNullOrBlank()) {
            " Context: ${sourceText.take(100)}..."
        } else ""

        return """Is "${suggested.name}" ($suggestedType) = "${candidate.name}" ($candidateType)?$context
Answer: Yes/No + reason"""
    }

    /**
     * Full verification: ~200 tokens
     */
    private fun buildFullVerificationPrompt(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        sourceText: String? = null
    ): String {
        val suggestedType = suggested.labels.firstOrNull() ?: "Entity"
        val candidateLabels = candidate.labels().filter {
            it != ENTITY_LABEL && it != "Entity" && it != "Reference"
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
