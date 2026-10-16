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
package com.embabel.dice.storage.falkordb

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStoreType
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.temporal.TemporalMetadata
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.NativeSchemaCommandExecutor
import org.drivine.query.QuerySpecification
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.SchemaItemInfo
import org.drivine.transaction.DrivineTransactionObject
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class FalkorDbPropositionRepositoryIntegrationTest {
    private lateinit var first: FalkorDbConnectionProvider
    private lateinit var second: FalkorDbConnectionProvider
    private lateinit var repository: FalkorDbPropositionRepository
    private lateinit var other: FalkorDbPropositionRepository
    private val now = Instant.parse("2026-04-05T06:07:08.123456789Z")

    @BeforeEach
    fun setUp() {
        val graph = "propositions_${UUID.randomUUID().toString().replace("-", "")}"
        first = server.provider(graph)
        second = server.provider(graph)
        repository = FalkorDbPropositionRepository(first)
        other = FalkorDbPropositionRepository(second)
    }

    @AfterEach
    fun closeProviders() {
        first.end()
        second.end()
    }

    private fun proposition(id: String = "one", context: String = "a"): Proposition = Proposition(
        id = id, contextId = ContextId(context), text = "Alpha beta literal ' \\ \$value 漢字 🙂",
        mentions = listOf(EntityMention("Alpha", "Person", "entity-a", MentionRole.SUBJECT, mapOf("alias" to "A")),
            EntityMention("beta", "Topic", "entity-b", MentionRole.OBJECT), EntityMention("unresolved", "Topic")),
        confidence = 0.9, decay = 0.03, importance = 0.7, reasoning = "evidence reasoning",
        grounding = listOf("chunk-a", "chunk-b"), created = now.minusSeconds(172800),
        contentRevised = now.minusSeconds(86400), metadataRevised = now.minusNanos(1),
        lastAccessed = now.minusSeconds(86400).plusNanos(1), pinned = true,
        status = PropositionStatus.ACTIVE, level = 2, sourceIds = listOf("source-proposition"), reinforceCount = 4,
        metadata = mapOf(DiceMetadataKeys.TRUST_SCORE to 0.8, "nested" to mapOf("labels" to listOf("a", "b"))),
        uri = "https://example.test/fact", temporal = TemporalMetadata(
            observedAt = now.minusSeconds(259200), validFrom = now.minusSeconds(86400),
            validTo = now.plusNanos(1), invalidatedAt = now.plusSeconds(86400),
            supersedes = listOf("old"), contradicts = listOf("conflict"),
        ), provenanceEntries = listOf(evidence("one"), ProvenanceEntry(ConnectorRef("test:connector", "object\\id"), sourceRevision = "r1")),
    )

    private fun evidence(id: String) = ProvenanceEntry(UriLocator("https://example.test/$id", "Source $id"),
        chunkId = "chunk-$id", startOffset = 1, endOffset = 5, contentHash = "hash-$id", sourceRevision = "revision-$id")

    @Test
    fun `complete aggregate survives reconnect and hostile literals stay literal`() {
        val original = proposition("id'\\\$name", "scope'\\\$name")
        assertEquals(original, repository.save(original))
        assertEquals(original, other.findById(original.id))
        assertEquals(listOf(original), other.query(PropositionQuery.againstContext(original.contextId.value)))
        assertEquals(listOf(original), other.findAll(true))
        assertEquals(original.provenanceEntries, other.provenanceOf(original.id))
        val hostile = "x' MATCH (n) DETACH DELETE n //"
        val injection = original.copy(id = hostile, contextId = ContextId(hostile), text = hostile)
        repository.save(injection)
        assertEquals(injection, other.findById(hostile))
        assertEquals(2, other.count())
        assertEquals(PropositionStoreType.STORED, repository.storeType)
        assertFalse(repository.supportsVector)
        assertTrue(repository.findSimilarWithScores(TextSimilaritySearchRequest("Alpha", 0.0, 10)).isEmpty())
        assertTrue(repository.findSimilarWithScores(TextSimilaritySearchRequest("Alpha", 0.0, 10), PropositionQuery()).isEmpty())
    }

    private fun fixtures(): List<Proposition> = (0..8).map { i ->
        proposition(listOf("a", "aa", "𐀀", "\uE000", "🙂", "f", "g", "h", "i")[i], if (i % 2 == 0) "a" else "b").copy(
            text = if (i % 3 == 0) "Alpha beta" else if (i % 3 == 1) "ALPHA" else "unrelated",
            confidence = 0.1 + i * 0.1, decay = 0.05 * i, importance = i / 10.0, level = i % 4,
            reinforceCount = i, pinned = i % 2 == 0,
            mentions = if (i % 3 == 0) proposition().mentions else listOf(EntityMention("beta", "Topic", "entity-b")),
            status = if (i % 2 == 0) PropositionStatus.ACTIVE else PropositionStatus.SUPERSEDED,
            created = now.minusSeconds(i * 86400L).plusNanos(i.toLong()),
            contentRevised = now.minusSeconds(i * 86400L).minusNanos(1),
            metadataRevised = now.minusSeconds(i * 43200L).plusNanos(1),
            lastAccessed = now.minusSeconds(i * 3600L).plusNanos(i.toLong()),
            temporal = when (i % 5) {
                0 -> null
                1 -> TemporalMetadata(validFrom = now.minusSeconds(86400))
                2 -> TemporalMetadata(validFrom = now.minusSeconds(86400), validTo = now.plusNanos(1))
                3 -> TemporalMetadata(validFrom = now.plusNanos(1))
                else -> TemporalMetadata(invalidatedAt = now)
            },
            metadata = when (i % 3) {
                0 -> mapOf(DiceMetadataKeys.TRUST_SCORE to 0.2)
                1 -> mapOf(DiceMetadataKeys.TRUST_SCORE to 0.9)
                else -> emptyMap()
            },
        )
    }

    @Test
    fun `every native membership predicate and scoped ordering matches portable oracle`() {
        val oracle = InMemoryPropositionRepository()
        val rows = fixtures()
        repository.saveAll(rows)
        oracle.saveAll(rows)
        val scope = ContextReadScope.ofValues(listOf("a", "b"))
        val queries = listOf(
            PropositionQuery(), PropositionQuery(contextId = ContextId("a")), PropositionQuery(entityId = "entity-a"),
            PropositionQuery(anyEntityIds = listOf("entity-a", "missing")), PropositionQuery(anyEntityIds = emptyList()),
            PropositionQuery(allEntityIds = listOf("entity-a", "entity-b")), PropositionQuery(allEntityIds = emptyList()),
            PropositionQuery(statuses = setOf(PropositionStatus.ACTIVE)), PropositionQuery(statuses = emptySet()),
            PropositionQuery(minLevel = 2), PropositionQuery(maxLevel = 1), PropositionQuery(minLevel = 2, maxLevel = 1),
            PropositionQuery(createdAfter = rows[4].created), PropositionQuery(createdBefore = rows[4].created),
            PropositionQuery(revisedAfter = rows[4].lastTouched), PropositionQuery(revisedBefore = rows[4].lastTouched),
            PropositionQuery(accessedAfter = rows[4].lastAccessed), PropositionQuery(accessedBefore = rows[4].lastAccessed),
            PropositionQuery(minConfidence = 0.5), PropositionQuery(minEffectiveConfidence = 0.4),
            PropositionQuery(belowEffectiveConfidence = 0.4), PropositionQuery(minImportance = 0.4),
            PropositionQuery(minReinforceCount = 4), PropositionQuery(minTrustScore = 0.7),
            PropositionQuery(pinned = true), PropositionQuery(pinned = false),
            PropositionQuery(minConfidence = 0.4, minLevel = 1, minTrustScore = 0.5, pinned = false),
        )
        for ((index, criteria) in queries.withIndex()) {
            val query = criteria.copy(effectiveConfidenceAsOf = now)
            assertEquals(oracle.query(query).map { it.id }.toSet(), repository.query(query).map { it.id }.toSet(), "legacy filter $index")
            assertEquals(oracle.count(query), repository.count(query), "legacy count $index")
            val spec = ScopedReadSpec.at(now, 2.0).restrict(scope, query)
            assertEquals(oracle.count(scope, spec), repository.count(scope, spec), "scoped count $index")
            for (order in ReadOrder.entries) {
                for (limit in listOf(0, 3, 20)) {
                    assertEquals(oracle.query(scope, spec, order, limit), repository.query(scope, spec, order, limit),
                        "filter=$index order=$order limit=$limit")
                }
            }
        }
    }

    @Test
    fun `effective confidence keeps nanosecond window and whole-day decay boundaries`() {
        val scope = ContextReadScope.ofValues(listOf("a"))
        val oracle = InMemoryPropositionRepository()
        val p = proposition().copy(temporal = null, contentRevised = now.minusSeconds(86400).plusNanos(1),
            lastAccessed = now.minusSeconds(172800), confidence = 0.9, decay = 0.1)
        val rows = listOf(p, p.copy(id = "exact-day", contentRevised = now.minusSeconds(86400)),
            p.copy(id = "dated-start", temporal = TemporalMetadata(validFrom = now.plusNanos(1))),
            p.copy(id = "dated-end", temporal = TemporalMetadata(validFrom = now.minusSeconds(86400), validTo = now)),
            p.copy(id = "invalidated-later", temporal = TemporalMetadata(invalidatedAt = now.plusNanos(1))),
            p.copy(id = "invalidated-now", temporal = TemporalMetadata(invalidatedAt = now)))
        repository.saveAll(rows)
        oracle.saveAll(rows)
        for (asOf in listOf(now.minusNanos(1), now, now.plusNanos(1))) {
            val spec = ScopedReadSpec.at(asOf, 2.0)
            assertEquals(oracle.query(scope, spec, ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 20),
                repository.query(scope, spec, ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 20))
        }
    }

    @Test
    fun `grants intersect with exclusions and filters before rank limit and keyword`() {
        val rows = fixtures()
        val oracle = InMemoryPropositionRepository()
        repository.saveAll(rows)
        oracle.saveAll(rows)
        val scope = ContextReadScope.ofValues(listOf("a", "b"))
        val keys = rows.map { PropositionKey(it.contextId.value, it.id) }
        val spec = ScopedReadSpec.at(now, 2.0)
            .includingOnly(scope, keys.take(7)).includingOnly(scope, keys.drop(2))
            .excluding(scope, listOf(keys[3], PropositionKey("b", rows[4].id)))
            .restrict(scope, PropositionQuery(minConfidence = 0.3, limit = 1))
        assertEquals(oracle.count(scope, spec), repository.count(scope, spec))
        assertEquals(oracle.query(scope, spec, ReadOrder.NONE, 1), repository.query(scope, spec, ReadOrder.NONE, 1))
        val tokens = listOf(" Alpha ", "BETA", "alpha", "", "   ")
        assertEquals(oracle.keywordOverlap(scope, spec, tokens, 2), repository.keywordOverlap(scope, spec, tokens, 2))
        val empty = spec.includingOnly(scope, emptyList())
        assertEquals(0L, repository.count(scope, empty))
        assertTrue(repository.query(scope, empty, ReadOrder.NONE, 10).isEmpty())
        assertTrue(repository.keywordOverlap(scope, spec, listOf("  "), 10).isEmpty())
        assertTrue(repository.keywordOverlap(scope, spec, tokens, 0).isEmpty())
        val isolated = ContextReadScope.ofValues(listOf("missing"))
        assertEquals(0L, repository.count(isolated, ScopedReadSpec.at(now, 2.0)))
    }

    @Test
    fun `legacy keyword overlap retains base limit and case-insensitive token semantics`() {
        val oracle = InMemoryPropositionRepository()
        val rows = (0..3).map { i -> proposition("keyword-$i").copy(
            confidence = 0.2 + i * 0.2, decay = 0.0, temporal = null,
            text = listOf("Alpha beta", "ALPHA", "beta", "ı İ alpha")[i],
        ) }
        repository.saveAll(rows)
        oracle.saveAll(rows)
        for (baseLimit in listOf(null, 0, 1, 3)) {
            val query = PropositionQuery.againstContext("a").copy(limit = baseLimit, effectiveConfidenceAsOf = now)
            for (tokens in listOf(listOf("alpha", "beta", "alpha"), listOf("i"), listOf(""), emptyList())) {
                assertEquals(oracle.keywordOverlap(query, tokens, 2), repository.keywordOverlap(query, tokens, 2),
                    "baseLimit=$baseLimit tokens=$tokens")
            }
        }
    }

    @Test
    fun `administration and inherited graph temporal operations execute complete contracts`() {
        val first = proposition("one", "prefix:a")
        val second = proposition("two", "prefix:b").copy(level = 4, sourceIds = listOf(first.id))
        val third = proposition("three", "other")
        val saved = repository.saveAllReturningCanonical(listOf(second, first, third))
        assertEquals(listOf(second, first, third), saved.canonicalPropositions)
        assertEquals(mapOf("two" to "two", "one" to "one", "three" to "three"), saved.canonicalIdByInputId)
        assertEquals(listOf(second, first, third), repository.findAll())
        assertEquals(listOf(first), repository.findByContextId(first.contextId))
        assertEquals(listOf(first), repository.findByContextIdValue(first.contextId.value))
        assertEquals(3, repository.findByEntity(RetrievableIdentifier("entity-a", "Person")).size)
        assertEquals(3, repository.findByStatus(PropositionStatus.ACTIVE).size)
        assertEquals(3, repository.findByGrounding("chunk-a").size)
        assertEquals(listOf(second), repository.findByMinLevel(4))
        assertEquals(listOf(first), repository.findSources(second))
        assertEquals(listOf(second), repository.findAbstractionsOf(first.id))
        assertEquals(3, repository.findByCreatedBetween(first.created, first.created).size)
        assertEquals(3, repository.findByRevisedBetween(first.lastTouched, first.lastTouched).size)
        assertEquals(3, repository.findAllOrderedByEffectiveConfidence().size)
        assertEquals(3, repository.findByEffectiveConfidenceAbove(0.0).size)
        assertEquals(3, repository.findByCreatedBetweenOrderedByEffectiveConfidence(first.created, first.created, now).size)
        assertNull(repository.pin("missing"))
        assertNull(repository.unpin("missing"))
        assertFalse(assertNotNull(repository.unpin(first.id)).pinned)
        assertTrue(assertNotNull(repository.pin(first.id)).pinned)
        val before = assertNotNull(repository.findById(first.id))
        repository.touchAccessed(listOf(first.id, "missing", first.id))
        val touched = assertNotNull(other.findById(first.id))
        assertTrue(touched.lastAccessed > before.lastAccessed)
        assertEquals(before.contentRevised, touched.contentRevised)
        assertEquals(before.provenanceEntries, touched.provenanceEntries)
        assertEquals(1, repository.count(PropositionQuery(limit = 1)))
        assertFalse(repository.delete("missing"))
        assertEquals(1, repository.clearByContext("prefix:a"))
        assertEquals(1, repository.clearByContextPrefix("prefix:"))
        assertEquals(listOf(third), repository.findAll())
        assertTrue(repository.delete(third.id))
        assertEquals(0, repository.clearAll())
    }

    @Test
    fun `save appends evidence and authoritative replacement handles empty and missing IDs`() {
        val p = proposition()
        repository.save(p)
        repository.save(p.copy(provenanceEntries = listOf(evidence("new"))))
        val expected = (p.provenanceEntries + evidence("new")).distinct()
        assertEquals(expected, other.provenanceOf(p.id))
        repository.addProvenance(p.id, listOf(evidence("new")))
        assertEquals(expected, other.provenanceOf(p.id))
        repository.setProvenance(p.id, listOf(evidence("only"), evidence("only")))
        assertEquals(listOf(evidence("only")), other.provenanceOf(p.id))
        assertNotNull(repository.addProvenance(p.id, emptyList()))
        assertEquals(listOf(evidence("only")), other.provenanceOf(p.id))
        assertNotNull(repository.clearProvenance(p.id))
        assertTrue(other.provenanceOf(p.id).isEmpty())
        assertNull(repository.addProvenance("missing", emptyList()))
        assertNull(repository.setProvenance("missing", listOf(evidence("no"))))
        assertNull(repository.clearProvenance("missing"))
        assertTrue(repository.provenanceOf("missing").isEmpty())
    }

    @Test
    fun `separate providers concurrently create one ID and preserve every appended evidence entry`() {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val jobs = listOf(repository, other).mapIndexed { worker, store -> pool.submit {
                check(start.await(10, TimeUnit.SECONDS))
                repeat(15) { n -> store.save(proposition().copy(provenanceEntries = listOf(evidence("$worker-$n")))) }
            } }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, repository.count())
            val stored = assertNotNull(repository.findById("one"))
            assertEquals(30, stored.provenanceEntries.size)
            assertEquals((0..1).flatMap { w -> (0..14).map { n -> evidence("$w-$n") } }.toSet(), stored.provenanceEntries.toSet())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun intercept(before: (QuerySpecification<*>) -> Unit): ConnectionProvider = object : ConnectionProvider by first, NativeSchemaCommandExecutor by first {
        override fun connect(): Connection {
            val delegate = first.connect()
            return object : Connection by delegate {
                override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
                    before(spec)
                    return delegate.query(spec)
                }
            }
        }
    }

    @Test
    fun `stale CAS recomputes pin and evidence updates against current aggregate`() {
        repository.save(proposition())
        val armed = AtomicBoolean(true)
        val provider = intercept { spec ->
            if (spec.statement!!.text.contains("AS accepted") && armed.compareAndSet(true, false)) {
                other.addProvenance("one", listOf(evidence("concurrent")))
            }
        }
        val raced = FalkorDbPropositionRepository(provider)
        assertFalse(assertNotNull(raced.unpin("one")).pinned)
        assertTrue(other.provenanceOf("one").contains(evidence("concurrent")))
        assertFalse(armed.get())
    }

    @Test
    fun `delete between read and CAS leaves no empty node and mutation returns missing`() {
        repository.save(proposition())
        val armed = AtomicBoolean(true)
        val provider = intercept { spec ->
            if (spec.statement!!.text.contains("AS accepted") && armed.compareAndSet(true, false)) {
                assertTrue(other.delete("one"))
            }
        }
        assertNull(FalkorDbPropositionRepository(provider).addProvenance("one", listOf(evidence("late"))))
        assertEquals(0, repository.count())
        assertNull(repository.findById("one"))
    }

    @Test
    fun `failed native replacement rolls back aggregate and mirrored query fields`() {
        val p = proposition()
        repository.save(p)
        val armed = AtomicBoolean(true)
        val provider = intercept { spec ->
            if (spec.statement!!.text.contains("AS accepted") && armed.compareAndSet(true, false)) {
                val properties = spec.parameters["properties"] as Map<*, *>
                spec.bind(spec.parameters + ("properties" to (properties + ("invalid" to mapOf("nested" to "not a graph property")))))
            }
        }
        assertThrows<RuntimeException> { FalkorDbPropositionRepository(provider).save(p.copy(text = "changed", level = 99)) }
        assertEquals(p, other.findById(p.id))
        assertTrue(other.findByMinLevel(99).isEmpty())
        assertEquals(listOf(p), other.findByMinLevel(2))
    }

    @Test
    fun `caller Spring and Drivine transactions are rejected before any database call`() {
        val calls = AtomicInteger()
        val provider = intercept { calls.incrementAndGet() }
        val guarded = FalkorDbPropositionRepository(provider)
        calls.set(0)
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThrows<IllegalStateException> { guarded.save(proposition()) }
            assertThrows<IllegalStateException> { guarded.delete("one") }
            assertThrows<IllegalStateException> { guarded.pin("one") }
            assertThrows<IllegalStateException> { guarded.touchAccessed(emptyList()) }
            assertThrows<IllegalStateException> { guarded.clearAll() }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        val holder = TransactionContextHolder(DatabaseRegistry(DataSourceMap()))
        holder.currentTransaction = DrivineTransactionObject()
        try {
            assertThrows<IllegalStateException> { FalkorDbPropositionRepository(provider, holder).save(proposition()) }
        } finally {
            holder.currentTransaction = null
        }
        assertEquals(0, calls.get())
        assertEquals(0, repository.count())
    }

    @Test
    fun `scoped select and keyword hydrate only selected rows in one statement`() {
        repository.saveAll(fixtures())
        val calls = AtomicInteger()
        var observing = false
        val provider = intercept { spec ->
            if (observing) {
                calls.incrementAndGet()
                assertTrue(spec.statement!!.text.contains("LIMIT"))
                assertTrue(spec.statement!!.text.contains("RETURN {aggregate:"))
            }
        }
        val native = FalkorDbPropositionRepository(provider)
        observing = true
        val scope = ContextReadScope.ofValues(listOf("a", "b"))
        val spec = ScopedReadSpec.at(now, 2.0)
        assertEquals(2, native.query(scope, spec, ReadOrder.CREATED_DESC, 2).size)
        assertEquals(2, native.keywordOverlap(scope, spec, listOf("alpha"), 2).size)
        assertEquals(2, calls.get())
    }

    @Test
    fun `concurrent cold starts await uniqueness and reconnect accepts existing operational schema`() {
        val graph = "cold_${UUID.randomUUID().toString().replace("-", "")}"
        val providers = listOf(server.provider(graph), server.provider(graph))
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val jobs = providers.map { provider -> pool.submit {
                check(start.await(10, TimeUnit.SECONDS))
                val store = FalkorDbPropositionRepository(provider)
                store.save(proposition())
            } }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            val restored = FalkorDbPropositionRepository(providers.first())
            assertEquals(1, restored.count())
            assertEquals(proposition(), restored.findById("one"))
            val provider = providers.first()
            val constraints = provider.schemaGrammar.parseConstraintRows(
                FalkorDbAccess(provider, null).query(provider.schemaGrammar.listConstraintsQuery()),
            )
            assertEquals("OPERATIONAL", constraints.single { it.label == "DiceProposition" } .status)
        } finally {
            pool.shutdownNow()
            providers.forEach { it.end() }
        }
    }

    @Test
    fun `duplicate existing identities and failed constraints reject startup before business writes`() {
        val raw = server.provider("duplicates_${UUID.randomUUID().toString().replace("-", "")}")
        try {
            val access = FalkorDbAccess(raw, null)
            access.query("CREATE (:DiceProposition {id:'duplicate'}) CREATE (:DiceProposition {id:'duplicate'}) RETURN {value:1} AS result")
            repeat(2) { assertThrows<RuntimeException> { FalkorDbPropositionRepository(raw) } }
            assertEquals(2L, (access.query("MATCH (p:DiceProposition) RETURN {value:count(p)} AS result").single()["value"] as Number).toLong())
            assertEquals(0L, (access.query("MATCH (p:DiceProposition) WHERE p.aggregate IS NOT NULL RETURN {value:count(p)} AS result").single()["value"] as Number).toLong())
        } finally {
            raw.end()
        }
    }

    @Test
    fun `repaired duplicate identities recover failed constraints on restart`() {
        val raw = server.provider("repaired_${UUID.randomUUID().toString().replace("-", "")}")
        try {
            val access = FalkorDbAccess(raw, null)
            access.write("CREATE (:DiceProposition {id:'duplicate', extra:false}), (:DiceProposition {id:'duplicate', extra:true})")
            assertThrows<IllegalStateException> { FalkorDbPropositionRepository(raw) }
            access.write("MATCH (p:DiceProposition {extra:true}) DELETE p")
            val recovered = FalkorDbPropositionRepository(raw)
            val fact = proposition("after-repair")
            recovered.save(fact)
            assertEquals(fact, FalkorDbPropositionRepository(raw).findById(fact.id))
            val constraints = raw.schemaGrammar.parseConstraintRows(access.query(raw.schemaGrammar.listConstraintsQuery()))
            assertEquals("OPERATIONAL", constraints.single { it.label == "DiceProposition" }.status)
        } finally {
            raw.end()
        }
    }

    @Test
    fun `schema creation recovers lost responses without relying on exception text`() {
        val raw = server.provider("response_${UUID.randomUUID().toString().replace("-", "")}")
        val indexResponseLost = AtomicBoolean(false)
        val constraintResponseLost = AtomicBoolean(false)
        val indexBuildingObserved = AtomicBoolean(false)
        val constraintBuildingObserved = AtomicBoolean(false)
        try {
            val grammar = object : SchemaGrammar by raw.schemaGrammar {
                override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> =
                    raw.schemaGrammar.parseConstraintRows(rows).map { item ->
                        if (constraintResponseLost.get() && constraintBuildingObserved.compareAndSet(false, true)) {
                            item.copy(status = "UNDER CONSTRUCTION")
                        } else item
                    }
            }
            val provider = object : ConnectionProvider by raw, NativeSchemaCommandExecutor by raw {
                override val schemaGrammar = grammar
                override fun connect(): Connection {
                    val delegate = raw.connect()
                    return object : Connection by delegate {
                        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
                            val result = delegate.query(spec)
                            if (spec.statement!!.text.startsWith("CREATE INDEX") && indexResponseLost.compareAndSet(false, true)) {
                                throw IllegalStateException("Index response unavailable", RuntimeException("Driver wrapper"))
                            }
                            if (spec.statement!!.text.startsWith("CALL db.indexes() YIELD label, properties, status") &&
                                indexResponseLost.get() && indexBuildingObserved.compareAndSet(false, true)) {
                                @Suppress("UNCHECKED_CAST")
                                return result.map { (it as Map<String, Any?>) + ("status" to "UNDER CONSTRUCTION") } as List<T>
                            }
                            return result
                        }
                    }
                }

                override fun executeNativeSchemaCommand(args: List<String>): Any? {
                    raw.executeNativeSchemaCommand(args)
                    constraintResponseLost.set(true)
                    throw IllegalStateException("Constraint response unavailable", RuntimeException("Driver wrapper"))
                }
            }
            val recovered = FalkorDbPropositionRepository(provider)
            assertTrue(indexResponseLost.get())
            assertTrue(constraintResponseLost.get())
            assertTrue(indexBuildingObserved.get())
            assertTrue(constraintBuildingObserved.get())
            recovered.save(proposition())
            assertEquals(proposition(), FalkorDbPropositionRepository(raw).findById("one"))
        } finally {
            raw.end()
        }
    }

    @Test
    fun `schema creation errors propagate when the required identity is absent`() {
        val raw = server.provider("failure_${UUID.randomUUID().toString().replace("-", "")}")
        val failure = IllegalStateException("Schema command refused")
        try {
            val provider = object : ConnectionProvider by raw, NativeSchemaCommandExecutor by raw {
                override fun executeNativeSchemaCommand(args: List<String>): Any? = throw failure
            }
            assertSame(failure, assertThrows<IllegalStateException> { FalkorDbPropositionRepository(provider) })
            assertTrue(FalkorDbAccess(raw, null).query("MATCH (p:DiceProposition) RETURN {id:p.id} AS result").isEmpty())
        } finally {
            raw.end()
        }
    }

    companion object {
        @Container
        @JvmField
        internal val server = FalkorDbTestServer()
    }
}
