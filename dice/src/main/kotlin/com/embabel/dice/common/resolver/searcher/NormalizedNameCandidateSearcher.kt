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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.NameNormalizer
import com.embabel.dice.common.PersonNameNormalizer
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.CandidateSearcher
import com.embabel.dice.common.resolver.SearchResult
import org.slf4j.LoggerFactory

/**
 * Finds a stored entity whose name equals the suggested name once both are normalized.
 *
 * Returns a confident match only when exactly one candidate matches after normalization.
 *
 * With the default [NameNormalizer], for entities labelled `Person`:
 * - "Dr. Watson" matches "Watson"
 * - "John Smith Jr." matches "John Smith"
 * - "Prof. Einstein" matches "Einstein"
 *
 * Names of other entities only have their whitespace collapsed. Use [withNameNormalizer] to
 * change the rules.
 *
 * @param repository The repository for text search
 * @param topK Maximum candidates to retrieve
 * @param similarityThreshold Minimum similarity score for text search
 */
class NormalizedNameCandidateSearcher private constructor(
    private val repository: NamedEntityDataRepository,
    private val topK: Int,
    private val similarityThreshold: Double,
    private val nameNormalizer: NameNormalizer,
) : CandidateSearcher {

    @JvmOverloads
    constructor(
        repository: NamedEntityDataRepository,
        topK: Int = 10,
        similarityThreshold: Double = 0.5,
    ) : this(repository, topK, similarityThreshold, NameNormalizer.standard())

    /**
     * Returns a searcher that compares names with the given normalizer.
     *
     * @param nameNormalizer the rules that decide when two names are the same
     * @return a new searcher; this one is unchanged
     */
    fun withNameNormalizer(nameNormalizer: NameNormalizer): NormalizedNameCandidateSearcher =
        NormalizedNameCandidateSearcher(repository, topK, similarityThreshold, nameNormalizer)

    private val logger = LoggerFactory.getLogger(NormalizedNameCandidateSearcher::class.java)

    override fun search(
        suggested: SuggestedEntity,
        schema: DataDictionary,
    ): SearchResult {
        val normalizedSuggested = nameNormalizer.normalize(suggested.name, suggested.labels)
        // A name with nothing left after normalization has nothing to compare
        if (normalizedSuggested.isBlank()) return SearchResult.candidates(emptyList())
        val candidates = mutableListOf<NamedEntityData>()

        try {
            val labelFilter = EntityFilter.hasAnyLabel(suggested.labels.toSet())
            val results = repository.textSearch(
                TextSimilaritySearchRequest(
                    query = luceneEscape(normalizedSuggested),
                    similarityThreshold = similarityThreshold,
                    topK = topK,
                ),
                entityFilter = labelFilter,
            )

            // Find candidates whose normalized name matches
            val matches = results.filter { result ->
                val normalizedCandidate = nameNormalizer.normalize(result.match.name, result.match.labels())
                normalizedCandidate.equals(normalizedSuggested, ignoreCase = true)
            }

            candidates.addAll(results.map { it.match })

            // Confident only if exactly 1 match
            if (matches.size == 1) {
                val match = matches.first().match
                logger.debug("NORMALIZED: '{}' -> '{}'", suggested.name, match.name)
                return SearchResult(confident = match, candidates = candidates)
            }
        } catch (e: Exception) {
            logger.warn("Normalized name search failed, so it found no candidates: {}", e.javaClass.name)
            logger.debug("Normalized name search failed for '{}'", suggested.name, e)
        }

        return SearchResult.candidates(candidates)
    }

    companion object {
        private val EVERY_NAME = PersonNameNormalizer.english().forEveryName()

        /**
         * Normalizes a name with the person rules, whatever kind of entity it belongs to.
         *
         * This is `PersonNameNormalizer.english().forEveryName()` for one name. Resolution itself
         * uses the [NameNormalizer] it was given, which by default applies these rules only to
         * entities labelled `Person`.
         *
         * Examples:
         *  - "Dr. Watson"        → "Watson"           (title)
         *  - "John Smith Jr."    → "John Smith"       (suffix)
         *  - "Lynda M Coker"     → "Lynda Coker"      (middle initial)
         *  - "Lynda M. Coker"    → "Lynda Coker"      (middle initial + dot)
         *  - "Coker, Lynda"      → "Lynda Coker"      (reversed)
         *  - "Coker, Lynda M."   → "Lynda Coker"      (reversed + initial)
         *
         * @param name the name as written
         * @return the normalized name
         */
        fun normalizeName(name: String): String = EVERY_NAME.normalize(name, emptyList())

        @JvmStatic
        fun create(repository: NamedEntityDataRepository): NormalizedNameCandidateSearcher =
            NormalizedNameCandidateSearcher(repository)
    }
}
