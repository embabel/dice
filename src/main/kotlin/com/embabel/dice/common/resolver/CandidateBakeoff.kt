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
