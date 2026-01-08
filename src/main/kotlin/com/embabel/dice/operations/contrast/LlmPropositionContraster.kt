package com.embabel.dice.operations.contrast

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.operations.PropositionGroup
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory
import java.util.*

/**
 * LLM-based implementation of PropositionContraster.
 *
 * Uses structured output to identify and articulate differences
 * between two groups of propositions.
 *
 * Example usage:
 * ```kotlin
 * val contraster = LlmPropositionContraster
 *     .withLlm(llmOptions)
 *     .withAi(ai)
 *
 * val aliceGroup = PropositionGroup("Alice", aliceProps)
 * val bobGroup = PropositionGroup("Bob", bobProps)
 * val differences = contraster.contrast(aliceGroup, bobGroup)
 * ```
 */
data class LlmPropositionContraster(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
) : PropositionContraster {

    companion object {

        @JvmStatic
        fun withLlm(llm: LlmOptions): Builder = Builder(llm)

        class Builder(private val llmOptions: LlmOptions) {

            fun withAi(ai: Ai): LlmPropositionContraster =
                LlmPropositionContraster(
                    llmOptions = llmOptions,
                    ai = ai,
                )
        }
    }

    private val logger = LoggerFactory.getLogger(LlmPropositionContraster::class.java)

    override fun contrast(
        groupA: PropositionGroup,
        groupB: PropositionGroup,
        targetCount: Int,
    ): List<Proposition> {
        if (groupA.isEmpty() || groupB.isEmpty()) {
            logger.debug("Cannot contrast empty groups")
            return emptyList()
        }

        // Build template data for group A
        val groupAData = groupA.propositions.mapIndexed { index, p ->
            mapOf(
                "index" to index,
                "text" to p.text,
                "confidence" to p.confidence,
            )
        }

        // Build template data for group B
        val groupBData = groupB.propositions.mapIndexed { index, p ->
            mapOf(
                "index" to index,
                "text" to p.text,
                "confidence" to p.confidence,
            )
        }

        val response = ai
            .withLlm(llmOptions)
            .withId("contrast-propositions")
            .creating(ContrastResponse::class.java)
            .fromTemplate(
                "dice/contrast_propositions",
                mapOf(
                    "labelA" to groupA.label,
                    "labelB" to groupB.label,
                    "groupA" to groupAData,
                    "groupB" to groupBData,
                    "targetCount" to targetCount,
                )
            )

        logger.info(
            "Generated {} contrast(s) between '{}' ({} props) and '{}' ({} props)",
            response.contrasts.size,
            groupA.label, groupA.size,
            groupB.label, groupB.size
        )

        // Calculate derived values
        val allPropositions = groupA.propositions + groupB.propositions
        val maxLevel = allPropositions.maxOfOrNull { it.level } ?: 0
        val newLevel = maxLevel + 1

        // Use the most common contextId from all sources
        val contextId = allPropositions
            .groupingBy { it.contextId }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: allPropositions.first().contextId

        return response.contrasts.mapNotNull { contrast ->
            // Map source indices to source IDs from both groups
            val sourceIdsA = contrast.sourceIndicesA
                .filter { it in groupA.propositions.indices }
                .map { groupA.propositions[it].id }
            val sourceIdsB = contrast.sourceIndicesB
                .filter { it in groupB.propositions.indices }
                .map { groupB.propositions[it].id }
            val allSourceIds = sourceIdsA + sourceIdsB

            if (allSourceIds.isEmpty()) {
                logger.warn("Contrast '{}' has no valid source indices", contrast.text)
                return@mapNotNull null
            }

            // Calculate decay as average of sources used
            val sourcesA = contrast.sourceIndicesA
                .filter { it in groupA.propositions.indices }
                .map { groupA.propositions[it] }
            val sourcesB = contrast.sourceIndicesB
                .filter { it in groupB.propositions.indices }
                .map { groupB.propositions[it] }
            val allSources = sourcesA + sourcesB
            val avgDecay = if (allSources.isNotEmpty()) {
                allSources.map { it.decay }.average()
            } else {
                0.0
            }

            Proposition(
                id = UUID.randomUUID().toString(),
                contextId = contextId,
                text = contrast.text,
                mentions = emptyList(),
                confidence = contrast.confidence.coerceIn(0.0, 1.0),
                decay = avgDecay.coerceIn(0.0, 1.0),
                reasoning = contrast.reasoning,
                grounding = emptyList(),
                status = PropositionStatus.ACTIVE,
                level = newLevel,
                sourceIds = allSourceIds,
            )
        }
    }
}

/**
 * Response structure for contrast operation.
 */
data class ContrastResponse(
    @param:JsonPropertyDescription("Generated contrasts describing differences between groups")
    val contrasts: List<ContrastItem> = emptyList(),
)

data class ContrastItem(
    @param:JsonPropertyDescription("The contrast proposition describing a difference")
    val text: String,

    @param:JsonPropertyDescription("Confidence in this contrast (0.0-1.0)")
    val confidence: ZeroToOne,

    @param:JsonPropertyDescription("Reasoning for this contrast")
    val reasoning: String,

    @param:JsonPropertyDescription("Indices of propositions from group A that support this contrast")
    val sourceIndicesA: List<Int>,

    @param:JsonPropertyDescription("Indices of propositions from group B that support this contrast")
    val sourceIndicesB: List<Int>,
)
