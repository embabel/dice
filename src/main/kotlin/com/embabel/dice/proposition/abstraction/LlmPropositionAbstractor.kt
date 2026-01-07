package com.embabel.dice.proposition.abstraction

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory
import java.util.*

/**
 * LLM-based implementation of PropositionAbstractor.
 *
 * Uses structured output to generate higher-level abstractions from
 * a group of related propositions.
 *
 * Example usage:
 * ```kotlin
 * val abstractor = LlmPropositionAbstractor
 *     .withLlm(llmOptions)
 *     .withAi(ai)
 *
 * // Get propositions about Bob
 * val bobProps = repository.findByEntity("bob-123")
 * val abstractions = abstractor.abstract(bobProps, targetCount = 2)
 * ```
 */
data class LlmPropositionAbstractor(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
) : PropositionAbstractor {

    companion object {

        @JvmStatic
        fun withLlm(llm: LlmOptions): Builder = Builder(llm)

        class Builder(private val llmOptions: LlmOptions) {

            fun withAi(ai: Ai): LlmPropositionAbstractor =
                LlmPropositionAbstractor(
                    llmOptions = llmOptions,
                    ai = ai,
                )
        }
    }

    private val logger = LoggerFactory.getLogger(LlmPropositionAbstractor::class.java)

    override fun abstract(
        propositions: List<Proposition>,
        targetCount: Int,
    ): List<Proposition> {
        if (propositions.isEmpty()) {
            logger.debug("No propositions to abstract")
            return emptyList()
        }

        if (propositions.size < 2) {
            logger.debug("Only {} proposition(s), need at least 2 to abstract", propositions.size)
            return emptyList()
        }

        // Build template data
        val propositionData = propositions.mapIndexed { index, p ->
            mapOf(
                "index" to index,
                "text" to p.text,
                "confidence" to p.confidence,
                "decay" to p.decay,
            )
        }

        val response = ai
            .withLlm(llmOptions)
            .withId("abstract-propositions")
            .creating(AbstractionResponse::class.java)
            .fromTemplate(
                "dice/abstract_propositions",
                mapOf(
                    "propositions" to propositionData,
                    "targetCount" to targetCount,
                )
            )

        logger.info(
            "Generated {} abstraction(s) from {} propositions",
            response.abstractions.size,
            propositions.size
        )

        // Calculate derived values
        val maxLevel = propositions.maxOfOrNull { it.level } ?: 0
        val newLevel = maxLevel + 1

        // Use the most common contextId from sources, or first one
        val contextId = propositions
            .groupingBy { it.contextId }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: propositions.first().contextId

        return response.abstractions.mapNotNull { abstraction ->
            // Map source indices to source IDs
            val sourceIds = abstraction.sourceIndices
                .filter { it in propositions.indices }
                .map { propositions[it].id }

            if (sourceIds.isEmpty()) {
                logger.warn("Abstraction '{}' has no valid source indices", abstraction.text)
                return@mapNotNull null
            }

            // Calculate decay as average of sources
            val sourcesForDecay = abstraction.sourceIndices
                .filter { it in propositions.indices }
                .map { propositions[it] }
            val avgDecay = if (sourcesForDecay.isNotEmpty()) {
                sourcesForDecay.map { it.decay }.average()
            } else {
                0.0
            }

            Proposition(
                id = UUID.randomUUID().toString(),
                contextId = contextId,
                text = abstraction.text,
                mentions = emptyList(), // Abstractions may mention entities but we don't extract them here
                confidence = abstraction.confidence.coerceIn(0.0, 1.0),
                decay = avgDecay.coerceIn(0.0, 1.0),
                reasoning = abstraction.reasoning,
                grounding = emptyList(), // Abstractions aren't grounded in source documents
                status = PropositionStatus.ACTIVE,
                level = newLevel,
                sourceIds = sourceIds,
            )
        }
    }
}

/**
 * Response structure for abstraction.
 */
data class AbstractionResponse(
    @param:JsonPropertyDescription("Generated abstractions")
    val abstractions: List<AbstractionItem> = emptyList(),
)

data class AbstractionItem(
    @param:JsonPropertyDescription("The abstract proposition text")
    val text: String,

    @param:JsonPropertyDescription("Confidence in this abstraction (0.0-1.0)")
    val confidence: ZeroToOne,

    @param:JsonPropertyDescription("Reasoning for this abstraction")
    val reasoning: String,

    @param:JsonPropertyDescription("Indices of source propositions that support this abstraction")
    val sourceIndices: List<Int>,
)
