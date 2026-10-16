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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.agent.*
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.Neo4jRagPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import kotlin.test.*

@SpringBootTest(classes = [TestApplication::class])
class DrivineScopedMemoryCompositionIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)

        // One driver for the whole class, used only to write embeddings the repository doesn't save.
        private val driver by lazy {
            GraphDatabase.driver(Neo4jTestContainer.instance.boltUrl, AuthTokens.basic("neo4j", Neo4jTestContainer.PASSWORD))
        }

        @JvmStatic
        @AfterAll
        fun closeDriver() = driver.close()
    }

    @Autowired private lateinit var repository: DrivinePropositionRepository
    @Autowired private lateinit var manager: PersistenceManager
    @Autowired private lateinit var graph: StatelessGraphObjectManager
    @Autowired private lateinit var registry: DatabaseRegistry
    @Autowired private lateinit var sources: DataSourceMap
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val at = Instant.parse("2026-10-15T12:00:00.000000123Z")
    private val scope = ContextReadScope.ofValues(listOf("composition-B", "composition-A", "composition-A"))
    private val stamp = ReadPolicyStamp("trusted-principal", "recall", "composition", "v1")
    private val entities = mock(NamedEntityDataRepository::class.java)
    private val listener = mock(DiceEventListener::class.java)
    private val unit = FloatArray(16) { if (it == 0) 1f else 0f }
    private val diagonal = FloatArray(16) { if (it < 2) 1f else 0f }
    private val vectors = mutableMapOf<String, FloatArray>()
    private val embedder = object : EmbeddingService by FakeEmbeddingService() {
        override fun embed(text: String): FloatArray = vectors[text] ?: unit
    }
    private val mapper = jacksonObjectMapper()
    private var before: Map<PropositionKey, Instant?>? = null
    private val a get() = row("allowed-a", "composition-A", "private alpha A", 0.9)
    private val b get() = row("allowed-b", "composition-B", "private beta B", 0.2)

    private fun row(id: String, owner: String, text: String, confidence: Double = 1.0) = Proposition(
        id = id, contextId = ContextId(owner), text = text, confidence = confidence,
        created = at, contentRevised = at, metadataRevised = at, lastAccessed = at,
        mentions = listOf(EntityMention("private mention", "Person", "private-entity")),
        metadata = mapOf("private-metadata" to "private-value"),
        provenanceEntries = listOf(ProvenanceEntry(UriLocator("private-source-$id", "private citation $id"))),
    )
    private fun key(row: Proposition) = PropositionKey(row.contextId.value, row.id)
    private fun persistedAccess() = repository.findAll().associate { key(it) to it.lastAccessed }
    private fun native() = DrivinePropositionRepository(graph, manager, embedder, transactionManager)
        .withNativeReadExecutor(Neo4jScopedReadExecutor.from(manager, registry, sources))
    private fun wrappers(): List<PropositionStore> {
        val native = native()
        val direct = EventEmittingPropositionRepository(native, listener)
        val nested = EventEmittingPropositionRepository.wrapping(direct, listener)
        return listOf(direct, nested, Neo4jRagPropositionRepository(native, entities),
            Neo4jRagPropositionRepository(nested, entities))
    }
    private fun reader(store: PropositionStore, limits: ScopedReadLimits = ScopedReadLimits.DEFAULT) =
        ScopedPropositionReader.open(store, scope, limits)
    private fun view(store: PropositionStore, grants: List<RowGrant>, limits: ScopedReadLimits = ScopedReadLimits.DEFAULT, memoryLimits: MemoryLimits = MemoryLimits.DEFAULT) =
        PolicyReadView.open(reader(store, limits), RowPolicy.of(stamp, grants), memoryLimits)
    private fun grant(row: Proposition, text: String) =
        RowGrant.of(key(row), RowProjection.text(ApprovedText.Literal(text)), false)
    private fun plan(vararg tiers: PolicySearchTier) = tiers.toSet()
    private fun output(tool: Tool, input: String = "{}") = (tool.call(input) as Tool.Result.Text).content
    private fun lines(text: String) = text.lines().filter { it.startsWith("- ") }
    private fun dtos(text: String) = lines(text).map { mapper.readTree(it.removePrefix("- ")) }
    private fun textValues(text: String) = dtos(text).map { it.path("text").textValue() }

    private fun seed(row: Proposition, vector: FloatArray = unit) {
        vectors[row.text] = vector
        repository.save(row)
        driver.session().use { session ->
            session.run("""MATCH (p:Proposition {id: ${'$'}id}) SET p.embedding = ${'$'}vector""",
                mapOf("id" to row.id, "vector" to vector.toList())).consume()
        }
    }

    private fun seedFixture() {
        seed(a, diagonal)
        seed(b)
        repeat(40) {
            seed(row("hidden-b-$it", "composition-B", "hidden strong needle mango $it"))
            seed(row("forbidden-c-$it", "composition-C", "forbidden strong needle mango $it"))
        }
        before = persistedAccess()
    }

    // Memory reads must leave persisted access times alone and never reach the entity repository or the event listener.
    private fun assertNoTouchesOrEvents() {
        assertEquals(checkNotNull(before) { "seedFixture() records the access times first" }, persistedAccess(),
            "Memory reads must not touch persisted access times")
        verifyNoInteractions(entities, listener)
    }

    @AfterEach
    fun clean() {
        repository.clearAll()
        vectors.clear()
    }

    @Test
    fun emptyThenScopedUnionSearchAndEagerDedupThroughStandardWrappers() {
        wrappers().forEach { store ->
            val scoped = ScopedMemory.from(reader(store)).withMinConfidence(0.0)
            assertTrue(scoped.contribution().contains("0 memories available"))
            assertEquals("No additional memories available.", output(scoped))
            val policy = PolicyMemory.from(view(store, emptyList()), plan(PolicySearchTier.KEYWORD))
            assertTrue(policy.contribution().contains("0 memories available"))
            assertEquals("No additional memories available.", output(policy, """{"query":"needle"}"""))
        }
        seedFixture()
        wrappers().forEach { store ->
            val requested = mutableListOf<List<PropositionKey>>()
            val scoped = ScopedMemory.from(reader(store)).withMinConfidence(0.0).withProvenance { keys ->
                requested += keys.toList()
                keys.associateWith { listOf("approved scoped citation") }
            }
            val listed = output(scoped, """{"limit":100}""")
            assertEquals(42, lines(listed).size)
            assertTrue(listed.contains("[composition-A/allowed-a]"))
            assertTrue(listed.contains("[composition-B/allowed-b]"))
            assertTrue(listed.contains("[composition-B/hidden-b-0]"))
            assertFalse(listed.contains("composition-C"))
            assertTrue(scoped.contribution().contains("42 memories available"))
            assertEquals(1, lines(output(scoped, """{"limit":1}""")).size)
            val searched = output(scoped, """{"query":"needle","limit":2}""")
            assertEquals(2, lines(searched).size)
            assertFalse(searched.contains("forbidden"))
            assertEquals(setOf(key(a), key(b)) + (0..39).map { PropositionKey("composition-B", "hidden-b-$it") },
                requested.first().toSet())
            assertTrue(requested.flatten().all { it.contextIdValue in scope.contextIdValues })

            val eager = scoped.withEagerQuery { it.copy(contextId = a.contextId, limit = 1) }
                .withEagerTextSearch(TextSimilaritySearchRequest(a.text, 0.0, 1))
            val contribution = eager.contribution()
            assertEquals(1, lines(contribution).size)
            assertTrue(contribution.contains("[composition-A/allowed-a]"))
            assertEquals(contribution, eager.contribution())
            val remaining = output(eager, """{"limit":100}""")
            assertEquals(41, lines(remaining).size)
            assertFalse(remaining.contains("[composition-A/allowed-a]"))
        }
        assertNoTouchesOrEvents()
    }

    @Test
    fun strictListingReplacementKeywordsOmittedFieldsAndFreshRevocation() {
        seedFixture()
        wrappers().forEach { store ->
            val grants = listOf(grant(b, "approved mango B"), grant(a, "approved mango A"))
            val policyView = view(store, grants)
            val memory = PolicyMemory.from(policyView, plan(PolicySearchTier.KEYWORD))
            val listed = output(memory)
            assertEquals(listOf("approved mango B", "approved mango A"), textValues(listed))
            dtos(listed).forEach { assertEquals(setOf("text", "entityHandles", "sources", "metadata"), it.fieldNames().asSequence().toSet()) }
            listOf("private", "composition-", "allowed-", "hidden", "forbidden", "confidence", "score", "principal").forEach {
                assertFalse(listed.contains(it), it)
            }
            assertTrue(memory.contribution().contains("2 memories available"))
            assertEquals(listOf("approved mango B"), textValues(output(memory, """{"query":"mango","limit":1}""")))
            assertEquals("No additional memories available.", output(memory, """{"query":"needle private alpha"}"""))
            val eager = PolicyMemory.configuring(policyView).withSearchTiers(plan(PolicySearchTier.KEYWORD))
                .withEagerQuery(PropositionQuery(limit = 1)).memory()
            assertEquals(listOf("approved mango B"), textValues(eager.contribution()))
            assertEquals(listOf("approved mango A"), textValues(output(eager)))
            val revoked = PolicyMemory.from(view(store, listOf(grants.last())), plan(PolicySearchTier.KEYWORD))
            assertTrue(revoked.contribution().contains("1 memories available"))
            assertEquals(listOf("approved mango A"), textValues(output(revoked, """{"query":"mango"}""")))
            val denied = PolicyMemory.from(view(store, emptyList()), plan())
            assertTrue(denied.contribution().contains("0 memories available"))
            assertEquals("No additional memories available.", output(denied))
            val omitted = RowGrant.of(key(a), RowProjection.text(ApprovedText.Omitted), false)
            assertFalse(dtos(output(PolicyMemory.from(view(store, listOf(omitted)), plan()))).single().has("text"))
        }
        assertNoTouchesOrEvents()
    }

    @Test
    fun strictStoredVectorsAdmitOnlyGrantedRowsBeforeBudgetAndEagerSelection() {
        seedFixture()
        val fields = setOf(MemoryField.OWNER, MemoryField.ID, MemoryField.EFFECTIVE_CONFIDENCE)
        fun vectorGrant(row: Proposition, score: Boolean = false) = RowGrant.of(key(row),
            RowProjection.text(ApprovedText.Stored).withFields(if (score) fields + MemoryField.SCORE else fields), true)
        val limits = ScopedReadLimits(401, ExactVectorBudget(2, Duration.ofSeconds(5)))
        val memoryLimits = MemoryLimits(10, 10)
        wrappers().forEach { store ->
            val policyView = view(store, listOf(vectorGrant(a), vectorGrant(b)), limits, memoryLimits)
            val memory = PolicyMemory.from(policyView, plan(PolicySearchTier.VECTOR))
            val result = dtos(output(memory, """{"query":"needle","limit":1}""")).single()
            assertEquals(b.contextId.value, result.path("owner").textValue())
            assertEquals(b.id, result.path("id").textValue())
            assertEquals(b.text, result.path("text").textValue())
            assertFalse(result.has("score"))
            assertFalse(result.has("confidence"))
            assertTrue(result.path("metadata").isEmpty)
            assertTrue(result.path("sources").isEmpty)
            val scored = PolicyMemory.from(view(store, listOf(vectorGrant(a, true), vectorGrant(b, true)), limits, memoryLimits), plan(PolicySearchTier.VECTOR))
            assertEquals(1.0, dtos(output(scored, """{"query":"needle","limit":1}""")).single().path("score").doubleValue(), 1e-6)
            val eager = PolicyMemory.configuring(policyView).withSearchTiers(plan(PolicySearchTier.VECTOR))
                .withEagerTextSearch(TextSimilaritySearchRequest("needle", 0.0, 1))
                .withEagerQuery(PropositionQuery(contextId = b.contextId, limit = 1)).memory()
            assertEquals(listOf(b.text), textValues(eager.contribution()))
            assertTrue(eager.contribution().contains("2 memories available"))
            assertEquals(listOf(a.text), textValues(output(eager, """{"query":"needle"}""")))
            val revoked = PolicyMemory.from(view(store, listOf(vectorGrant(a)), limits, memoryLimits), plan(PolicySearchTier.VECTOR))
            assertEquals(listOf(a.text), textValues(output(revoked, """{"query":"needle"}""")))
            assertTrue(revoked.contribution().contains("1 memories available"))
        }
        assertNoTouchesOrEvents()
    }
}
