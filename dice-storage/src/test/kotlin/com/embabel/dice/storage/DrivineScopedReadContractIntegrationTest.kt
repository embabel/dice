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
import com.embabel.dice.proposition.PropositionQuery.OrderBy
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.temporal.TemporalMetadata
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.Mockito.mock
import org.neo4j.driver.exceptions.Neo4jException
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
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
    private fun spec() = ScopedReadSpec.of(scope)
    private val clock = ReadClock(at, 2.0)
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
        assertEquals(10L, configured.count(spec(), clock))
        assertEquals(listOf("a"), configured.query(spec(), clock, OrderBy.NONE, 1).map { it.id })
        assertEquals(listOf("a"), configured.keywordOverlap(spec(), clock, listOf("alpha", "beta"), 1).map { it.id })
        assertEquals(ScopedReadFailure.SCOPED_READ_UNSUPPORTED, assertFailsWith<ScopedReadException> {
            manual.count(spec(), clock)
        }.failure)
        assertFailsWith<IllegalArgumentException> { configured.withScopedReadTimeout(Duration.ZERO) }
        val retimed = configured.withScopedReadTimeout(Duration.ofSeconds(2))
        assertNotSame(configured, retimed)
        assertEquals(10L, retimed.count(spec(), clock))
        assertTrue(configured.supportsScopedReads)
        assertEquals(10L, configured.count(spec(), clock))
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
            val criteria = spec().restrict(query)
            assertEquals(local.count(criteria, clock), repository.count(criteria, clock), name)
            OrderBy.entries.forEach { order -> listOf(1, 3, 100).forEach { limit ->
                assertEquals(keys(local.query(criteria, clock, order, limit)),
                    keys(repository.query(criteria, clock, order, limit)), "$name order=$order limit=$limit")
            } }
            val tokens = listOf(" alpha ", "BETA", "alpha", "", "   ")
            assertEquals(keys(local.keywordOverlap(criteria, clock, tokens, 3)),
                keys(repository.keywordOverlap(criteria, clock, tokens, 3)), name)
            val allOf = criteria.restrict(PropositionQuery(entityId = "missing"))
            assertEquals(0L, repository.count(allOf, clock))
        } }
    }

    @Test
    fun `key grants empty intersections and hostile values cannot broaden reads`() {
        val local = seed()
        val allowed = listOf(PropositionKey("native-A", "a"), PropositionKey("native-B", "b"))
        val criteria = spec().includingOnly(allowed).excluding(listOf(allowed.first()))
        assertEquals(1L, repository.count(criteria, clock))
        assertEquals(keys(local.query(criteria, clock, OrderBy.NONE, 100)), keys(repository.query(criteria, clock, OrderBy.NONE, 100)))
        assertEquals(listOf("b"), repository.keywordOverlap(criteria, clock, listOf("alpha"), 100).map { it.id })
        assertEquals(0L, repository.count(criteria.includingOnly(emptyList()), clock))
        assertEquals(0L, repository.count(criteria.includingOnly(listOf(allowed.first())), clock))
        assertEquals(emptyList(), repository.query(spec(), clock, OrderBy.NONE, 0))
        assertEquals(emptyList(), repository.keywordOverlap(spec(), clock, listOf("   "), 100))
        val attack = "x') MATCH (secret) DETACH DELETE secret //"
        val hostileScope = ContextReadScope.ofValues(listOf(attack))
        val hostile = fixtures().first().copy(id = attack, contextId = ContextId(attack), text = attack,
            mentions = listOf(EntityMention(attack, "fixture", attack)))
        repository.save(hostile)
        val hostileSpec = ScopedReadSpec.of(hostileScope).restrict(PropositionQuery(entityId = attack))
            .includingOnly(listOf(PropositionKey(attack, attack)))
        assertEquals(listOf(attack), repository.keywordOverlap(hostileSpec, clock, listOf(attack), 1).map { it.id })
        assertEquals(1L, repository.count(hostileSpec, clock))
        assertEquals(10L, repository.count(spec(), clock))
    }

    @Test
    fun `ties order by context then id in code point order before the limit`() {
        // U+FF61 sorts before U+10000 by code point but after it in UTF-16, where U+10000 is U+D800 U+DC00.
        val base = fixtures().first()
        // Distinct texts: the store merges equal text within one context. The keyword overlap still ties.
        listOf("𐀀" to "｡", "｡" to "｡", "c1" to "𐀀", "c2" to "native-A").forEach { (id, context) ->
            repository.save(base.copy(id = id, contextId = ContextId(context), text = "ALPHA beta facts $id"))
        }
        val tied = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("native-A", "｡", "𐀀")))
        // Context first (native-A, then ｡, then 𐀀), then id. UTF-16 order would give c2, c1, 𐀀, ｡.
        val expected = listOf("c2", "｡", "𐀀", "c1")
        OrderBy.entries.forEach { order -> listOf(2, 10).forEach { limit ->
            assertEquals(expected.take(limit), repository.query(tied, clock, order, limit).map { it.id }, "$order $limit")
        } }
        assertEquals(expected.take(2), repository.keywordOverlap(tied, clock, listOf("alpha"), 2).map { it.id })
        val granted = tied.includingOnly(listOf(PropositionKey("｡", "𐀀"), PropositionKey("｡", "｡"), PropositionKey("𐀀", "c1")))
        assertEquals(listOf("｡", "𐀀", "c1"), repository.query(granted, clock, OrderBy.NONE, 10).map { it.id })
    }

    @Test
    fun `hydration retains all mapper fields metadata and nanosecond precision`() {
        seed()
        val manyMentions = fixtures().first().copy(id = "proposition#with#markers", text = "many ordered mentions",
            mentions = (0..12).map { EntityMention("mention-$it", "fixture", "entity-$it") })
        repository.save(manyMentions)
        (fixtures().filter { it.contextId.value in scope.contextIdValues } + manyMentions).forEach { expected ->
            val criteria = spec().includingOnly(listOf(PropositionKey(expected.contextId.value, expected.id)))
            assertEquals(expected, repository.query(criteria, clock, OrderBy.NONE, 1).single())
        }
    }

    /**
     * An older write path stored a proposition's dates as ISO strings, and live databases still hold
     * such rows. Reads have to load them with the same instants they had before, or one old row would
     * fail the whole read.
     */
    @Test
    fun `rows with dates stored as ISO strings load with their original instants`() {
        clean()
        val saved = fixtures().single { it.id == "window" }
        repository.save(saved)
        manager.execute(
            QuerySpecification.withStatement(
                "MATCH (p:Proposition {id: \$id}) SET p.created = toString(p.created), " +
                    "p.lastAccessed = toString(p.lastAccessed), p.validFrom = toString(p.validFrom), " +
                    "p.contentRevised = toString(p.contentRevised), p.metadataRevised = toString(p.metadataRevised), " +
                    "p.lastTouched = toString(p.lastTouched), p.validTo = toString(p.validTo), " +
                    "p.observedAt = toString(p.observedAt)",
            ).bind(mapOf("id" to saved.id)),
        )
        val stored = manager.getOne(
            QuerySpecification.withStatement("MATCH (p:Proposition {id: \$id}) RETURN valueType(p.validFrom)")
                .bind(mapOf("id" to saved.id)).transform(String::class.java),
        )
        // The premise: the rewrite really left a string behind.
        assertTrue(stored.startsWith("STRING"), stored)

        OrderBy.entries.forEach { order ->
            assertEquals(listOf(saved), repository.query(spec(), clock, order, 10), "$order")
        }
        assertEquals(listOf(saved), repository.keywordOverlap(spec(), clock, listOf("window"), 10))
    }

    @Test
    fun `native plans execute scoped selection count and keyword statements`() {
        seed()
        val criteria = spec().restrict(PropositionQuery(entityId = "entity-1", minEffectiveConfidence = 0.5))
        val statements = listOf(
            DrivineScopedReadOperations.queryStatement(criteria, clock, OrderBy.EFFECTIVE_CONFIDENCE_DESC, 2),
            DrivineScopedReadOperations.countStatement(criteria, clock),
            DrivineScopedReadOperations.keywordStatement(criteria, clock, listOf("alpha", "beta"), 2),
        )
        statements.forEachIndexed { index, (statement, parameters) -> executor().read(Duration.ofSeconds(5)) { tx ->
            assertEquals(if (index == 1) 1 else 2, tx.run(statement, parameters).list().size, statement)
            assertFalse(statement.contains("native-C"))
        } }
        assertEquals(2, repository.query(criteria, clock, OrderBy.EFFECTIVE_CONFIDENCE_DESC, 2).size)
    }

    @Test
    fun `E1 one read runs one statement`() {
        val criteria = spec().restrict(PropositionQuery(entityId = "entity-1"))
        val loading = "RETURN p, [(p)-[:HAS_MENTION]->(m:Mention) | m] AS mentions"
        listOf(
            DrivineScopedReadOperations.queryStatement(criteria, clock, OrderBy.EFFECTIVE_CONFIDENCE_DESC, 2).first,
            DrivineScopedReadOperations.keywordStatement(criteria, clock, listOf("alpha", "beta"), 2).first,
        ).forEach { statement ->
            assertEquals(1, statement.split(loading).size - 1, statement)
            assertFalse(statement.contains("UNWIND ${'$'}selectedKeys"), statement)
        }
    }

    @Test
    fun `a fragment without its own instant is evaluated at the read clock`() {
        clean()
        val touched = at.minus(Duration.ofDays(401))
        val base = fixtures().first().copy(
            created = touched, contentRevised = touched, metadataRevised = touched, lastAccessed = touched,
        )
        val rows = listOf(base.copy(id = "slow"), base.copy(id = "fast", decay = 0.5))
        rows.forEach { repository.save(it) }
        val readClock = ReadClock(at.minus(Duration.ofDays(400)), 2.0)
        val fragment = PropositionQuery(minEffectiveConfidence = 0.5)
        fun keptAt(asOf: Instant) = keys(rows.filter { it.effectiveConfidenceAt(asOf, fragment.decayK) >= 0.5 })
        val expected = keptAt(readClock.asOf)
        // The premise: rows kept at the read clock differ from rows kept now, so a read at now would show.
        assertNotEquals(expected.size, keptAt(Instant.now()).size)
        val criteria = spec().restrict(fragment)
        assertEquals(expected.size.toLong(), repository.count(criteria, readClock))
        assertEquals(expected, keys(repository.query(criteria, readClock, OrderBy.NONE, 10)))
    }

    @Test
    fun `E1 E2 server timeout fails as a scoped read failure and leaves the driver usable`() {
        val timedOut = setOf(
            "Neo.ClientError.Transaction.TransactionTimedOutClientConfiguration",
            "Neo.ClientError.Transaction.TransactionTimedOut",
        )
        val slow = "UNWIND range(1, 1000000000) AS value RETURN sum(value)"

        val byDefault = assertFailsWith<ScopedReadException> {
            executor().read(Duration.ofMillis(100)) { tx -> tx.run(slow).consume() }
        }
        assertEquals(ScopedReadFailure.SCOPED_READ_FAILED, byDefault.failure)
        val defaultCause = assertIs<Neo4jException>(byDefault.cause)
        assertTrue(defaultCause.code() in timedOut, defaultCause.code())

        val chosen = assertFailsWith<ScopedReadException> {
            executor().read(Duration.ofMillis(100), ScopedReadFailure.EXACT_VECTOR_DEADLINE_EXCEEDED) { tx ->
                tx.run(slow).consume()
            }
        }
        assertEquals(ScopedReadFailure.EXACT_VECTOR_DEADLINE_EXCEEDED, chosen.failure)
        val chosenCause = assertIs<Neo4jException>(chosen.cause)
        assertTrue(chosenCause.code() in timedOut, chosenCause.code())

        assertEquals(1L, executor().read(Duration.ofSeconds(5)) { tx -> tx.run("RETURN 1").single()[0].asLong() })

        val other = assertFailsWith<ScopedReadException> {
            executor().read(Duration.ofSeconds(5), ScopedReadFailure.EXACT_VECTOR_DEADLINE_EXCEEDED) { tx ->
                tx.run("RETURN 1/0").consume()
            }
        }
        assertEquals(ScopedReadFailure.SCOPED_READ_FAILED, other.failure)
        assertIs<Neo4jException>(other.cause)
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
            assertTrue(record["sameInstant"].asBoolean())
            assertEquals(fixture.effectiveConfidenceAt(at, 2.0), record["confidence"].asDouble(), 1e-14)
        }
    }
}
