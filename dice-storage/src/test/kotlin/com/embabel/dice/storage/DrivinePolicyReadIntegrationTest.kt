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
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import kotlin.test.*

@SpringBootTest(classes = [TestApplication::class])
class DrivinePolicyReadIntegrationTest {
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
    private val scope = ContextReadScope.ofValues(listOf("policy-B", "policy-A", "policy-A"))
    private val timeout = Duration.ofSeconds(5)
    private fun executor() = Neo4jScopedReadExecutor.from(manager, registry, sources)
    private fun manual() = DrivinePropositionRepository(graph, manager, FakeEmbeddingService(), transactionManager)
    private fun native() = manual().withNativeReadExecutor(executor())
    private fun spec() = ScopedReadSpec.of(scope)
    private val clock = ReadClock(at, 0.7)
    private fun row(id: String, context: String = "policy-A", text: String = "alpha beta $id") = Proposition(
        id = id, contextId = ContextId(context), text = text, confidence = 0.8, mentions = emptyList(),
        created = at, contentRevised = at, metadataRevised = at, lastAccessed = at,
    )
    private fun key(p: Proposition) = PropositionKey(p.contextId.value, p.id)
    private fun grant(p: Proposition, text: ApprovedText = ApprovedText.Stored) =
        StrictReadRow(key(p), text)
    private fun seed(rows: List<Proposition>) = rows.forEach { repository.save(it) }
    @AfterEach fun clean() { repository.clearAll() }

    @Test
    fun `strict listing and approved keywords match the in-memory store`() {
        val a = row("a", text = "private alpha beta secret").copy(confidence = 1.0)
        val b = row("b", "policy-B", "ALPHA BETA").copy(confidence = 0.1)
        val omitted = row("omitted", "policy-B", "alpha beta secret")
        seed(listOf(a, b, omitted) + List(12) { row("hidden-$it", "policy-B") } +
            List(12) { row("forbidden-$it", "policy-C") })
        val local = InMemoryPropositionRepository()
        local.saveAll(listOf(a, b, omitted))
        val grants = listOf(grant(omitted, ApprovedText.Omitted), grant(b),
            grant(a, ApprovedText.Literal("beta")))
        for (limit in listOf(0, 1, 2, 100)) {
            assertEquals(local.queryByGrantOrder(spec(), clock, grants, limit, timeout),
                native().queryByGrantOrder(spec(), clock, grants, limit, timeout))
            for (tokens in listOf(listOf(" ALPHA ", "alpha", "BETA", ""), listOf("secret"), listOf("beta"))) {
                assertEquals(local.keywordByApprovedText(spec(), clock, grants, tokens, limit, timeout),
                    native().keywordByApprovedText(spec(), clock, grants, tokens, limit, timeout))
            }
        }
        assertEquals(listOf(b), native().keywordByApprovedText(spec(), clock, grants, listOf("alpha"), 1, timeout))
        assertEquals(listOf(omitted), native().queryByGrantOrder(spec(), clock, grants, 1, timeout))
    }

    @Test
    fun `an approved replacement never scores the original text`() {
        val a = row("a", text = "hidden alpha beta secret").copy(confidence = 1.0)
        val b = row("b", "policy-B", "nothing").copy(confidence = 0.1)
        seed(listOf(a, b, row("hidden", "policy-B"), row("forbidden", "policy-C")))
        val grants = listOf(grant(b, ApprovedText.Literal("alpha beta")),
            grant(a, ApprovedText.Literal("beta")))
        fun ranks() = listOf(
            native().queryByGrantOrder(spec(), clock, grants, 100, timeout).map(::key),
            native().keywordByApprovedText(spec(), clock, grants, listOf("alpha", "beta"), 100, timeout).map(::key),
        )
        val before = ranks()
        assertEquals(listOf(key(b), key(a)), before[1])
        assertTrue(native().keywordByApprovedText(spec(), clock, grants, listOf("secret"), 1, timeout).isEmpty())
        repository.save(a.copy(text = "beta", confidence = 0.0, decay = 1.0, created = Instant.EPOCH,
            importance = 0.0, reinforceCount = 0, sourceIds = listOf("private-source")))
        repository.save(b.copy(text = "secret secret", confidence = 1.0, importance = 1.0, reinforceCount = 100))
        assertEquals(before, ranks())
    }

    @Test
    fun `grant membership keeps every conjunct before the limit`() {
        val a = row("a")
        val allowed = row("allowed", "policy-B").copy(importance = 0.9, pinned = true)
        val excluded = row("excluded", "policy-B").copy(importance = 0.9, pinned = true)
        val low = row("low", "policy-B").copy(importance = 0.1, pinned = true)
        val unpinned = row("unpinned", "policy-B").copy(importance = 0.9)
        val notIncluded = row("not-included", "policy-B").copy(importance = 0.9, pinned = true)
        val fixtures = listOf(a, allowed, excluded, low, unpinned, notIncluded)
        seed(fixtures + row("forbidden", "policy-C"))
        val local = InMemoryPropositionRepository().apply { saveAll(fixtures) }
        val grants = fixtures.map { grant(it) }
        val restricted = spec().includingOnly(fixtures.filter { it != notIncluded }.map(::key))
            .excluding(listOf(key(excluded)))
            .restrict(PropositionQuery(contextId = ContextId("policy-B"), pinned = true))
            .restrict(PropositionQuery(minImportance = 0.7))
        val criteria = listOf(restricted, restricted.includingOnly(emptyList()),
            restricted.restrict(PropositionQuery(contextId = ContextId("policy-A")))) +
            listOf(PropositionQuery(statuses = setOf(PropositionStatus.STALE)), PropositionQuery(minConfidence = 0.9),
                PropositionQuery(minEffectiveConfidence = 0.9), PropositionQuery(belowEffectiveConfidence = 0.9),
                PropositionQuery(createdAfter = at.plusNanos(1)), PropositionQuery(revisedBefore = at),
                PropositionQuery(accessedBefore = at), PropositionQuery(minLevel = 1), PropositionQuery(maxLevel = 0),
                PropositionQuery(entityId = "absent"), PropositionQuery(anyEntityIds = emptyList()),
                PropositionQuery(allEntityIds = emptyList()), PropositionQuery(minTrustScore = 0.9),
                PropositionQuery(minReinforceCount = 1)).map { spec().restrict(it) }
        criteria.forEach { filter ->
            assertEquals(local.queryByGrantOrder(filter, clock, grants, 1, timeout),
                native().queryByGrantOrder(filter, clock, grants, 1, timeout))
            assertEquals(local.keywordByApprovedText(filter, clock, grants, listOf("alpha"), 1, timeout),
                native().keywordByApprovedText(filter, clock, grants, listOf("alpha"), 1, timeout))
        }
        assertEquals(listOf(allowed), native().queryByGrantOrder(restricted, clock, grants, 1, timeout))
    }

    @Test
    fun `ordinary reads and vectors intersect grants before admission`() {
        val a = row("a")
        val b = row("b", "policy-B")
        seed(listOf(a, b) + List(8) { row("hidden-$it", "policy-B") } + List(8) { row("forbidden-$it", "policy-C") })
        val before = repository.findAll().associateBy { it.id }
        val session = ScopedPropositionReader.open(native(), scope).snapshot(at, 0.7)
            .includingOnly(listOf(key(a), key(b))).excluding(listOf(key(a)))
        assertEquals(1L, session.count())
        assertEquals(listOf(b), session.query(PropositionQuery.OrderBy.NONE, 1))
        assertTrue(session.includingOnly(listOf(key(a))).query(PropositionQuery.OrderBy.NONE, 1).isEmpty())
        assertTrue(session.includingOnly(listOf(key(row("hidden-0", "policy-B")))).query(PropositionQuery.OrderBy.NONE, 1).isEmpty())
        assertEquals(0L, session.includingOnly(emptyList()).count())
        val matches = native().findSimilarWithScores(TextSimilaritySearchRequest(query = b.text, topK = 1, similarityThreshold = 0.0),
            spec().includingOnly(listOf(key(b))), clock, ExactVectorBudget(1, timeout))
        assertEquals(listOf(b), matches.map { it.match })
        assertEquals(before, repository.findAll().associateBy { it.id })
        assertTrue(native().queryByGrantOrder(spec(), clock, listOf(StrictReadRow(PropositionKey("policy-A", b.id),
            ApprovedText.Stored)), 1, timeout).isEmpty())
    }

    @Test
    fun `invalid strict requests and a store without an executor fail closed`() {
        val a = row("a")
        seed(listOf(a))
        val grants = listOf(grant(a))
        val reads = native()
        for (invalid in listOf(grants + grants,
            grants + grant(row("c", "policy-C")),
            List(1001) { StrictReadRow(PropositionKey("policy-A", "row-$it"), ApprovedText.Omitted) })) {
            assertFailsWith<IllegalArgumentException> { reads.queryByGrantOrder(spec(), clock, invalid, 1, timeout) }
            assertFailsWith<IllegalArgumentException> { reads.keywordByApprovedText(spec(), clock, invalid, listOf("alpha"), 1, timeout) }
        }
        for (duration in listOf(Duration.ZERO, Duration.ofNanos(-1), timeout.plusNanos(1))) {
            assertFailsWith<IllegalArgumentException> { reads.queryByGrantOrder(spec(), clock, grants, 1, duration) }
            assertFailsWith<IllegalArgumentException> { reads.keywordByApprovedText(spec(), clock, grants, listOf("alpha"), 1, duration) }
        }
        for (limit in listOf(-1, 1001)) {
            assertFailsWith<IllegalArgumentException> { reads.queryByGrantOrder(spec(), clock, grants, limit, timeout) }
            assertFailsWith<IllegalArgumentException> { reads.keywordByApprovedText(spec(), clock, grants, listOf("alpha"), limit, timeout) }
        }
        assertFalse(manual().supportsStrictPolicyReads)
        assertEquals(ScopedReadFailure.SCOPED_READ_UNSUPPORTED, assertFailsWith<ScopedReadException> {
            manual().queryByGrantOrder(spec(), clock, grants, 1, timeout)
        }.failure)
        assertEquals(ScopedReadFailure.SCOPED_READ_UNSUPPORTED, assertFailsWith<ScopedReadException> {
            manual().keywordByApprovedText(spec(), clock, grants, listOf("alpha"), 1, timeout)
        }.failure)
        assertTrue(reads.withExactVectorBudget(ExactVectorBudget.DEFAULT).supportsStrictPolicyReads)
        assertTrue(reads.queryByGrantOrder(spec(), clock, emptyList(), 1, Duration.ofNanos(1)).isEmpty())
        assertTrue(reads.keywordByApprovedText(spec(), clock, emptyList(), listOf("alpha"), 1, Duration.ofNanos(1)).isEmpty())
        assertTrue(reads.keywordByApprovedText(spec(), clock, grants, listOf(" "), 1, timeout).isEmpty())
        assertTrue(reads.keywordByApprovedText(spec(), clock, listOf(grant(a, ApprovedText.Omitted)), listOf("alpha"), 1, timeout).isEmpty())
        assertEquals(listOf(a), reads.queryByGrantOrder(spec(), clock, grants, 1, timeout))
    }

    @Test
    fun `a replacement statement scores only approved text`() {
        val a = row("a", text = "private hidden secret")
        seed(listOf(a))
        val literal = "alpha ' quoted $" + "injection \n beta"
        val grants = listOf(grant(a, ApprovedText.Literal(literal)))
        val (statement, parameters) = DrivinePolicyReadOperations.keywordStatement(ScopedReadSpec.of(scope), ReadClock(at, 0.7), grants, listOf("alpha"), 1)
        assertFalse(statement.contains("p.text"))
        assertFalse(statement.contains("confidence"))
        assertFalse(statement.contains(literal))
        assertEquals(literal, ((parameters.getValue("literalRows") as List<*>).single() as Map<*, *>)["text"])
        assertEquals(listOf(a), native().keywordByApprovedText(spec(), clock, grants, listOf("alpha"), 1, timeout))
    }
}
