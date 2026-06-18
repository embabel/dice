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

import com.embabel.common.ai.model.LlmOptions

/**
 * Shared configuration for proposition extraction from conversations.
 * Used by any application integrating the DICE memory pipeline.
 *
 * @param enabled whether proposition extraction is enabled
 * @param extractionLlm LLM for proposition extraction
 * @param entityResolutionLlm LLM for entity resolution (defaults to extractionLlm if null)
 * @param windowSize number of messages to include in each extraction window
 * @param overlapSize number of messages to overlap for context continuity
 * @param triggerInterval extract propositions every N messages (0 = manual only)
 * @param showPrompts whether to log extraction prompts
 * @param showResponses whether to log extraction responses
 * @param entityPackages packages to scan for NamedEntity classes to include in the data dictionary
 * @param existingPropositionsToShow number of existing propositions to include in the extraction prompt to avoid duplicates
 * @param classifyBatchSize max propositions per classify-batch LLM call (smaller = faster, more calls)
 * @param classifyLlm optional cheaper LLM for classification calls (defaults to extractionLlm if null)
 * @param projectionLlm LLM for graph projection relationship classification (defaults to classifyLlm if null)
 */
data class PropositionExtractionProperties(
    val enabled: Boolean = true,
    val extractionLlm: LlmOptions? = null,
    val entityResolutionLlm: LlmOptions? = null,
    val windowSize: Int = 10,
    val overlapSize: Int = 2,
    val triggerInterval: Int = 6,
    val showPrompts: Boolean = false,
    val showResponses: Boolean = false,
    val entityPackages: List<String> = emptyList(),
    val existingPropositionsToShow: Int = 100,
    val classifyBatchSize: Int = 15,
    val classifyLlm: LlmOptions? = null,
    val projectionLlm: LlmOptions? = null,
) {
    init {
        require(windowSize > 0) { "windowSize must be positive" }
        require(overlapSize >= 0) { "overlapSize must not be negative" }
        require(triggerInterval >= 0) { "triggerInterval must not be negative" }
    }
}
