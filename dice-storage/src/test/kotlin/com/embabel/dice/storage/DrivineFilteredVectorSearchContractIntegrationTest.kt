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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.conformance.AbstractFilteredVectorSearchContractTest
import com.embabel.dice.conformance.ConformanceEmbeddingService
import com.embabel.dice.conformance.ConformanceEmbeddings
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.store.VectorFiltering
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Declares a [ConformanceEmbeddingService] as the primary `EmbeddingService` of the Spring context
 * that [DrivineFilteredVectorSearchContractIntegrationTest] starts. The repository and the vector
 * index of that context are built from this service. The service has the same width as
 * [FakeEmbeddingService], because every context ensures the same vector index on the shared
 * container. Every other test class keeps the fake service that [TestApplication] supplies.
 */
@TestConfiguration
open class ConformanceEmbeddingConfig {

    @Bean
    @Primary
    open fun conformanceEmbeddingService(): EmbeddingService =
        ConformanceEmbeddingService(FakeEmbeddingService().dimensions)
}

/**
 * Runs the filtered vector search suite against the Neo4j-backed [DrivinePropositionRepository]
 * (testcontainer). The graph store publishes no capability descriptor, so the suite checks that it
 * declares [VectorFiltering.UNDECLARED] and skips the cases that need a filtering mode.
 *
 * The store of this class embeds with [ConformanceEmbeddingService] through
 * [ConformanceEmbeddingConfig]. The skipped cases place each text at a known angle from the query,
 * and only that service puts it there.
 */
@SpringBootTest(classes = [TestApplication::class, ConformanceEmbeddingConfig::class])
class DrivineFilteredVectorSearchContractIntegrationTest : AbstractFilteredVectorSearchContractTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    override fun store(): PropositionStore = repository

    override fun expectedVectorFiltering(): VectorFiltering = VectorFiltering.UNDECLARED

    /**
     * Saves a proposition whose text sits at 60 degrees from the query and checks the vector the
     * store wrote. The stored vector must have the expected width, and its first two components
     * must match the conformance embedding of that text.
     */
    @Test
    fun `the store of this context embeds with the conformance embedding service`() {
        val text = ConformanceEmbeddings.text("wired", 60.0)
        val saved = repository.save(
            Proposition(
                contextId = ContextId("conformance-embedding-wiring"),
                text = text,
                mentions = emptyList(),
                confidence = 0.9,
                status = PropositionStatus.ACTIVE,
            )
        )
        val dimensions = FakeEmbeddingService().dimensions
        val expected = ConformanceEmbeddings.embed(text, dimensions)

        assertEquals(1L, storedVectorMatches(saved.id, dimensions, expected[0].toDouble(), expected[1].toDouble()))
    }

    /**
     * Compares a stored embedding with an expected one inside Cypher, so the read returns a single
     * number. The result is 1 when the embedding has [dimensions] components and its first two
     * components are within 1e-6 of [x] and [y]. Otherwise the result is 0.
     *
     * @param id The id of the proposition whose embedding is read.
     * @param dimensions The width the stored embedding must have.
     * @param x The expected first component.
     * @param y The expected second component.
     * @return 1 when the stored embedding matches, 0 otherwise.
     */
    private fun storedVectorMatches(id: String, dimensions: Int, x: Double, y: Double): Long =
        persistenceManager.getOne(
            QuerySpecification.withStatement(
                """
                MATCH (p:Proposition {id: ${'$'}id})
                RETURN CASE
                         WHEN size(p.embedding) = ${'$'}dimensions
                          AND abs(p.embedding[0] - ${'$'}x) < ${'$'}tolerance
                          AND abs(p.embedding[1] - ${'$'}y) < ${'$'}tolerance
                         THEN 1 ELSE 0 END
                """.trimIndent()
            )
                .bind(mapOf("id" to id, "dimensions" to dimensions, "x" to x, "y" to y, "tolerance" to 1e-6))
                .transform(Long::class.java)
        )

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (s:Source) DETACH DELETE s"))
    }
}
