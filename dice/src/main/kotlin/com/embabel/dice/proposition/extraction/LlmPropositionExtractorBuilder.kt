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
import com.embabel.common.ai.model.LlmOptions

/**
 * Builder returned by [LlmPropositionExtractor.builder]. It holds the model options
 * until an [Ai] is supplied, then creates the extractor.
 */
class LlmPropositionExtractorBuilder internal constructor(
    private val llmOptions: LlmOptions,
) {

    /**
     * Create the extractor with the given AI service.
     *
     * @param ai the AI service the extractor uses to call the model
     * @return an extractor that uses the stored model options and the given AI service
     */
    fun withAi(ai: Ai): LlmPropositionExtractor =
        LlmPropositionExtractor(llmOptions = llmOptions, ai = ai)
}
