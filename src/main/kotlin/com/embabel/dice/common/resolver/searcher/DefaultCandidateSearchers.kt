package com.embabel.dice.common.resolver.searcher

import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.common.resolver.CandidateSearcher

/**
 * Factory for creating default candidate searcher chains.
 *
 * The default chain is ordered cheapest-first:
 * 1. By ID lookup (instant)
 * 2. Exact name match (instant)
 * 3. Normalized name match - "Dr. Watson" -> "Watson"
 * 4. Partial name match - "Brahms" -> "Johannes Brahms"
 * 5. Fuzzy name match - handles typos
 * 6. Vector/embedding search (moderate)
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
     * @return List of searchers ordered cheapest-first
     */
    @JvmStatic
    fun create(repository: NamedEntityDataRepository): List<CandidateSearcher> = listOf(
        ByIdCandidateSearcher(repository),
        ByExactNameCandidateSearcher(repository),
        NormalizedNameCandidateSearcher(repository),
        PartialNameCandidateSearcher(repository),
        FuzzyNameCandidateSearcher(repository),
        VectorCandidateSearcher(repository),
    )

    /**
     * Create a chain without vector search.
     *
     * @param repository The repository for search operations
     * @return List of searchers without vector search
     */
    @JvmStatic
    fun withoutVector(repository: NamedEntityDataRepository): List<CandidateSearcher> = listOf(
        ByIdCandidateSearcher(repository),
        ByExactNameCandidateSearcher(repository),
        NormalizedNameCandidateSearcher(repository),
        PartialNameCandidateSearcher(repository),
        FuzzyNameCandidateSearcher(repository),
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
