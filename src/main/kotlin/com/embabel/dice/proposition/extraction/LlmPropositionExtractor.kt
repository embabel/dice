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
package com.embabel.dice.proposition.extraction

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.nested.ObjectCreationExample
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.*
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.proposition.*
import org.slf4j.LoggerFactory


/**
 * Controls whose perspective propositions are extracted from in conversational text.
 *
 * When the input contains dialogue between a user and an assistant, this determines
 * which speaker's facts are prioritized.
 */
enum class ExtractionPerspective(val description: String) {

    /** Extract facts from all speakers (default, backward compatible) */
    ALL("Extract facts stated by any speaker in the text."),

    /** Extract facts stated by or about the user; ignore assistant self-descriptions */
    USER("Extract facts SOLELY from the user's messages. Do NOT extract facts that originate only from assistant or system messages. Always attribute facts to the user using their name as an entity mention."),

    /** Extract facts stated by or about the assistant — what it decided, expressed, or revealed about itself */
    AGENT("Extract facts about the assistant's own knowledge, decisions, and expressed opinions. Focus on what the assistant said, decided, or revealed about itself. Do NOT extract facts about the user unless the assistant is referencing them."),
}

interface ExtractionConfig {
    val schemaAdherence: SchemaAdherence

    /**
     * Backward compatibility: returns true if entities should be locked to schema.
     */
    val lockToSchema: Boolean
        get() = schemaAdherence.entities
}

/**
 * Model passed to the Jinja template for proposition extraction.
 */
data class TemplateModel(
    val context: SourceAnalysisContext,
    val chunk: Chunk,
    override val schemaAdherence: SchemaAdherence,
    val existingPropositions: List<Proposition>,
    val perspective: ExtractionPerspective = ExtractionPerspective.ALL,
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
 * @param schemaAdherence Configuration for how strictly to adhere to the schema for entities and predicates.
 * @param existingPropositionsToShow Number of existing propositions to show in the prompt.
 * @param propositionRepository Optional repository to fetch existing propositions from.
 * When provided, existing propositions for the context will be included in the prompt
 * to help the LLM avoid duplicates and maintain consistency.
 * @param perspective Controls whose perspective propositions are extracted from. See [ExtractionPerspective].
 */
data class LlmPropositionExtractor(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
    private val template: String = "dice/extract_propositions",
    private val examples: List<ObjectCreationExample<PropositionsResult>> = emptyList(),
    override val schemaAdherence: SchemaAdherence = SchemaAdherence.DEFAULT,
    val existingPropositionsToShow: Int = 100,
    private val propositionRepository: PropositionRepository? = null,
    val perspective: ExtractionPerspective = ExtractionPerspective.ALL,
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

    /**
     * Set the number of existing propositions to show in prompts.
     */
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

    /**
     * Set the schema adherence configuration.
     */
    fun withSchemaAdherence(adherence: SchemaAdherence): LlmPropositionExtractor {
        return this.copy(
            schemaAdherence = adherence,
        )
    }

    /**
     * Set the proposition repository to fetch existing propositions from.
     * When provided, existing propositions for the context will be included in the prompt.
     */
    fun withPropositionRepository(repository: PropositionRepository): LlmPropositionExtractor {
        return this.copy(
            propositionRepository = repository,
        )
    }

    /**
     * Set the extraction perspective. Controls whose facts are extracted
     * from conversational text. See [ExtractionPerspective].
     */
    fun withPerspective(perspective: ExtractionPerspective): LlmPropositionExtractor {
        return this.copy(
            perspective = perspective,
        )
    }

    private fun extractExistingPropositions(context: SourceAnalysisContext): List<Proposition> {
        if (propositionRepository == null) {
            return emptyList()
        }

        val existing = propositionRepository.findByContextId(context.contextId)
            .filter { it.status == PropositionStatus.ACTIVE }
            .sortedByDescending { it.confidence }
            .take(existingPropositionsToShow)

        if (existing.isNotEmpty()) {
            logger.debug(
                "Found {} existing propositions for context {} (showing top {})",
                propositionRepository.findByContextId(context.contextId).size,
                context.contextId,
                existing.size
            )
        }

        return existing
    }

    override fun extract(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): SuggestedPropositions {
        logger.debug("Extracting propositions from chunk {}", chunk.id)

        val existingPropositions = extractExistingPropositions(context)

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
                        schemaAdherence = schemaAdherence,
                        existingPropositions = existingPropositions,
                        perspective = perspective,
                    ),
                ) + context.promptVariables,
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
        sourceText: String?,
        mentionFilter: MentionFilter?,
    ): SuggestedEntities {
        // Collect all unique mentions across all propositions
        val uniqueMentions = mutableMapOf<MentionKey, SuggestedMention>()

        for (proposition in suggestedPropositions.propositions) {
            for (mention in proposition.mentions) {

                // Apply filter if provided
                if (mentionFilter != null && !mentionFilter.isValid(mention, proposition.text)) {
                    logger.warn(
                        "Filtered mention '{}': {}",
                        mention.span,
                        mentionFilter.rejectionReason(mention)
                    )
                    continue
                }

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
            sourceText = sourceText,
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
