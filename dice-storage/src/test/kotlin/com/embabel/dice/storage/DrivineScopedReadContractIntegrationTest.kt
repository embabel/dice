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
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.temporal.TemporalMetadata
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.Mockito.mock
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises production native selection and hydration, rather than just the predicate compiler. */
@SpringBootTest(classes = [TestApplication::class])
class DrivineScopedReadContractIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired private lateinit var repository: DrivinePropositionRepository
    @Autowired private lateinit var manager: PersistenceManager
    @Autowired private lateinit var registry: DatabaseRegistry
    @Autowired private lateinit var sources: DataSourceMap

    private val at = Instant.parse("2026-10-15T12:00:00.000000123Z")
    private val scope = ContextReadScope.ofValues(listOf("native-A", "native-B", "native-A"))
    private fun executor() = Neo4jScopedReadExecutor.from(manager, registry, sources)
    private fun spec() = ScopedReadSpec.at(at, 2.0)
    private fun keys(rows: List<Proposition>) = rows.map { PropositionKey(it.contextId.value, it.id) }

    @AfterEach
    fun clean() {
        repository.clearAll()
    }

    private fun fixtures(): List<Proposition> {
        val first = Proposition(
            id = "a", contextId = ContextId("native-A"), text = "ALPHA beta facts",
            confidence = 0.9, decay = 0.1, importance = 0.6, level = 2, reinforceCount = 3,
            created = at.minusSeconds(864000), contentRevised = at.minusSeconds(172800),
            metadataRevised = at.minusSeconds(259200), lastAccessed = at.minusSeconds(86400),
            mentions = listOf(EntityMention("one", "fixture", "entity-1"), EntityMention("two", "fixture", "entity-2")),
            grounding = listOf("ground"), sourceIds = listOf("source"), uri = "urn:fixture:a", reasoning = "fixture reason",
            metadata = mapOf("dice.trust.score" to 0.8, "label" to "kept", "count" to 4L),
        )
        return listOf(
            first,
            first.copy(id = "b", contextId = ContextId("native-B"), text = "alpha beta B", pinned = true),
            first.copy(id = "c", contextId = ContextId("native-C"), text = "alpha beta secret", confidence = 1.0,
                decay = 0.0, importance = 1.0, reinforceCount = 100),
            first.copy(id = "old", text = "alpha decaying", confidence = 1.0, decay = 0.9, level = 0),
            first.copy(id = "fresh", text = "beta fresh", confidence = 0.8, lastAccessed = at, reinforceCount = 4),
            first.copy(id = "stale", text = "alpha stale", status = PropositionStatus.STALE,
                mentions = emptyList(), metadata = mapOf("dice.trust.score" to 0.1)),
            first.copy(id = "window", text = "beta window", temporal = TemporalMetadata(
                observedAt = at.minusNanos(1), validFrom = at.minusSeconds(86400), validTo = at.plusNanos(1),
                supersedes = listOf("old"), contradicts = listOf("fresh"))),
            first.copy(id = "end-only", text = "alpha validTo only", temporal = TemporalMetadata(validTo = at.minusSeconds(1))),
            first.copy(id = "invalidated", text = "alpha invalidated", temporal = TemporalMetadata(invalidatedAt = at)),
            first.copy(id = "open", text = "alpha open", temporal = TemporalMetadata(validFrom = at.minusSeconds(172800))),
            first.copy(id = "edge", text = "beta day boundary", lastAccessed = at.minusSeconds(86400).plusNanos(1)),
        )
    }

    private fun seed(): InMemoryPropositionRepository {
        clean()
        val local = InMemoryPropositionRepository()
        fixtures().forEach { repository.save(it); local.save(it) }
        return local
    }

    @Test
    fun `manual construction remains unsupported and wither leaves receiver unchanged`() {
        seed()
        val manual = DrivinePropositionRepository(
            mock(StatelessGraphObjectManager::class.java), mock(PersistenceManager::class.java),
            mock(EmbeddingService::class.java), mock(PlatformTransactionManager::class.java),
        )
        val configured = manual.withNativeReadExecutor(executor())
        assertFalse(manual.supportsScopedReads)
        assertTrue(configured.supportsScopedReads)
        assertEquals(10L, configured.count(scope, spec()))
        assertEquals(listOf("a"), configured.query(scope, spec(), ReadOrder.NONE, 1).map { it.id })
        assertEquals(listOf("a"), configured.keywordOverlap(scope, spec(), listOf("alpha", "beta"), 1).map { it.id })
        assertEquals("SCOPED_READ_UNSUPPORTED", assertFailsWith<ScopedReadException> {
            manual.count(scope, spec())
        }.code)
    }

    @TestFactory
    fun `every filter and conjunction matches portable oracle`(): List<DynamicTest> {
        val p = fixtures().first()
        val filters = linkedMapOf(
            "none" to PropositionQuery(), "context" to PropositionQuery(contextId = p.contextId),
            "entity" to PropositionQuery(entityId = "entity-1"),
            "any" to PropositionQuery(anyEntityIds = listOf("missing", "entity-2")),
            "all" to PropositionQuery(allEntityIds = listOf("entity-1", "entity-2", "entity-1")),
            "empty any" to PropositionQuery(anyEntityIds = emptyList()), "empty all" to PropositionQuery(allEntityIds = emptyList()),
            "status" to PropositionQuery(statuses = setOf(PropositionStatus.ACTIVE)),
            "empty statuses" to PropositionQuery(statuses = emptySet()),
            "level lower" to PropositionQuery(minLevel = 2), "level upper" to PropositionQuery(maxLevel = 0),
            "created lower" to PropositionQuery(createdAfter = p.created),
            "created upper" to PropositionQuery(createdBefore = p.created.minusNanos(1)),
            "created inclusive upper" to PropositionQuery(createdBefore = p.created),
            "revised lower" to PropositionQuery(revisedAfter = p.lastTouched),
            "revised upper" to PropositionQuery(revisedBefore = p.lastTouched.minusNanos(1)),
            "revised inclusive upper" to PropositionQuery(revisedBefore = p.lastTouched),
            "accessed lower" to PropositionQuery(accessedAfter = p.lastAccessed.plusNanos(1)),
            "accessed upper" to PropositionQuery(accessedBefore = p.lastAccessed),
            "confidence" to PropositionQuery(minConfidence = 0.9),
            "importance" to PropositionQuery(minImportance = 0.7),
            "reinforcement" to PropositionQuery(minReinforceCount = 4), "pin" to PropositionQuery(pinned = true),
            "trust" to PropositionQuery(minTrustScore = 0.8),
            "effective lower" to PropositionQuery(minEffectiveConfidence = 0.75),
            "effective upper" to PropositionQuery(belowEffectiveConfidence = 0.75),
            "custom decay" to PropositionQuery(minEffectiveConfidence = 0.85, decayK = 0.5),
            "nonfinite decay" to PropositionQuery(minEffectiveConfidence = 0.5, decayK = Double.NaN),
            "nan confidence" to PropositionQuery(minConfidence = Double.NaN),
            "nan effective" to PropositionQuery(belowEffectiveConfidence = Double.NaN),
            "nan importance" to PropositionQuery(minImportance = Double.NaN),
            "nan trust" to PropositionQuery(minTrustScore = Double.NaN),
            "combined" to PropositionQuery(entityId = "entity-1", statuses = setOf(PropositionStatus.ACTIVE),
                minLevel = 2, minConfidence = 0.8, minImportance = 0.5, minTrustScore = 0.8, pinned = false),
        )
        val local by lazy { seed() }
        return filters.map { (name, query) -> DynamicTest.dynamicTest(name) {
            val criteria = spec().restrict(scope, query)
            assertEquals(local.count(scope, criteria), repository.count(scope, criteria), name)
            ReadOrder.entries.forEach { order -> listOf(1, 3, 100).forEach { limit ->
                assertEquals(keys(local.query(scope, criteria, order, limit)),
                    keys(repository.query(scope, criteria, order, limit)), "$name order=$order limit=$limit")
            } }
            val tokens = listOf(" alpha ", "BETA", "alpha", "", "   ")
            assertEquals(keys(local.keywordOverlap(scope, criteria, tokens, 3)),
                keys(repository.keywordOverlap(scope, criteria, tokens, 3)), name)
            val allOf = criteria.restrict(scope, PropositionQuery(entityId = "missing"))
            assertEquals(0L, repository.count(scope, allOf))
        } }
    }

    @Test
    fun `key grants empty intersections and hostile values cannot broaden reads`() {
        val local = seed()
        val allowed = listOf(PropositionKey("native-A", "a"), PropositionKey("native-B", "b"))
        val criteria = spec().includingOnly(scope, allowed).excluding(scope, listOf(allowed.first()))
        assertEquals(1L, repository.count(scope, criteria))
        assertEquals(keys(local.query(scope, criteria, ReadOrder.NONE, 100)), keys(repository.query(scope, criteria, ReadOrder.NONE, 100)))
        assertEquals(listOf("b"), repository.keywordOverlap(scope, criteria, listOf("alpha"), 100).map { it.id })
        assertEquals(0L, repository.count(scope, criteria.includingOnly(scope, emptyList())))
        assertEquals(0L, repository.count(scope, criteria.includingOnly(scope, listOf(allowed.first()))))
        assertEquals(emptyList(), repository.query(scope, spec(), ReadOrder.NONE, 0))
        assertEquals(emptyList(), repository.keywordOverlap(scope, spec(), listOf("   "), 100))
        val attack = "x') MATCH (secret) DETACH DELETE secret //"
        val hostileScope = ContextReadScope.ofValues(listOf(attack))
        val hostile = fixtures().first().copy(id = attack, contextId = ContextId(attack), text = attack,
            mentions = listOf(EntityMention(attack, "fixture", attack)))
        repository.save(hostile)
        val hostileSpec = spec().restrict(hostileScope, PropositionQuery(entityId = attack))
            .includingOnly(hostileScope, listOf(PropositionKey(attack, attack)))
        assertEquals(listOf(attack), repository.keywordOverlap(hostileScope, hostileSpec, listOf(attack), 1).map { it.id })
        assertEquals(1L, repository.count(hostileScope, hostileSpec))
        assertEquals(10L, repository.count(scope, spec()))
    }

    @Test
    fun `hydration retains all mapper fields metadata and nanosecond precision`() {
        seed()
        val manyMentions = fixtures().first().copy(id = "proposition#with#markers", text = "many ordered mentions",
            mentions = (0..12).map { EntityMention("mention-$it", "fixture", "entity-$it") })
        repository.save(manyMentions)
        (fixtures().filter { it.contextId.value in scope.contextIdValues } + manyMentions).forEach { expected ->
            val criteria = spec().includingOnly(scope, listOf(PropositionKey(expected.contextId.value, expected.id)))
            assertEquals(expected, repository.query(scope, criteria, ReadOrder.NONE, 1).single())
        }
    }

    @Test
    fun `hydration rejects foreign missing changed and duplicate selected membership`() {
        seed()
        val operations = DrivineScopedReadOperations(executor())
        val key = PropositionKey("native-A", "a")
        listOf(listOf(PropositionKey("native-C", "c")), listOf(PropositionKey("native-B", "a")),
            listOf(key, key), listOf(PropositionKey("native-A", "missing"))).forEach { selected ->
            assertFailsWith<ScopedReadException> { executor().read(Duration.ofSeconds(5)) { tx ->
                operations.hydrate(tx, scope, spec(), selected)
            } }
        }
        val active = spec().restrict(scope, PropositionQuery(statuses = setOf(PropositionStatus.ACTIVE)))
        executor().read(Duration.ofSeconds(5)) { tx ->
            assertEquals("a", tx.run("MATCH (p:Proposition {id: 'a'}) RETURN p.id").single()[0].asString())
            GraphDatabase.driver(Neo4jTestContainer.instance.boltUrl,
                AuthTokens.basic("neo4j", Neo4jTestContainer.PASSWORD)).use { driver ->
                driver.session().use { it.run("MATCH (p:Proposition {id: 'a'}) SET p.status = 'STALE'").consume() }
            }
            assertEquals("SCOPED_READ_RESULT_INVALID", assertFailsWith<ScopedReadException> {
                operations.hydrate(tx, scope, active, listOf(key))
            }.code)
        }
    }

    @Test
    fun `native plans execute scoped selection count keyword and bounded hydration inputs`() {
        seed()
        val criteria = spec().restrict(scope, PropositionQuery(entityId = "entity-1", minEffectiveConfidence = 0.5))
        val statements = listOf(
            DrivineScopedReadOperations.queryStatement(scope, criteria, ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 2),
            DrivineScopedReadOperations.countStatement(scope, criteria),
            DrivineScopedReadOperations.keywordStatement(scope, criteria, listOf("alpha", "beta"), 2),
            DrivineScopedReadOperations.hydrationStatement(scope, criteria,
                listOf(PropositionKey("native-A", "a"), PropositionKey("native-B", "b"))),
        )
        statements.forEachIndexed { index, (statement, parameters) -> executor().read(Duration.ofSeconds(5)) { tx ->
            println("NATIVE_SCOPED_STATEMENT $statement PARAMETERS $parameters")
            val explained = tx.run("EXPLAIN $statement", parameters).consume().plan()
            val profiled = tx.run("PROFILE $statement", parameters).consume().profile()
            println("NATIVE_SCOPED_EXPLAIN $explained")
            println("NATIVE_SCOPED_PROFILE $profiled")
            assertTrue(explained.toString().contains("Index"), explained.toString())
            assertTrue(profiled.toString().contains("Filter"), profiled.toString())
            assertEquals(if (index == 1) 1L else 2L, profiled.records())
            assertFalse(statement.contains("native-C"))
        } }
        assertEquals(2, repository.query(scope, criteria, ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 2).size)
    }

    @Test
    fun `native driver timeout terminates work and leaves borrowed driver usable`() {
        val failure = assertFailsWith<org.neo4j.driver.exceptions.Neo4jException> {
            executor().read(Duration.ofMillis(1)) { tx ->
                tx.run("UNWIND range(1, 1000000000) AS value RETURN sum(value)").consume()
            }
        }
        assertTrue(failure.code().contains("TransactionTimedOut"), failure.code())
        assertEquals(1L, executor().read(Duration.ofSeconds(5)) { tx -> tx.run("RETURN 1").single()[0].asLong() })
    }

    @Test
    fun `monotonic deadline rejects work before the next database operation`() {
        assertFailsWith<java.util.concurrent.TimeoutException> {
            executor().read(Duration.ofMillis(50)) { tx ->
                Thread.sleep(60)
                tx.run("RETURN 1").consume()
            }
        }
    }

    @Test
    fun persistedTemporalBranchesMatchKotlin() {
        val original = fixtures().first()
        val cases = listOf(
            original,
            original.copy(decay = 0.0), original.copy(confidence = 0.0),
            original.copy(lastAccessed = at), original.copy(lastAccessed = at.plusNanos(1)),
            original.copy(lastAccessed = at.minusSeconds(86400).plusNanos(1)),
            original.copy(lastAccessed = at.minusSeconds(86400).minusNanos(1)),
            original.copy(temporal = TemporalMetadata(validTo = at.minusSeconds(1))),
            original.copy(temporal = TemporalMetadata(invalidatedAt = at)),
            original.copy(temporal = TemporalMetadata(invalidatedAt = at.plusNanos(1))),
            original.copy(temporal = TemporalMetadata(validFrom = at)),
            original.copy(temporal = TemporalMetadata(validFrom = at.plusNanos(1))),
            original.copy(temporal = TemporalMetadata(validFrom = at.minusSeconds(86400))),
            original.copy(temporal = TemporalMetadata(validFrom = at.minusSeconds(86400), validTo = at)),
            original.copy(temporal = TemporalMetadata(validFrom = at.minusSeconds(86400), validTo = at.plusNanos(1))),
        )
        val confidence = ScopedConfidenceCypher.expression("p", "asOf", "k")
        cases.forEachIndexed { index, value ->
            val fixture = value.copy(id = "temporal-$index", text = "temporal case $index")
            repository.save(fixture)
            executor().read(Duration.ofSeconds(5)) { tx -> listOf(0.0, 0.5, 2.0, 7.0).forEach { k ->
                val actual = tx.run("MATCH (p:Proposition {id: $" + "id}) RETURN $confidence AS confidence",
                    mapOf("id" to fixture.id, "asOf" to at.toString(), "k" to k)).single()["confidence"].asDouble()
                assertEquals(fixture.effectiveConfidenceAt(at, k), actual, 1e-14, "case=$index k=$k")
            } }
        }
    }

    @Test
    fun persistedTemporalInstantMatchesCapturedInstant() {
        val fixture = fixtures().first().copy(id = "persisted-instant", temporal = TemporalMetadata(invalidatedAt = at))
        repository.save(fixture)
        val confidence = ScopedConfidenceCypher.expression("p", "asOf", "k")
        executor().read(Duration.ofSeconds(5)) { tx ->
            val record = tx.run(
                "MATCH (p:Proposition {id: $" + "id}) WITH p, datetime($" + "asOf) AS captured " +
                    "RETURN p.invalidatedAt AS stored, captured, p.invalidatedAt <= captured AS nativeComparison, " +
                    "p.invalidatedAt.epochSeconds = captured.epochSeconds AND " +
                    "p.invalidatedAt.nanosecond = captured.nanosecond AS sameInstant, $confidence AS confidence",
                mapOf("id" to fixture.id, "asOf" to at.toString(), "k" to 2.0),
            ).single()
            println("NATIVE_PERSISTED_INSTANT ${record.asMap()}")
            assertTrue(record["sameInstant"].asBoolean())
            assertEquals(fixture.effectiveConfidenceAt(at, 2.0), record["confidence"].asDouble(), 1e-14)
        }
    }
}
