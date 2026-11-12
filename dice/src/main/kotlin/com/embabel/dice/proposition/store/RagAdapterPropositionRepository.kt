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
package com.embabel.dice.proposition.store

import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.VectorSearchCapable
import org.slf4j.LoggerFactory

/**
 * Reference proposition store that backs persistence through Embabel's
 * [NamedEntityDataRepository] SPI without ever importing a graph driver or emitting Cypher from
 * production code. The store stays hexagonal: it talks to the SPI port, and the consuming
 * application supplies whatever concrete implementation it likes.
 *
 * ## Composition
 *
 * The entity SPI stores `NamedEntityData`, so a [Proposition] is held in a separate store. This
 * adapter composes two collaborators:
 *
 * - [crud]: a supplementary durable [PropositionStore] that actually holds the [Proposition]
 *   objects (CRUD, identity, the composable [query]). All base-port members delegate here.
 * - [entityRepository]: the entity/vector/relationship backend the consumer supplies. It backs
 *   the separate entity-axis projection/reconciliation path; it is held here as a real collaborator but
 *   is deliberately never exposed in this store's public signatures, so no SPI-only type leaks
 *   into the proposition contract.
 *
 * ## Declared capabilities (honest fragments only)
 *
 * The store declares exactly [PropositionStore] and [VectorSearchCapable]. Every
 * [VectorSearchCapable] member (both [findSimilarWithScores] overloads and [findClusters]) is
 * forwarded to the supplementary store when that store is itself vector-capable, so any
 * backend-pushed-down filtering or clustering override on the supplementary store is honoured
 * rather than bypassed. When the supplementary store is not vector-capable, every vector member
 * degrades to an empty result. None of these paths is ever wired to the SPI's entity
 * `vectorSearch`, which operates on a different axis (entities, not propositions) and would be
 * semantically wrong.
 *
 * ## Why graph and temporal capabilities are omitted
 *
 * The proposition capability fragments (`GraphTraversalCapable` for the abstraction hierarchy,
 * `TemporalQueryCapable` for time-window/effective-confidence queries) are proposition-scoped,
 * and the [NamedEntityDataRepository] SPI is entity-scoped. The SPI exposes only 1-hop entity
 * navigation (`findRelated`) and no temporal surface, so neither proposition fragment can be
 * served honestly here. Declaring a fragment this store cannot back would make a caller's
 * `supportsGraph` report `true` while results came back empty. With the fragments omitted, a
 * caller using `PropositionStoreTemplate` sees `supportsGraph == false` and gets empty, typed
 * results from the graph/temporal paths, never an exception. Full multi-hop / path-between
 * traversal is deferred to a future native graph-query adapter.
 *
 * The class is open so the deprecated [Neo4jRagPropositionRepository] can extend it.
 *
 * @param crud the supplementary durable proposition store backing all CRUD and query operations
 * @param entityRepository the entity/relationship/vector backend supplied by the consumer; held
 *   for the entity-axis projection path and never surfaced in this store's public signatures
 */
open class RagAdapterPropositionRepository(
    private val crud: PropositionStore,
    val entityRepository: NamedEntityDataRepository,
) : PropositionStore by crud, VectorSearchCapable {

    // Named for the runtime class, so a store built through the deprecated name logs under that name.
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Disambiguates the diamond between [VectorSearchCapable.query] and [PropositionStore.query]
     * by forwarding to the supplementary store, giving the composed type a single unambiguous query.
     */
    override fun query(query: PropositionQuery): List<Proposition> = crud.query(query)

    /**
     * Proposition vector similarity search. Forwards to the supplementary store when it is
     * vector-capable; degrades gracefully to an empty list otherwise. Never touches the
     * entity-axis SPI vector search, which operates on a different axis.
     */
    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>> {
        val capable = crud as? VectorSearchCapable
        if (capable == null) {
            logger.debug("findSimilarWithScores: supplementary store {} is not vector-capable, returning empty", crud::class.simpleName)
            return emptyList()
        }
        return capable.findSimilarWithScores(textSimilaritySearchRequest)
    }

    /**
     * Filtered similarity search. Forwards to the supplementary store so any backend that
     * pushes the query filter down to its own index gets credit for it, rather than falling
     * back to the interface default that re-filters generic results. Degrades to empty when
     * the supplementary store is not vector-capable.
     */
    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> {
        val capable = crud as? VectorSearchCapable
        if (capable == null) {
            logger.debug("findSimilarWithScores(filtered): supplementary store {} is not vector-capable, returning empty", crud::class.simpleName)
            return emptyList()
        }
        return capable.findSimilarWithScores(textSimilaritySearchRequest, query)
    }

    /**
     * Proposition clustering. Forwards to the supplementary store so any backend-native
     * clustering override is honoured. Degrades to empty when the supplementary store is
     * not vector-capable.
     */
    override fun findClusters(
        similarityThreshold: ZeroToOne,
        topK: Int,
        query: PropositionQuery,
    ): List<Cluster<Proposition>> =
        (crud as? VectorSearchCapable)?.findClusters(similarityThreshold, topK, query)
            ?: emptyList()
}
