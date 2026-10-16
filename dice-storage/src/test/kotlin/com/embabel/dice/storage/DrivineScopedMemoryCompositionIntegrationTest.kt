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
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.proposition.store.Neo4jRagPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
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
    private fun view(store: PropositionStore, grants: List<RowGrant>, limits: ScopedReadLimits = ScopedReadLimits.DEFAULT) =
        PolicyReadView.open(reader(store, limits), RowPolicy.of(stamp, grants))
    private fun grant(row: Proposition, text: String) =
        RowGrant.of(key(row), RowProjection.text(ApprovedText.Literal(text)), true, false)
    private fun plan(vararg tiers: PolicySearchTier) = PolicyMemoryPlan.listingOnly().withSearchTiers(tiers.toSet())
    private fun output(tool: Tool, input: String = "{}") = (tool.call(input) as Tool.Result.Text).content
    private fun lines(text: String) = text.lines().filter { it.startsWith("- ") }
    private fun dtos(text: String) = lines(text).map { mapper.readTree(it.removePrefix("- ")) }
    private fun textValues(text: String) = dtos(text).map { it.path("text").textValue() }

    private fun seed(row: Proposition, vector: FloatArray = unit) {
        vectors[row.text] = vector
        repository.save(row)
        GraphDatabase.driver(Neo4jTestContainer.instance.boltUrl,
            AuthTokens.basic("neo4j", Neo4jTestContainer.PASSWORD)).use { driver ->
            driver.session().use { session ->
                session.run("MATCH (p:Proposition {id: $" + "id}) SET p.embedding = $" + "vector",
                    mapOf("id" to row.id, "vector" to vector.toList())).consume()
            }
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

    @AfterEach
    fun clean() {
        try {
            before?.let { assertEquals(it, persistedAccess(), "Memory reads must not touch persisted access times") }
            verifyNoInteractions(entities, listener)
        } finally {
            repository.clearAll()
            vectors.clear()
        }
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
    }

    @Test
    fun strictListingReplacementKeywordsOmittedFieldsAndFreshRevocation() {
        seedFixture()
        wrappers().forEach { store ->
            val grants = listOf(grant(b, "approved mango B"), grant(a, "approved mango A"))
            val memory = PolicyMemory.from(view(store, grants), plan(PolicySearchTier.KEYWORD))
            val listed = output(memory)
            assertEquals(listOf("approved mango B", "approved mango A"), textValues(listed))
            dtos(listed).forEach { assertEquals(setOf("text", "entityHandles", "sources", "metadata"), it.fieldNames().asSequence().toSet()) }
            listOf("private", "composition-", "allowed-", "hidden", "forbidden", "confidence", "score", "principal").forEach {
                assertFalse(listed.contains(it), it)
            }
            assertTrue(memory.contribution().contains("2 memories available"))
            assertEquals(listOf("approved mango B"), textValues(output(memory, """{"query":"mango","limit":1}""")))
            assertEquals("No additional memories available.", output(memory, """{"query":"needle private alpha"}"""))
            val eager = memory.withEagerQuery(PropositionQuery(limit = 1))
            assertEquals(listOf("approved mango B"), textValues(eager.contribution()))
            assertEquals(listOf("approved mango A"), textValues(output(eager)))
            val revoked = PolicyMemory.from(view(store, listOf(grants.last())), plan(PolicySearchTier.KEYWORD))
            assertTrue(revoked.contribution().contains("1 memories available"))
            assertEquals(listOf("approved mango A"), textValues(output(revoked, """{"query":"mango"}""")))
            val denied = PolicyMemory.from(view(store, emptyList()), plan())
            assertTrue(denied.contribution().contains("0 memories available"))
            assertEquals("No additional memories available.", output(denied))
            val omitted = RowGrant.of(key(a), RowProjection.text(ApprovedText.Omitted), true, false)
            assertFalse(dtos(output(PolicyMemory.from(view(store, listOf(omitted)), plan()))).single().has("text"))
        }
    }

    @Test
    fun strictStoredVectorsAdmitOnlyGrantedRowsBeforeBudgetAndEagerSelection() {
        seedFixture()
        val fields = setOf(MemoryField.OWNER, MemoryField.ID, MemoryField.EFFECTIVE_CONFIDENCE)
        fun vectorGrant(row: Proposition, score: Boolean = false) = RowGrant.of(key(row),
            RowProjection.text(ApprovedText.Stored).withFields(if (score) fields + MemoryField.SCORE else fields), true, true)
        val limits = ScopedReadLimits(10, 401, 10, ExactVectorBudget(2, Duration.ofSeconds(5)))
        wrappers().forEach { store ->
            val memory = PolicyMemory.from(view(store, listOf(vectorGrant(a), vectorGrant(b)), limits), plan(PolicySearchTier.VECTOR))
            val result = dtos(output(memory, """{"query":"needle","limit":1}""")).single()
            assertEquals(b.contextId.value, result.path("owner").textValue())
            assertEquals(b.id, result.path("id").textValue())
            assertEquals(b.text, result.path("text").textValue())
            assertFalse(result.has("score"))
            assertFalse(result.has("confidence"))
            assertTrue(result.path("metadata").isEmpty)
            assertTrue(result.path("sources").isEmpty)
            val scored = PolicyMemory.from(view(store, listOf(vectorGrant(a, true), vectorGrant(b, true)), limits), plan(PolicySearchTier.VECTOR))
            assertEquals(1.0, dtos(output(scored, """{"query":"needle","limit":1}""")).single().path("score").doubleValue(), 1e-6)
            val eager = memory.withEagerTextSearch(TextSimilaritySearchRequest("needle", 0.0, 1))
                .withEagerQuery(PropositionQuery(contextId = b.contextId, limit = 1))
            assertEquals(listOf(b.text), textValues(eager.contribution()))
            assertTrue(eager.contribution().contains("2 memories available"))
            assertEquals(listOf(a.text), textValues(output(eager, """{"query":"needle"}""")))
            val revoked = PolicyMemory.from(view(store, listOf(vectorGrant(a)), limits), plan(PolicySearchTier.VECTOR))
            assertEquals(listOf(a.text), textValues(output(revoked, """{"query":"needle"}""")))
            assertTrue(revoked.contribution().contains("1 memories available"))
        }
    }

    @Test
    fun unicodeContextAndIdTiesMatchResidentOrderingAndToolOutput() {
        val fixtures = listOf(
            row("unicode-context-bmp", "composition-\uE000", "unicode context bmp", 0.8),
            row("unicode-context-supplementary", "composition-\uD83D\uDE00", "unicode context supplementary", 0.8),
            row("unicode-id-\uE000", "composition-A", "unicode id bmp", 0.8),
            row("unicode-id-\uD83D\uDE00", "composition-A", "unicode id supplementary", 0.8),
            row("unicode-forbidden", "composition-C", "unicode forbidden", 1.0),
        )
        fixtures.forEach { seed(it) }
        before = persistedAccess()
        val resident = InMemoryPropositionRepository(embedder).apply { saveAll(fixtures) }
        val scopes = listOf(ContextReadScope.ofValues(fixtures.take(2).map { it.contextId.value }), scope)
        scopes.forEach { tieScope ->
            fun observations(store: PropositionStore): Map<String, Any> {
                val bound = ScopedPropositionReader.open(store, tieScope)
                val session = bound.snapshot(at, 2.0)
                val memory = ScopedMemory.from(bound).withMinConfidence(0.0)
                return linkedMapOf(
                    "query" to session.query(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 1).map(::key),
                    "keyword" to session.keywordOverlap(listOf("unicode"), 1).map(::key),
                    "vector" to session.findSimilarWithScores(TextSimilaritySearchRequest("unicode", 0.0, 1)).map { key(it.match) },
                    "tool listing" to output(memory, """{"limit":1}"""),
                    "tool search" to output(memory, """{"query":"unicode","limit":1}"""),
                )
            }
            val expected = observations(resident)
            wrappers().forEach { store ->
                assertEquals(expected, observations(store), "Unicode tie parity for ${tieScope.contextIdValues}")
            }
        }
    }

    @Test
    fun provenanceAuthorityAndUnsupportedDependenciesFailWithoutPartialPublication() {
        seedFixture()
        wrappers().forEach { store ->
            val requestsSeen = mutableListOf<List<PolicyProvenanceRequest>>()
            val source = "approved-source-a"
            val projection = RowProjection.text(ApprovedText.Literal("approved summary"))
                .withSources(listOf(AllowedSource(source, true, false)))
            val granted = RowGrant.of(key(a), projection, true, false)
            val policyView = view(store, listOf(granted))
            var mode = 0
            val memory = PolicyMemory.from(policyView, plan()).withEagerQuery(PropositionQuery(limit = 1))
                .withProvenance { actualStamp, requests ->
                    assertEquals(stamp, actualStamp)
                    requestsSeen += requests.toList()
                    when (mode) {
                        0 -> mapOf(PolicySourceKey(PropositionKey("composition-C", "forbidden-c-0"), source) to ProjectedSource("forbidden citation", null))
                        1 -> mapOf(PolicySourceKey(key(a), "ungranted-source") to ProjectedSource("private citation", null))
                        2 -> requests.associate { it.key to ProjectedSource("approved citation", "private locator") }
                        3 -> error("private resolver detail")
                        else -> requests.associate { it.key to ProjectedSource("approved citation", null) }
                    }
                }
            repeat(4) {
                mode = it
                var published: String? = null
                val failure = assertFailsWith<ScopedReadException> { published = memory.contribution() }
                assertNull(published)
                assertNull(failure.cause)
                assertEquals(if (it == 3) "SCOPED_READ_FAILED" else "SCOPED_READ_RESULT_INVALID", failure.code)
                assertFalse(failure.toString().contains("private"))
                assertFalse(failure.toString().contains("composition-"))
            }
            mode = 4
            assertEquals(listOf("approved summary"), textValues(output(memory)))
            val contribution = memory.contribution()
            assertTrue(contribution.contains("approved citation"))
            assertFalse(contribution.contains("private"))
            assertFalse(contribution.contains(source))
            assertEquals("No additional memories available.", output(memory))
            assertTrue(requestsSeen.all { it == listOf(PolicyProvenanceRequest(PolicySourceKey(key(a), source), true, false)) })

            val scoped = ScopedMemory.from(reader(store)).withMinConfidence(0.0)
            listOf("{", "[]", "{} {}", """{"contextId":"composition-C"}""", """{"scope":["composition-C"]}""",
                """{"principal":"attacker"}""", """{"policy":"changed"}""", """{"grants":[]}""", """{"limit":0}""").forEach { input ->
                listOf<Tool>(memory, scoped).forEach { tool ->
                    assertEquals("INVALID_SCOPED_READ", assertFailsWith<ScopedReadException> { output(tool, input) }.code)
                }
            }
            assertEquals(setOf("query", "limit"), memory.definition.inputSchema.parameters.map { it.name }.toSet())
            listOf(PolicySearchTier.VECTOR, PolicySearchTier.RELATED).forEach { tier ->
                val failure = assertFailsWith<ScopedReadException> { PolicyMemory.from(policyView, plan(tier)) }
                assertEquals("INVALID_SCOPED_READ", failure.code)
                assertNull(failure.cause)
            }
            assertEquals("SCOPED_READ_UNSUPPORTED", assertFailsWith<ScopedReadException> {
                output(PolicyMemory.from(policyView, plan()), """{"query":"summary"}""")
            }.code)
            assertEquals("SCOPED_READ_UNSUPPORTED", assertFailsWith<ScopedReadException> {
                PolicyMemory.from(policyView, plan()).withEagerQuery(PropositionQuery(orderBy = PropositionQuery.OrderBy.CREATED_DESC))
            }.code)
            val unsupported = DrivinePropositionRepository(graph, manager, embedder, transactionManager)
            val failure = assertFailsWith<ScopedReadException> {
                PolicyMemory.from(view(EventEmittingPropositionRepository(unsupported, listener), listOf(granted)), plan())
            }
            assertEquals("SCOPED_READ_UNSUPPORTED", failure.code)
            assertNull(failure.cause)
        }
    }
}
