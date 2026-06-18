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
package com.embabel.dice.common.resolver

import com.embabel.agent.api.common.Ai
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.NamedEntityData.Companion.ENTITY_LABEL
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.common.SuggestedEntity
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory

/**
 * Strategy for building prompts used in LLM candidate bakeoff.
 *
 * Two built-in strategies are provided in [BakeoffPromptStrategies]:
 * - [BakeoffPromptStrategies.COMPACT]: Minimal prompts (~100-200 tokens) optimized for speed and cost.
 *   **Note**: Does not include entity descriptions, which may cause disambiguation issues
 *   when candidates have similar names but different meanings.
 * - [BakeoffPromptStrategies.FULL]: Detailed prompts (~400-600 tokens) with descriptions and
 *   comprehensive matching rules for complex disambiguation.
 *
 * You can implement your own strategy for domain-specific prompt customization:
 * ```kotlin
 * val myStrategy = object : BakeoffPromptStrategy {
 *     override fun buildBakeoffPrompt(...) = "..."
 *     override fun buildVerificationPrompt(...) = "..."
 * }
 * val bakeoff = LlmCandidateBakeoff.withLlm(llmOptions).withAi(ai).withPromptStrategy(myStrategy)
 * ```
 */
interface BakeoffPromptStrategy {

    /**
     * Build a prompt for selecting the best match from multiple candidates.
     *
     * @param suggested The entity to match
     * @param candidates The candidate entities from the database
     * @param sourceText Optional conversation context
     * @return The prompt text for the LLM
     */
    fun buildBakeoffPrompt(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        sourceText: String?,
    ): String

    /**
     * Build a prompt for verifying a single candidate match.
     *
     * @param suggested The entity to match
     * @param candidate The single candidate to verify
     * @param sourceText Optional conversation context
     * @return The prompt text for the LLM
     */
    fun buildVerificationPrompt(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        sourceText: String?,
    ): String
}

/**
 * Built-in prompt strategies for [LlmCandidateBakeoff].
 *
 * Usage:
 * - Kotlin: `BakeoffPromptStrategies.COMPACT` or `BakeoffPromptStrategies.FULL`
 * - Java: `BakeoffPromptStrategies.COMPACT` or `BakeoffPromptStrategies.FULL`
 */
object BakeoffPromptStrategies {

    /**
     * Compact prompts optimized for speed and token usage (~100-200 tokens).
     *
     * **Warning**: Does not include entity descriptions. This works well when entity
     * names are distinctive, but may cause incorrect matches when candidates have
     * similar names with different meanings (e.g., "Symphony No. 5" by different composers).
     * Use [FULL] if disambiguation accuracy is critical.
     */
    @JvmField
    val COMPACT: BakeoffPromptStrategy = CompactBakeoffPromptStrategy

    /**
     * Full prompts with detailed descriptions and matching rules (~400-600 tokens).
     *
     * Includes entity descriptions and comprehensive instructions for complex
     * disambiguation. Use this when accuracy is more important than token cost.
     */
    @JvmField
    val FULL: BakeoffPromptStrategy = FullBakeoffPromptStrategy
}

/**
 * Structured response from the bakeoff LLM.
 */
data class BakeoffSelection(
    @field:JsonPropertyDescription("The candidate number (1-based) of the best match, or null if none match")
    val selectedCandidate: Int?,
    @field:JsonPropertyDescription("Brief explanation of why this candidate was selected or why no match was found")
    val reason: String,
)

/**
 * Structured response for single candidate verification.
 */
data class VerificationResult(
    @field:JsonPropertyDescription("True if the candidate matches the suggested entity")
    val isMatch: Boolean,
    @field:JsonPropertyDescription("Brief explanation of why it matches or doesn't match")
    val reason: String,
)

/**
 * Uses an LLM to select the best match from multiple candidates.
 *
 * This is more efficient than evaluating candidates one-by-one, and allows
 * the LLM to compare and contrast options. Uses structured output for reliable parsing.
 *
 * Two built-in prompt strategies are available in [BakeoffPromptStrategies]:
 * - [BakeoffPromptStrategies.COMPACT]: Minimal prompts (~100-200 tokens) for fast, cheap resolution.
 *   Does not include descriptions - may cause issues with similarly-named entities.
 * - [BakeoffPromptStrategies.FULL]: Detailed prompts (~400-600 tokens) for complex disambiguation.
 *
 * Custom strategies can be implemented for domain-specific prompt requirements.
 *
 * Returns the best matching candidate, or null if none match.
 *
 * Example usage (Java-friendly builder):
 * ```java
 * var bakeoff = LlmCandidateBakeoff
 *     .withLlm(llmOptions)
 *     .withAi(ai)
 *     .withPromptStrategy(BakeoffPromptStrategies.FULL);
 * ```
 *
 * @param llmOptions LLM configuration (model, temperature, etc.)
 * @param ai The Embabel AI instance for LLM calls
 * @param promptStrategy Strategy for building prompts (default: COMPACT)
 */
data class LlmCandidateBakeoff(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
    private val promptStrategy: BakeoffPromptStrategy = BakeoffPromptStrategies.COMPACT,
) : CandidateBakeoff {

    companion object {

        private val logger = LoggerFactory.getLogger(LlmCandidateBakeoff::class.java)

        /**
         * Start building an LlmCandidateBakeoff with the specified LLM options.
         *
         * @param llm LLM configuration (model, temperature, etc.)
         * @return Builder for fluent configuration
         */
        @JvmStatic
        fun withLlm(llm: LlmOptions): Builder = Builder(llm)

        /**
         * Step builder for Java-friendly construction.
         */
        class Builder(private val llmOptions: LlmOptions) {

            /**
             * Set the AI instance and create the bakeoff with default COMPACT prompts.
             *
             * @param ai The Embabel AI instance for LLM calls
             * @return LlmCandidateBakeoff ready for use
             */
            fun withAi(ai: Ai): LlmCandidateBakeoff =
                LlmCandidateBakeoff(
                    llmOptions = llmOptions,
                    ai = ai,
                )
        }
    }

    /**
     * Set the prompt strategy.
     *
     * Use [BakeoffPromptStrategies.COMPACT] for fast/cheap resolution (no descriptions).
     * Use [BakeoffPromptStrategies.FULL] for accurate disambiguation (includes descriptions).
     *
     * @param strategy The prompt building strategy
     * @return New instance with the specified strategy
     */
    fun withPromptStrategy(strategy: BakeoffPromptStrategy): LlmCandidateBakeoff =
        copy(promptStrategy = strategy)

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

        val promptText = promptStrategy.buildBakeoffPrompt(suggested, candidates, sourceText)

        return try {
            val result = ai.withLlm(llmOptions)
                .withId("candidate-bakeoff-${suggested.id}")
                .createObject(promptText, BakeoffSelection::class.java)

            logger.info(
                "LLM bakeoff for '{}' ({} candidates): selected={}, reason={}",
                suggested.name, candidates.size, result.selectedCandidate, result.reason
            )

            if (result.selectedCandidate != null) {
                val index = result.selectedCandidate - 1  // Convert to 0-based
                if (index in candidates.indices) {
                    logger.debug(
                        "LLM selected candidate {}: {}",
                        result.selectedCandidate,
                        candidates[index].match.name
                    )
                    candidates[index].match
                } else {
                    logger.warn("LLM selected invalid candidate number: {}", result.selectedCandidate)
                    null
                }
            } else {
                logger.debug("LLM selected NONE of the candidates: {}", result.reason)
                null
            }
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
        val promptText = promptStrategy.buildVerificationPrompt(suggested, candidate, sourceText)

        return try {
            val result = ai.withLlm(llmOptions)
                .withId("candidate-verify-${suggested.id}")
                .createObject(promptText, VerificationResult::class.java)

            logger.debug(
                "LLM verification for '{}' vs '{}': match={}, reason={}",
                suggested.name, candidate.name, result.isMatch, result.reason
            )
            result.isMatch
        } catch (e: Exception) {
            logger.warn("LLM verification failed: {}", e.message)
            false
        }
    }
}

/**
 * Compact prompt strategy optimized for speed and token usage (~100-200 tokens).
 *
 * **Important**: Does not include entity descriptions in the prompts. This works well
 * when entity names are distinctive, but may cause incorrect matches when candidates
 * have similar names with different meanings (e.g., "Symphony No. 5" by different composers).
 *
 * If you experience disambiguation issues, switch to [FullBakeoffPromptStrategy] or
 * create a custom strategy that includes relevant description snippets.
 */
internal object CompactBakeoffPromptStrategy : BakeoffPromptStrategy {

    override fun buildBakeoffPrompt(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        sourceText: String?,
    ): String {
        val suggestedType = suggested.labels.firstOrNull() ?: "Entity"

        // One-line per candidate: "1. Name (Type) [score]" - no description
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

        return """Match "${suggested.name}" ($suggestedType) to a candidate or select none.$context

$candidateList

Select the candidate number (1-${candidates.size}) that matches, or null if none match."""
    }

    override fun buildVerificationPrompt(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        sourceText: String?,
    ): String {
        val suggestedType = suggested.labels.firstOrNull() ?: "Entity"
        val candidateType = candidate.labels().firstOrNull {
            it != ENTITY_LABEL && it != "Entity" && it != "Reference"
        } ?: "Entity"

        val context = if (!sourceText.isNullOrBlank()) {
            " Context: ${sourceText.take(100)}..."
        } else ""

        return """Is "${suggested.name}" ($suggestedType) the same entity as "${candidate.name}" ($candidateType)?$context

Determine if these refer to the same entity."""
    }
}

/**
 * Full prompt strategy with detailed descriptions and matching rules (~400-600 tokens).
 *
 * Includes entity descriptions and comprehensive instructions for complex disambiguation.
 * Use this when accuracy is more important than token cost, or when dealing with
 * entities that have similar or generic names.
 */
internal object FullBakeoffPromptStrategy : BakeoffPromptStrategy {

    override fun buildBakeoffPrompt(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        sourceText: String?,
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
6. If NONE of the candidates are a true match, select null

Select the candidate number (1-${candidates.size}) that matches, or null if none match."""
    }

    override fun buildVerificationPrompt(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        sourceText: String?,
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

Determine if these refer to the same entity. Types must match (Composer ≠ Work).
For works, verify the composer matches the conversation context."""
    }
}
