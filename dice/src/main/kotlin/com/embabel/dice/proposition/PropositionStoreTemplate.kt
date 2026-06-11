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
package com.embabel.dice.proposition

import com.embabel.agent.rag.service.Cluster
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne

/**
 * Portable boundary that lets domain code program against a [PropositionStore] and reach for the
 * optional vector and graph capabilities without first knowing whether the backing store honours
 * them.
 *
 * The contract is graceful degradation: when the wrapped store does not implement a capability, the
 * matching method returns an empty, typed result — it never throws. When the store does implement
 * the capability, the call delegates straight through to the store's real behaviour.
 *
 * Capability presence is queryable up front via [supportsVector] and [supportsGraph], so callers can
 * branch on availability rather than inspecting an empty result and guessing why it was empty.
 */
class PropositionStoreTemplate(private val store: PropositionStore) {

    /**
     * Vector similarity search if the store backs it, otherwise an empty list.
     */
    fun findSimilar(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<Proposition> =
        (store as? VectorSearchCapable)?.findSimilar(textSimilaritySearchRequest) ?: emptyList()

    /**
     * Clusters of similar propositions if the store backs vector search, otherwise an empty list.
     */
    fun findClusters(
        similarityThreshold: ZeroToOne = 0.7,
        topK: Int = 10,
    ): List<Cluster<Proposition>> =
        (store as? VectorSearchCapable)?.findClusters(similarityThreshold, topK) ?: emptyList()

    /**
     * Source propositions a given proposition was abstracted from, if the store backs graph
     * traversal, otherwise an empty list.
     */
    fun findSources(proposition: Proposition): List<Proposition> =
        (store as? GraphTraversalCapable)?.findSources(proposition) ?: emptyList()

    /**
     * Propositions that were abstracted from the given proposition, if the store backs graph
     * traversal, otherwise an empty list.
     */
    fun findAbstractionsOf(propositionId: String): List<Proposition> =
        (store as? GraphTraversalCapable)?.findAbstractionsOf(propositionId) ?: emptyList()

    /** Whether the wrapped store honestly backs vector similarity search. */
    val supportsVector: Boolean get() = store is VectorSearchCapable

    /** Whether the wrapped store honestly backs abstraction-hierarchy traversal. */
    val supportsGraph: Boolean get() = store is GraphTraversalCapable
}
