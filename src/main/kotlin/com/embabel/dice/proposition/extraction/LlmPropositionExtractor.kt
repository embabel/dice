package com.embabel.dice.proposition.extraction

import com.embabel.agent.api.common.Ai
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.proposition.*
import com.embabel.dice.t.SourceAnalysisConfig
import org.slf4j.LoggerFactory

/**
 * LLM-based proposition extractor.
 * Uses the propose_facts.jinja template to extract propositions from chunks.
 *
 * @param ai AI service for LLM calls
 * @param llmOptions LLM configuration
 */
class LlmPropositionExtractor(
    private val ai: Ai,
    private val llmOptions: LlmOptions = LlmOptions(),
) : PropositionExtractor {

    private val logger = LoggerFactory.getLogger(LlmPropositionExtractor::class.java)

    override fun extract(
        chunk: Chunk,
        context: SourceAnalysisConfig,
    ): SuggestedPropositions {
        logger.debug("Extracting propositions from chunk {}", chunk.id)

        val result = ai
            .withLlm(llmOptions)
            .withId("propose-facts")
            .creating(PropositionsResult::class.java)
            .fromTemplate(
                "propose_facts",
                mapOf(
                    "context" to context,
                    "chunk" to chunk,
                )
            )

        logger.debug("Extracted {} propositions from chunk {}", result.propositions.size, chunk.id)

        return SuggestedPropositions(
            chunkId = chunk.id,
            propositions = result.propositions,
        )
    }

    override fun toSuggestedEntities(
        suggestedPropositions: SuggestedPropositions
    ): SuggestedEntities {
        // Collect all unique mentions across all propositions
        val uniqueMentions = mutableMapOf<MentionKey, SuggestedMention>()

        for (proposition in suggestedPropositions.propositions) {
            for (mention in proposition.mentions) {
                val key = MentionKey.from(mention)
                // Keep the first occurrence (or one with suggestedId if available)
                val existing = uniqueMentions[key]
                if (existing == null || (existing.suggestedId == null && mention.suggestedId != null)) {
                    uniqueMentions[key] = mention
                }
            }
        }

        val suggestedEntities = uniqueMentions.values.map { it.toSuggestedEntity() }

        logger.debug(
            "Converted {} propositions to {} unique suggested entities",
            suggestedPropositions.propositions.size,
            suggestedEntities.size
        )

        return SuggestedEntities(
            chunkIds = setOf(suggestedPropositions.chunkId),
            suggestedEntities = suggestedEntities,
        )
    }

    override fun resolvePropositions(
        suggestedPropositions: SuggestedPropositions,
        resolutions: Resolutions<SuggestedEntityResolution>,
    ): List<Proposition> {
        // Build a map from mention key to resolved entity ID
        val resolutionMap = buildResolutionMap(resolutions)

        logger.debug("Resolution map has {} entries", resolutionMap.size)

        return suggestedPropositions.propositions.map { suggested ->
            val resolvedMentions = suggested.mentions.map { mention ->
                val key = MentionKey.from(mention)
                val resolvedId = resolutionMap[key]
                mention.toEntityMention(resolvedId)
            }

            suggested.toProposition(listOf(suggestedPropositions.chunkId))
                .copy(mentions = resolvedMentions)
        }
    }

    private fun buildResolutionMap(
        resolutions: Resolutions<SuggestedEntityResolution>
    ): Map<MentionKey, String> {
        val map = mutableMapOf<MentionKey, String>()

        for (resolution in resolutions.resolutions) {
            val recommended = resolution.recommended ?: continue
            val suggested = resolution.suggested

            // The suggested entity's name and labels give us the mention key
            val key = MentionKey(
                span = suggested.name.lowercase().trim(),
                type = suggested.labels.firstOrNull() ?: continue,
            )
            map[key] = recommended.id
        }

        return map
    }
}

/**
 * Internal class for parsing LLM output.
 */
internal data class PropositionsResult(
    val propositions: List<SuggestedProposition>,
)
