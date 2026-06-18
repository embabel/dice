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
package com.embabel.dice.projection.graph

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.ZeroToOne
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory

/**
 * LLM-based implementation of [RelationshipDescriptionSynthesizer].
 *
 * Uses structured output to synthesize a concise description for a relationship
 * from the propositions that mention both entities.
 *
 * Example usage:
 * ```kotlin
 * val synthesizer = LlmRelationshipDescriptionSynthesizer
 *     .withLlm(llmOptions)
 *     .withAi(ai)
 * ```
 */
data class LlmRelationshipDescriptionSynthesizer(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
) : RelationshipDescriptionSynthesizer {

    companion object {

        @JvmStatic
        fun withLlm(llm: LlmOptions): Builder = Builder(llm)

        class Builder(private val llmOptions: LlmOptions) {

            fun withAi(ai: Ai): LlmRelationshipDescriptionSynthesizer =
                LlmRelationshipDescriptionSynthesizer(
                    llmOptions = llmOptions,
                    ai = ai,
                )
        }
    }

    private val logger = LoggerFactory.getLogger(LlmRelationshipDescriptionSynthesizer::class.java)

    override fun synthesize(request: SynthesisRequest): SynthesisResult {
        if (request.propositions.isEmpty()) {
            logger.debug("No propositions to synthesize description from")
            return SynthesisResult(
                description = request.existingDescription ?: "",
                confidence = 0.0,
                sourcePropositionIds = emptyList(),
            )
        }

        val propositionData = request.propositions.mapIndexed { index, p ->
            mapOf(
                "index" to index,
                "text" to p.text,
                "confidence" to p.confidence,
            )
        }

        val templateParams = mutableMapOf<String, Any>(
            "sourceEntityName" to request.sourceEntityName,
            "targetEntityName" to request.targetEntityName,
            "relationshipType" to request.relationshipType,
            "propositions" to propositionData,
        )
        request.existingDescription?.let { templateParams["existingDescription"] = it }

        val response = ai
            .withLlm(llmOptions)
            .withId("synthesize-relationship-description")
            .creating(SynthesisResponse::class.java)
            .fromTemplate(
                "dice/synthesize_relationship_description",
                templateParams,
            )

        val sourcePropositionIds = response.sourceIndices
            .filter { it in request.propositions.indices }
            .map { request.propositions[it].id }

        logger.info(
            "Synthesized description for {} -> {} ({}): '{}'",
            request.sourceEntityName,
            request.targetEntityName,
            request.relationshipType,
            response.description,
        )

        return SynthesisResult(
            description = response.description,
            confidence = response.confidence.coerceIn(0.0, 1.0),
            sourcePropositionIds = sourcePropositionIds,
        )
    }
}

internal data class SynthesisResponse(
    @param:JsonPropertyDescription("Concise description of the relationship")
    val description: String,

    @param:JsonPropertyDescription("Confidence in the description (0.0-1.0)")
    val confidence: ZeroToOne,

    @param:JsonPropertyDescription("Indices of propositions supporting this description")
    val sourceIndices: List<Int>,
)
