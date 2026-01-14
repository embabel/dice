package com.embabel.dice.common.resolver.searcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.CandidateSearcher
import com.embabel.dice.common.resolver.MatchResult
import com.embabel.dice.common.resolver.SearchResult
import com.embabel.dice.common.resolver.defaultMatchStrategies
import com.embabel.dice.common.resolver.matcher.ChainedEntityMatchingStrategy
import org.slf4j.LoggerFactory

/**
 * Searches using text/full-text search and applies heuristic matching strategies.
 *
 * Uses the provided matching strategies to determine if any candidate
 * is a confident match. If a strategy returns [MatchResult.Match],
 * returns that as a confident result.
 *
 * @param repository The repository for text search
 * @param matchStrategies Strategies to evaluate candidates (ordered cheapest first)
 * @param topK Maximum candidates to retrieve
 * @param similarityThreshold Minimum similarity score for text search
 */
class TextCandidateSearcher(
    private val repository: NamedEntityDataRepository,
    private val matchStrategies: ChainedEntityMatchingStrategy = defaultMatchStrategies(),
    private val topK: Int = 10,
    private val similarityThreshold: Double = 0.5,
) : CandidateSearcher {

    private val logger = LoggerFactory.getLogger(TextCandidateSearcher::class.java)

    override fun search(
        suggested: SuggestedEntity,
        schema: DataDictionary,
    ): SearchResult {
        val candidates = mutableListOf<NamedEntityData>()

        try {
            val query = buildTextQuery(suggested.name)
            val labelFilter = EntityFilter.hasAnyLabel(suggested.labels.toSet())
            val results = repository.textSearch(
                TextSimilaritySearchRequest(
                    query = query,
                    similarityThreshold = similarityThreshold,
                    topK = topK,
                ),
                entityFilter = labelFilter,
            )

            for (result in results) {
                val candidate = result.match
                candidates.add(candidate)

                // Check if this is a confident match via heuristics
                if (matchStrategies.matches(suggested, candidate, schema)) {
                    logger.debug(
                        "HEURISTIC: '{}' -> '{}' (score: {})",
                        suggested.name, candidate.name, result.score
                    )
                    return SearchResult(confident = candidate, candidates = candidates)
                }
            }
        } catch (e: Exception) {
            logger.debug("Text search failed for '{}': {}", suggested.name, e.message)
        }

        return SearchResult.candidates(candidates)
    }

    private fun buildTextQuery(name: String): String {
        val parts = mutableListOf<String>()
        parts.add("\"$name\"^2")  // Exact phrase boost
        name.split(Regex("\\s+")).filter { it.length >= 2 }.forEach { term ->
            parts.add(term)
            if (term.length >= 4) parts.add("$term~")  // Fuzzy
        }
        return parts.joinToString(" OR ")
    }

    companion object {
        @JvmStatic
        fun create(
            repository: NamedEntityDataRepository,
            matchStrategies: ChainedEntityMatchingStrategy = defaultMatchStrategies(),
        ): TextCandidateSearcher = TextCandidateSearcher(repository, matchStrategies)
    }
}
