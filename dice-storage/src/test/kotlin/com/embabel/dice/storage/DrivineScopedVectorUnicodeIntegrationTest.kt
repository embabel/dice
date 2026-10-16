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
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

@SpringBootTest(classes = [TestApplication::class])
class DrivineScopedVectorUnicodeIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired private lateinit var repository: DrivinePropositionRepository
    @Autowired private lateinit var manager: PersistenceManager
    @Autowired private lateinit var graph: StatelessGraphObjectManager
    @Autowired private lateinit var registry: DatabaseRegistry
    @Autowired private lateinit var sources: DataSourceMap
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val at = Instant.parse("2026-10-15T12:00:00Z")
    private val budget = ExactVectorBudget(100, Duration.ofSeconds(20))
    private val unit = FloatArray(16) { if (it == 0) 1f else 0f }
    private val embedder = object : EmbeddingService by FakeEmbeddingService() {
        override fun embed(text: String): FloatArray = unit
    }
    private fun executor() = Neo4jScopedReadExecutor.from(manager, registry, sources)
    private fun native() = DrivinePropositionRepository(graph, manager, embedder, transactionManager, "unicode-test-index")
        .withNativeReadExecutor(executor())
    private fun request(k: Int) = TextSimilaritySearchRequest(query = "query", topK = k, similarityThreshold = 0.0)
    private fun proposition(id: String, context: String) = Proposition(
        id = id, text = "text $id $context", contextId = ContextId(context), confidence = 0.9,
        created = at, contentRevised = at, metadataRevised = at, mentions = emptyList(),
    )

    @AfterEach
    fun clean() { repository.clearAll() }

    @Test
    fun contextTiesMatchResidentBeforeTopK() {
        listOf(listOf("\uE000", "😀"), listOf("z", "a", "aa", "a😀", "a\uE000", "a😀a", "𐀀", "😀", "\uE000")).forEach { contexts ->
            assertParity(contexts.mapIndexed { index, context -> proposition("context-$index", "unicode-$context") })
        }
    }

    @Test
    fun sameContextIdTiesMatchResidentBeforeTopK() {
        listOf(listOf("\uE000", "😀"), listOf("z", "a", "aa", "a😀", "a\uE000", "a😀a", "𐀀", "😀", "\uE000")).forEach { ids ->
            assertParity(ids.map { proposition("id-$it", "unicode-ids") })
        }
    }

    private fun assertParity(fixtures: List<Proposition>) {
        val local = InMemoryPropositionRepository(embedder)
        fixtures.forEach { proposition ->
            repository.save(proposition)
            local.save(proposition)
            GraphDatabase.driver(Neo4jTestContainer.instance.boltUrl,
                AuthTokens.basic("neo4j", Neo4jTestContainer.PASSWORD)).use { driver ->
                driver.session().use { session ->
                    session.run("MATCH (p:Proposition {id: $" + "id}) SET p.embedding = $" + "vector",
                        mapOf("id" to proposition.id, "vector" to unit.toList())).consume()
                }
            }
        }
        val scope = ContextReadScope.ofValues(fixtures.map { it.contextId.value })
        val spec = ScopedReadSpec.at(at, 2.0)
        val (statement, parameters) = DrivineScopedVectorSearch.statement(scope, spec, request(100), budget, unit.toList())
        val rows = executor().read(Duration.ofSeconds(20)) { tx ->
            tx.run(statement, parameters).single()["results"].asList { value ->
                PropositionKey(value["contextId"].asString(), value["id"].asString()) to value["score"].asDouble()
            }
        }
        println("UNICODE_VECTOR_NATIVE_SCORES $rows")
        assertEquals(List(fixtures.size) { 1.0 }, rows.map { it.second })
        listOf(1, 2, 5, 100).forEach { k ->
            val expected = local.findSimilarWithScores(request(k), scope, spec, budget)
            val actual = native().findSimilarWithScores(request(k), scope, spec, budget)
            fun key(proposition: Proposition) = PropositionKey(proposition.contextId.value, proposition.id)
            assertEquals(expected.map { key(it.match) }, actual.map { key(it.match) }, "Unicode vector keys k=$k")
            assertEquals(expected.map { it.score }, actual.map { it.score }, "Unicode vector scores k=$k")
        }
        repository.clearAll()
    }
}
