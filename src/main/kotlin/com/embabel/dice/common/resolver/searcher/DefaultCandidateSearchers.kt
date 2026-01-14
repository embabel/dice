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
