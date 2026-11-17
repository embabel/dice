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
package com.embabel.dice.proposition.revision

import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository

/**
 * A read view of a repository that shows the latest copy of each stored proposition a revision
 * run has changed but not saved yet.
 *
 * Without it, two results in one run that back up the same stored proposition both start from
 * the stored copy, and saving them keeps only the last one's evidence. Revising against this view
 * makes the second result start from the first one's copy instead. Writes go straight to the
 * repository underneath.
 *
 * @param delegate the repository the run revises against
 */
internal class InRunPropositions private constructor(
    private val delegate: PropositionRepository,
) : PropositionRepository by delegate {

    private val latest = LinkedHashMap<String, Proposition>()

    /**
     * Remembers the stored propositions [result] changed, so later revisions start from them.
     *
     * @param result a revision result from this run
     */
    fun record(result: RevisionResult) {
        when (result) {
            is RevisionResult.Merged -> if (result.revised != result.original) latest[result.original.id] = result.revised
            is RevisionResult.Reinforced -> if (result.revised != result.original) latest[result.original.id] = result.revised
            is RevisionResult.Contradicted -> latest[result.original.id] = result.original
            is RevisionResult.New, is RevisionResult.Generalized -> Unit
        }
    }

    override fun findById(id: String): Proposition? = latest[id] ?: delegate.findById(id)

    override fun query(query: PropositionQuery): List<Proposition> =
        delegate.query(query).mapNotNull { current(it, query) }

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>> =
        delegate.findSimilarWithScores(textSimilaritySearchRequest).mapNotNull { current(it, null) }

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> =
        delegate.findSimilarWithScores(textSimilaritySearchRequest, query).mapNotNull { current(it, query) }

    override fun findSimilar(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<Proposition> =
        findSimilarWithScores(textSimilaritySearchRequest).map { it.match }

    /**
     * Swaps in this run's copy of [stored], or drops it when that copy no longer has a status the
     * query asks for, as when the run contradicted it.
     */
    private fun current(stored: Proposition, query: PropositionQuery?): Proposition? {
        val copy = latest[stored.id] ?: return stored
        val statuses = query?.statuses
        return if (statuses == null || copy.status in statuses) copy else null
    }

    private fun current(result: SimilarityResult<Proposition>, query: PropositionQuery?): SimilarityResult<Proposition>? {
        val copy = current(result.match, query) ?: return null
        return if (copy === result.match) result else SimilarityResult(match = copy, score = result.score)
    }

    companion object {

        /**
         * Wraps [repository], or returns it when it already is a view for some run.
         *
         * @param repository the repository to revise against
         * @return a view that tracks the latest copy of what the run changes
         */
        fun over(repository: PropositionRepository): InRunPropositions =
            repository as? InRunPropositions ?: InRunPropositions(repository)

        /**
         * A new view on top of [repository], even when it is already a view. What this view
         * records stays out of the one underneath until the caller records it there too, so a
         * chunk that fails part way leaves the run as it was.
         *
         * @param repository the repository, or the run's view, to read through
         * @return a fresh view
         */
        fun layeredOver(repository: PropositionRepository): InRunPropositions = InRunPropositions(repository)
    }
}
