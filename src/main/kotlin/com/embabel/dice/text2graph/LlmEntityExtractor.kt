package com.embabel.dice.text2graph

import com.embabel.agent.api.common.Ai
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities

/**
 * LLM-based entity extractor.
 *
 * Uses structured output to extract entities from text based on the schema
 * defined in the [com.embabel.dice.common.SourceAnalysisContext].
 *
 * Example usage:
 * ```kotlin
 * val extractor = LlmEntityExtractor
 *     .withLlm(llmOptions)
 *     .withAi(ai)
 *
 * val entities = extractor.suggestEntities(chunk, context)
 * ```
 *
 * @param llmOptions LLM configuration (model, temperature, etc.)
 * @param ai The Embabel AI instance for LLM calls
 * @param template Template name for entity extraction prompt (default: "suggest_entities")
 */
data class LlmEntityExtractor(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
    private val template: String = "suggest_entities",
) : EntityExtractor {

    companion object {

        /**
         * Start building an LlmEntityExtractor with the specified LLM options.
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
             * Set the AI instance and create the extractor.
             *
             * @param ai The Embabel AI instance for LLM calls
             * @return LlmEntityExtractor ready for use
             */
            fun withAi(ai: Ai): LlmEntityExtractor =
                LlmEntityExtractor(
                    llmOptions = llmOptions,
                    ai = ai,
                )
        }
    }

    /**
     * Set a custom template for entity extraction.
     *
     * @param templateName The template name (resolved by TemplateResolver)
     * @return New instance with the specified template
     */
    fun withTemplate(templateName: String): LlmEntityExtractor =
        copy(template = templateName)

    override fun suggestEntities(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): SuggestedEntities {
        val entities = ai
            .withLlm(llmOptions)
            .withId("extract-entities")
            .creating(ExtractedEntities::class.java)
            .fromTemplate(
                template,
                mapOf(
                    "context" to context,
                    "chunk" to chunk,
                ) + context.promptVariables,
            )
        return SuggestedEntities(
            suggestedEntities = entities.entities.map {
                it.toSuggestedEntity(chunk.id)
            },
            sourceText = chunk.text,
        )
    }
}