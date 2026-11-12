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

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.GraphTraversalCapable
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStoreTemplate
import com.embabel.dice.proposition.VectorSearchCapable
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.ConcurrentHashMap

/**
 * End-to-end check that the RAG-backed proposition store's declared fragments hold up against a
 * real container-backed entity repository.
 *
 * Composes a vector-backed supplementary proposition store (for CRUD) with a driver-backed
 * [NamedEntityDataRepository] view over a live Neo4j (the entity axis). All Cypher stays
 * in this test file — none leaks into production code.
 *
 * Skipped automatically when no container runtime is available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RagAdapterDeclaredFragmentsIT {

    private val contextId = ContextId("rag-adapter-fragments")

    @Test
    fun `declared fragments behave correctly over a real entity repository view`() {
        GraphDatabase.driver(
            neo4j.boltUrl,
            AuthTokens.basic("neo4j", neo4j.adminPassword),
        ).use { driver ->
            driver.session().use { it.run("MATCH (n) DETACH DELETE n") }

            val adapter = RagAdapterPropositionRepository(
                crud = vectorBackedCrud(),
                entityRepository = graphBackedRepository(driver),
            )

            // (a) CRUD through the adapter is retrievable via the adapter.
            val saved = adapter.save(proposition("A likes B"))
            assertEquals(saved, adapter.findById(saved.id), "saved proposition is retrievable via findById")
            assertTrue(
                adapter.query(PropositionQuery()).any { it.id == saved.id },
                "saved proposition is visible via query",
            )

            // (b) Vector search is declared and returns the supplementary store's real results.
            assertTrue(adapter is VectorSearchCapable, "adapter declares vector search")
            val request = TextSimilaritySearchRequest(query = "A likes B", similarityThreshold = 0.5, topK = 10)
            assertFalse(adapter.findSimilar(request).isEmpty(), "vector search returns real results")

            // (c) Graph traversal is omitted and degrades to empty via the template, never throws.
            assertFalse(adapter is GraphTraversalCapable, "adapter does not declare graph traversal")
            val template = PropositionStoreTemplate(adapter)
            assertFalse(template.supportsGraph, "template reports no graph support")
            assertTrue(template.findSources(saved).isEmpty(), "findSources degrades to empty")
        }
    }

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )

    private fun vectorBackedCrud(): InMemoryPropositionRepository {
        val embeddingMap = ConcurrentHashMap<String, FloatArray>()
        embeddingMap["A likes B"] = floatArrayOf(1f, 0f, 0f)
        val embeddingService = mock<EmbeddingService>()
        whenever(embeddingService.embed(any<String>())).thenAnswer { invocation ->
            val text = invocation.getArgument<String>(0)
            embeddingMap[text] ?: floatArrayOf(0f, 0f, 0f)
        }
        return InMemoryPropositionRepository(embeddingService)
    }

    /**
     * Builds a mock entity repository backed by the live Neo4j container.
     * ID lookup, save, and relationship merge all run real Cypher against the container.
     * All Cypher is confined to this method.
     */
    private fun graphBackedRepository(driver: Driver): NamedEntityDataRepository {
        val repository = mockk<NamedEntityDataRepository>(relaxed = true)

        every { repository.findById(any()) } answers {
            val id = firstArg<String>()
            driver.session().use { session ->
                val result = session.run(
                    "MATCH (n {id: \$id}) RETURN labels(n) AS labels, n.name AS name LIMIT 1",
                    mapOf<String, Any>("id" to id),
                )
                if (!result.hasNext()) {
                    null
                } else {
                    val record = result.next()
                    SimpleNamedEntityData(
                        id = id,
                        name = record["name"].asString(id),
                        description = "",
                        labels = record["labels"].asList { it.asString() }.toSet(),
                        properties = emptyMap(),
                    ) as NamedEntityData
                }
            }
        }

        every { repository.save(any()) } answers {
            val entity = firstArg<NamedEntityData>()
            driver.session().use { session ->
                session.run(
                    "MERGE (n {id: \$id}) SET n.name = \$name",
                    mapOf<String, Any>("id" to entity.id, "name" to entity.name),
                )
            }
            entity
        }

        every { repository.mergeRelationship(any(), any(), any()) } answers {
            val source = firstArg<RetrievableIdentifier>()
            val target = secondArg<RetrievableIdentifier>()
            val rel = thirdArg<RelationshipData>()
            driver.session().use { session ->
                session.run(
                    "MATCH (a {id: \$source}), (b {id: \$target}) MERGE (a)-[r:RELATED {type: \$type}]->(b)",
                    mapOf<String, Any>("source" to source.id, "target" to target.id, "type" to rel.name),
                )
            }
        }

        return repository
    }

    companion object {
        @Container
        @JvmStatic
        val neo4j: Neo4jContainer<*> = Neo4jContainer("neo4j:5-community")
    }
}
