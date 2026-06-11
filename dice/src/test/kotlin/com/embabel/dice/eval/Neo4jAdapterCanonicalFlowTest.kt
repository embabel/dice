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
package com.embabel.dice.eval

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.GraphTraversalCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.TemporalQueryCapable
import com.embabel.dice.proposition.VectorSearchCapable
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.proposition.store.Neo4jRagPropositionRepository
import com.embabel.agent.rag.service.RetrievableIdentifier

/**
 * Thin bridge that wires a [Neo4jRagPropositionRepository] into the TCK's [PropositionRepository]
 * variable without modifying the adapter itself.
 *
 * The adapter intentionally doesn't declare [GraphTraversalCapable] or [TemporalQueryCapable] — it
 * only promises what it actually backs. Both interfaces carry default implementations built on
 * [findAll] and [findById], which the adapter's supplementary store already handles, so this wrapper
 * just re-declares them to satisfy the TCK's compile-time type without adding any real logic.
 *
 * Every call routes straight through to the adapter unchanged.
 */
private class TckPropositionRepositoryBridge(
    private val adapter: Neo4jRagPropositionRepository,
) : PropositionRepository,
    VectorSearchCapable by adapter,
    GraphTraversalCapable,
    TemporalQueryCapable {

    // PropositionStore delegation
    override fun save(proposition: Proposition): Proposition = adapter.save(proposition)
    override fun findById(id: String): Proposition? = adapter.findById(id)
    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = adapter.findByEntity(entityIdentifier)
    override fun findByStatus(status: PropositionStatus): List<Proposition> = adapter.findByStatus(status)
    override fun findByGrounding(chunkId: String): List<Proposition> = adapter.findByGrounding(chunkId)
    override fun findByMinLevel(minLevel: Int): List<Proposition> = adapter.findByMinLevel(minLevel)
    override fun findAll(): List<Proposition> = adapter.findAll()
    override fun delete(id: String): Boolean = adapter.delete(id)
    override fun count(): Int = adapter.count()
    override fun query(query: PropositionQuery): List<Proposition> = adapter.query(query)

    // VectorSearchCapable is fully handled by the `by adapter` delegation clause above.

    override val luceneSyntaxNotes: String get() = "no lucene support"
}

/**
 * Concrete, CI-runnable instantiation of the canonical-flow contract against the
 * [Neo4jRagPropositionRepository] adapter, running entirely offline — no Docker, driver,
 * graph database, embedding model, or LLM required.
 *
 * The adapter composes two offline doubles:
 * - An [InMemoryPropositionRepository] wired with the TCK's deterministic [FixedVectorEmbeddingService]
 *   backs the CRUD and vector-search axis, so the similarity assertions in the inherited test have
 *   real, non-empty results.
 * - An [InMemoryNamedEntityDataRepository] (from `embabel-agent-rag-core`) backs the entity-repository
 *   axis; it holds no data and is never queried by the canonical flow, matching the adapter's declared
 *   contract (the entity axis is not surfaced through the proposition contract).
 *
 * A thin [TckPropositionRepositoryBridge] re-declares [GraphTraversalCapable] and
 * [TemporalQueryCapable] so the TCK's typed [PropositionRepository] variable compiles, while still
 * routing every real operation through the adapter without modification.
 *
 * The inherited end-to-end flow runs green with no external dependencies, confirming that the adapter
 * correctly delegates all proposition operations to its supplementary store and that the TCK's
 * vector, graph-query, collector, and report stages all work correctly through the extra delegation layer.
 */
class Neo4jAdapterCanonicalFlowTest : AbstractCanonicalFlowTest() {

    override fun newStore(): PropositionRepository {
        val embeddingService = newEmbeddingService()
        val crud = InMemoryPropositionRepository(embeddingService = embeddingService)
        val entityRepository = InMemoryNamedEntityDataRepository(
            dataDictionary = DataDictionary.fromClasses("neo4j-adapter-tck"),
        )
        val adapter = Neo4jRagPropositionRepository(crud = crud, entityRepository = entityRepository)
        return TckPropositionRepositoryBridge(adapter)
    }
}
