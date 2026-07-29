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
package com.embabel.dice.report

import com.embabel.agent.core.ContextId
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence port for reviewable [SemanticLink]s.
 */
@ApiStatus.Experimental
interface SemanticLinkStore {

    /**
     * Return the natural key for [link], independent of endpoint order.
     */
    @ApiStatus.Experimental
    fun idOf(link: SemanticLink): String = listOf(
        minOf(link.sourceEntityId, link.targetEntityId),
        maxOf(link.sourceEntityId, link.targetEntityId),
        link.kind.name,
    ).joinToString("|")

    /**
     * Upsert [link] in [contextId].
     *
     * An existing accepted or rejected review decision is retained.
     */
    @ApiStatus.Experimental
    fun record(contextId: ContextId, link: SemanticLink): SemanticLink

    /**
     * Find links in [contextId], optionally restricted to [status].
     */
    @ApiStatus.Experimental
    fun find(contextId: ContextId, status: ReviewStatus? = null): List<SemanticLink>

    /**
     * Update the review status of [id] in [contextId], returning `null` when absent.
     */
    @ApiStatus.Experimental
    fun updateReviewStatus(contextId: ContextId, id: String, status: ReviewStatus): SemanticLink?
}

/**
 * Thread-safe, in-process [SemanticLinkStore] for tests and single-node applications.
 */
@ApiStatus.Experimental
class InMemorySemanticLinkStore : SemanticLinkStore {

    private val linksByContext = ConcurrentHashMap<ContextId, ConcurrentHashMap<String, SemanticLink>>()

    @ApiStatus.Experimental
    override fun record(contextId: ContextId, link: SemanticLink): SemanticLink {
        val stored = linksByContext
            .computeIfAbsent(contextId) { ConcurrentHashMap() }
            .compute(idOf(link)) { _, existing ->
                val refreshed = link.detached()
                when (existing?.reviewStatus) {
                    ReviewStatus.ACCEPTED,
                    ReviewStatus.REJECTED,
                    -> refreshed.copy(reviewStatus = existing.reviewStatus)

                    else -> refreshed
                }
            }!!
        return stored.detached()
    }

    @ApiStatus.Experimental
    override fun find(contextId: ContextId, status: ReviewStatus?): List<SemanticLink> =
        linksByContext[contextId]
            ?.values
            ?.asSequence()
            ?.filter { status == null || it.reviewStatus == status }
            ?.sortedBy(::idOf)
            ?.map { it.detached() }
            ?.toList()
            ?: emptyList()

    @ApiStatus.Experimental
    override fun updateReviewStatus(
        contextId: ContextId,
        id: String,
        status: ReviewStatus,
    ): SemanticLink? = linksByContext[contextId]
        ?.computeIfPresent(id) { _, link -> link.copy(reviewStatus = status) }
        ?.detached()

    private fun SemanticLink.detached(): SemanticLink = copy(
        connectingEntityIds = connectingEntityIds.toList(),
        sourcePropositionIds = sourcePropositionIds.toList(),
    )
}
