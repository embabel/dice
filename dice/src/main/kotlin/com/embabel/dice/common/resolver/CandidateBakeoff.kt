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
package com.embabel.dice.common.resolver

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.common.SuggestedEntity

/**
 * Selects the best match from multiple candidates.
 *
 * When multiple candidates are found by [CandidateSearcher]s but none are confident,
 * a bakeoff can compare them and select the best match.
 *
 * @see LlmCandidateBakeoff for an LLM-based implementation
 */
interface CandidateBakeoff {

    /**
     * Select the best matching candidate from a list.
     *
     * @param suggested The entity we're trying to match
     * @param candidates The search results to evaluate
     * @param sourceText Optional conversation/source text for additional context
     * @return The best matching candidate, or null if none are suitable
     */
    fun selectBestMatch(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        sourceText: String? = null,
    ): NamedEntityData?
}
