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
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.CandidateSearcher
import com.embabel.dice.common.resolver.SearchResult
import org.slf4j.LoggerFactory

/**
 * Searches for an exact match by ID.
 *
 * This is the cheapest searcher - it returns a confident match
 * only if exactly 1 entity is found with the given ID.
 *
 * @param repository The repository for ID lookup
 */
class ByIdCandidateSearcher(
    private val repository: NamedEntityDataRepository,
) : CandidateSearcher {

    private val logger = LoggerFactory.getLogger(ByIdCandidateSearcher::class.java)

    override fun search(
        suggested: SuggestedEntity,
        schema: DataDictionary,
    ): SearchResult {
        val id = suggested.id ?: return SearchResult.empty()

        return try {
            repository.findById(id)?.let { existing ->
                logger.debug("BY_ID: '{}' -> '{}'", suggested.name, existing.name)
                SearchResult.confident(existing)
            } ?: SearchResult.empty()
        } catch (e: Exception) {
            logger.debug("ID lookup failed for '{}': {}", id, e.message)
            SearchResult.empty()
        }
    }

    companion object {
        @JvmStatic
        fun create(repository: NamedEntityDataRepository): ByIdCandidateSearcher =
            ByIdCandidateSearcher(repository)
    }
}
