package com.embabel.dice.proposition.extraction

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.nested.ObjectCreationExample
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.*
import com.embabel.dice.proposition.*
import org.slf4j.LoggerFactory

/**
 * LLM-based proposition extractor.
 * Uses a Jinja template to extract propositions from chunks.
 *
 * @param ai AI service for LLM calls
 * @param llmOptions LLM configuration
 * @param template Template name for proposition extraction. Unless
 * the TemplateResolver in use has been customized, this will be the path to a Jinja template.
 * under /src/main/resources/prompts
 * The templates should expect "context" and "chunk" variables.
 * The default is "dice/extract_propositions". Users can override this
 * or use it as an example for a custom template. It can include other elements
 * with paths under the TemplateRenderer's configured base path.
 * @param examples Optional list of examples for few-shot prompting.
 */
data class LlmPropositionExtractor(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
    private val template: String = "dice/extract_propositions",
    private val examples: List<ObjectCreationExample<PropositionsResult>> = emptyList(),
) : PropositionExtractor {

    companion object {

        @JvmStatic
        fun withLlm(
            llm: LlmOptions,
        ): Builder {
            return Builder(llm)
        }

        class Builder(
            private val llmOptions: LlmOptions,
        ) {

            fun withAi(ai: Ai): LlmPropositionExtractor =
                LlmPropositionExtractor(
                    ai = ai,
                    llmOptions = llmOptions,
                )
        }
    }

    private val logger = LoggerFactory.getLogger(LlmPropositionExtractor::class.java)

    fun withExample(example: ObjectCreationExample<PropositionsResult>): LlmPropositionExtractor {
        return this.copy(
            examples = this.examples + example,
        )
    }

    fun withExamples(
        examples: List<ObjectCreationExample<PropositionsResult>>
    ): LlmPropositionExtractor {
        return this.copy(
            examples = this.examples + examples,
        )
    }

    fun withTemplate(templateName: String): LlmPropositionExtractor {
        return this.copy(
            template = templateName,
        )
    }

    override fun extract(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): SuggestedPropositions {
        logger.debug("Extracting propositions from chunk {}", chunk.id)

        val result = ai
            .withLlm(llmOptions)
            .withId("extract-propositions")
            .creating(PropositionsResult::class.java)
            .withExamples(examples)
            .fromTemplate(
                templateName = template,
                model = mapOf(
                    "context" to context,
                    "chunk" to chunk,
                ) + context.templateModel,
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

        val suggestedEntities = uniqueMentions.values.map {
            SuggestedEntity(
                labels = listOf(it.type),
                name = it.span,
                summary = "Entity mentioned in proposition",
                id = it.suggestedId,
                properties = emptyMap(),
                chunkId = suggestedPropositions.chunkId,
            )
        }

        logger.debug(
            "Converted {} propositions to {} unique suggested entities",
            suggestedPropositions.propositions.size,
            suggestedEntities.size
        )

        return SuggestedEntities(
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
 * Class for parsing LLM output.
 */
data class PropositionsResult(
    val propositions: List<SuggestedProposition>,
)
