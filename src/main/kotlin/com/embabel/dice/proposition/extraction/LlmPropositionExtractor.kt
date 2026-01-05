package com.embabel.dice.proposition.extraction

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.nested.ObjectCreationExample
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.*
import com.embabel.dice.proposition.*
import org.slf4j.LoggerFactory


interface ExtractionConfig {
    val lockToSchema: Boolean
}

/**
 * Model passed to the Jinja template for proposition extraction.
 */
data class TemplateModel(
    val context: SourceAnalysisContext,
    val chunk: Chunk,
    override val lockToSchema: Boolean,
    val existingPropositions: List<Proposition>,
) : ExtractionConfig

/**
 * LLM-based proposition extractor.
 * Uses a Jinja template to extract propositions from chunks.
 *
 * ## Custom Templates
 *
 * Custom templates can include the default template and add domain-specific focus:
 *
 * ```jinja
 * {% include "dice/extract_propositions.jinja" %}
 *
 * FOCUS:
 *
 * Extract facts about the user and the user's musical preferences:
 *
 * - The user's level of knowledge about music theory
 * - Favorite genres, artists, and songs
 * - Instruments they play or want to learn
 * - Listening habits and contexts
 * ...
 * ```
 *
 * @param llmOptions LLM configuration
 * @param ai AI service for LLM calls
 * @param template Template name for proposition extraction. Unless
 * the TemplateResolver in use has been customized, this will be the path to a Jinja template
 * under /src/main/resources/prompts.
 * The templates should expect an object of type [TemplateModel] as input with name "model".
 * The default is "dice/extract_propositions". Users can override this
 * or use it as an example for a custom template.
 * @param examples Optional list of examples for few-shot prompting.
 * @param lockToSchema If true, only extract propositions with entity types from the schema.
 * If false, prefer schema types but allow important propositions outside the schema.
 * @param existingPropositionsToShow Number of existing propositions to show in logs.
 */
data class LlmPropositionExtractor(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
    private val template: String = "dice/extract_propositions",
    private val examples: List<ObjectCreationExample<PropositionsResult>> = emptyList(),
    override val lockToSchema: Boolean = true,
    val existingPropositionsToShow: Int = 10,
) : PropositionExtractor, ExtractionConfig {

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

    fun withExistingPropositionsToShow(
        count: Int
    ): LlmPropositionExtractor {
        return this.copy(
            existingPropositionsToShow = count,
        )
    }

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

    fun withLockToSchema(lock: Boolean): LlmPropositionExtractor {
        return this.copy(
            lockToSchema = lock,
        )
    }

    private fun extractExistingPropositions(): List<Proposition> {
        // Placeholder for extracting existing propositions if needed
        // In a real implementation, this would fetch from a database or context
        return emptyList()
    }

    override fun extract(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): SuggestedPropositions {
        logger.debug("Extracting propositions from chunk {}", chunk.id)

        val existingPropositions = extractExistingPropositions()

        val result = ai
            .withLlm(llmOptions)
            .withId("extract-propositions")
            .creating(PropositionsResult::class.java)
            .withExamples(examples)
            .fromTemplate(
                templateName = template,
                model = mapOf(
                    "model" to TemplateModel(
                        context = context,
                        chunk = chunk,
                        lockToSchema = lockToSchema,
                        existingPropositions = existingPropositions,
                    ),
                ) + context.templateModel,
            )

        logger.debug("Extracted {} propositions from chunk {}", result.propositions.size, chunk.id)

        return SuggestedPropositions(
            chunkId = chunk.id,
            propositions = result.propositions,
        )
    }

    override fun toSuggestedEntities(
        suggestedPropositions: SuggestedPropositions,
        context: SourceAnalysisContext,
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
        context: SourceAnalysisContext,
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

            suggested.toProposition(chunkIds = listOf(suggestedPropositions.chunkId), contextId = context.contextId)
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
