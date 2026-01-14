package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntity

/**
 * Result of a candidate search operation.
 *
 * @property confident If non-null, a confident match was found and resolution can stop early
 * @property candidates All candidates found by this search, passed along for potential LLM arbitration
 */
data class SearchResult(
    val confident: NamedEntityData? = null,
    val candidates: List<NamedEntityData> = emptyList(),
) {
    companion object {
        /**
         * No matches found.
         */
        @JvmStatic
        fun empty(): SearchResult = SearchResult()

        /**
         * A confident match was found - resolution should stop.
         */
        @JvmStatic
        fun confident(match: NamedEntityData): SearchResult =
            SearchResult(confident = match, candidates = listOf(match))

        /**
         * Candidates found but none are confident matches.
         */
        @JvmStatic
        fun candidates(candidates: List<NamedEntityData>): SearchResult =
            SearchResult(candidates = candidates)
    }
}

/**
 * A searcher that finds candidate entities for a suggested entity.
 *
 * Unlike [EntityMatchingStrategy] which compares entities, a CandidateSearcher
 * actively searches for and retrieves candidates from a data source.
 *
 * Searchers are designed to be chained in an [EscalatingEntityResolver]:
 * - Each searcher does its own search and returns candidates
 * - If a searcher returns a confident match, resolution stops early
 * - Otherwise, candidates are accumulated for potential LLM arbitration
 *
 * Searchers should be ordered cheapest-first:
 * 1. Exact ID/name lookup (instant, no LLM)
 * 2. Heuristic/fuzzy search (fast, no LLM)
 * 3. Vector/embedding search (moderate, no LLM)
 *
 * LLM is not a searcher - it's an arbiter that receives accumulated candidates.
 */
interface CandidateSearcher {

    /**
     * Search for candidates matching the suggested entity.
     *
     * @param suggested The suggested entity to find matches for
     * @param schema The data dictionary for type hierarchy information
     * @return Search result containing confident match (if any) and candidates
     */
    fun search(
        suggested: SuggestedEntity,
        schema: DataDictionary,
    ): SearchResult
}
