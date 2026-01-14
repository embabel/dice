package com.embabel.dice.common.resolver.searcher

import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.common.resolver.CandidateSearcher
import com.embabel.dice.common.resolver.defaultMatchStrategies
import com.embabel.dice.common.resolver.matcher.ChainedEntityMatchingStrategy

/**
 * Factory for creating default candidate searcher chains.
 *
 * The default chain is ordered cheapest-first:
 * 1. By ID lookup (instant)
 * 2. Exact name match (instant)
 * 3. Text search with heuristic matching (fast)
 * 4. Vector/embedding search (moderate)
 *
 * Java usage:
 * ```java
 * List<CandidateSearcher> searchers = DefaultCandidateSearchers.create(repository);
 * ```
 */
object DefaultCandidateSearchers {

    /**
     * Create the default chain of candidate searchers.
     *
     * @param repository The repository for search operations
     * @param matchStrategies Strategies for heuristic matching
     * @return List of searchers ordered cheapest-first
     */
    @JvmStatic
    fun create(
        repository: NamedEntityDataRepository,
        matchStrategies: ChainedEntityMatchingStrategy = defaultMatchStrategies(),
    ): List<CandidateSearcher> = listOf(
        ByIdCandidateSearcher(repository),
        ByExactNameCandidateSearcher(repository),
        TextCandidateSearcher(repository, matchStrategies),
        VectorCandidateSearcher(repository, matchStrategies),
    )

    /**
     * Create a chain without vector search.
     *
     * @param repository The repository for search operations
     * @param matchStrategies Strategies for heuristic matching
     * @return List of searchers without vector search
     */
    @JvmStatic
    fun withoutVector(
        repository: NamedEntityDataRepository,
        matchStrategies: ChainedEntityMatchingStrategy = defaultMatchStrategies(),
    ): List<CandidateSearcher> = listOf(
        ByIdCandidateSearcher(repository),
        ByExactNameCandidateSearcher(repository),
        TextCandidateSearcher(repository, matchStrategies),
    )

    /**
     * Create a chain with only exact match searchers.
     *
     * @param repository The repository for search operations
     * @return List containing only exact match searchers (by ID and by name)
     */
    @JvmStatic
    fun exactOnly(repository: NamedEntityDataRepository): List<CandidateSearcher> = listOf(
        ByIdCandidateSearcher(repository),
        ByExactNameCandidateSearcher(repository),
    )
}
