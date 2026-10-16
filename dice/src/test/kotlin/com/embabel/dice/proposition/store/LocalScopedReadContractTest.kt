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
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ContextSetReadCapable
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedPropositionReader
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.matchesFilters
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.random.Random

class LocalScopedReadContractTest {

    enum class Backend { MEMORY, JSON, JSON_RELOADED }

    @TempDir
    lateinit var directory: Path

    private val asOf = Instant.parse("2026-01-20T00:00:00Z")
    private val old = asOf.minusSeconds(10 * 86400L)
    private val scope = ContextReadScope.ofValues(listOf("B", "A", "A"))
    private val spec get() = ScopedReadSpec.at(asOf, 0.7)
    private val keyOrder = compareBy<Proposition> { it.contextId.value }.thenBy { it.id }

    private fun row(id: String, context: String, confidence: Double = 0.8) = Proposition(
        id = id, contextId = ContextId(context), text = "alpha beta", mentions = emptyList(),
        confidence = confidence, created = old, contentRevised = old, metadataRevised = old,
        lastAccessed = old,
    )

    private fun repository(backend: Backend, rows: List<Proposition>): PropositionRepository {
        val path = directory.resolve("${backend.name}.json")
        val repository = if (backend == Backend.MEMORY) InMemoryPropositionRepository()
        else JsonFilePropositionRepository(path)
        repository.saveAll(rows.shuffled(Random(291)))
        return if (backend == Backend.JSON_RELOADED) JsonFilePropositionRepository(path) else repository
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `global union filters denied decoys before K and counts without cap`(backend: Backend) {
        val rows = listOf(row("a", "A", 0.7), row("b", "B", 0.9), row("c", "C", 1.0))
        val repository = repository(backend, rows)
        val session = ScopedPropositionReader.open(repository, scope).snapshot(asOf, 0.7)
        assertEquals(listOf("b", "a"), session.query(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 2).map { it.id })
        assertEquals(listOf("b"), session.keywordOverlap(listOf("alpha"), 1).map { it.id })
        assertEquals(2L, session.count())
        assertEquals(listOf("a", "b"), session.query(ReadOrder.NONE, 2).map { it.id })
        assertEquals(emptyList<Proposition>(), session.query(ReadOrder.NONE, 0))
        assertEquals(2L, session.count())
        assertEquals(0L, (repository as ContextSetReadCapable).count(ContextReadScope.ofValues(listOf("D")), spec))
        val narrowed = session.excluding(listOf(PropositionKey("B", "b")))
        assertEquals(listOf("a"), narrowed.query(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 1).map { it.id })
        assertEquals(listOf("a"), narrowed.keywordOverlap(listOf("alpha"), 1).map { it.id })
        assertEquals(1L, narrowed.count())
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `included keys intersect before all candidates and empty means deny all`(backend: Backend) {
        val repository = repository(backend, listOf(row("a", "A"), row("b", "B", 0.9), row("c", "C", 1.0)))
        val session = ScopedPropositionReader.open(repository, scope).snapshot(asOf, 0.7)
        val included = session.includingOnly(listOf(PropositionKey("A", "a"), PropositionKey("B", "b")))
            .includingOnly(listOf(PropositionKey("A", "a")))
        assertEquals(listOf("a"), included.query(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 1).map { it.id })
        assertEquals(listOf("a"), included.keywordOverlap(listOf("alpha"), 1).map { it.id })
        assertEquals(1L, included.count())
        for (empty in listOf(session.includingOnly(emptyList()), included.excluding(listOf(PropositionKey("A", "a"))))) {
            assertEquals(emptyList<Proposition>(), empty.query(ReadOrder.NONE, 10))
            assertEquals(emptyList<Proposition>(), empty.keywordOverlap(listOf("alpha"), 10))
            assertEquals(0L, empty.count())
        }
        val backendReads = repository as ContextSetReadCapable
        val wrongContext = spec.includingOnly(scope, listOf(PropositionKey("B", "a")))
        assertEquals(0L, backendReads.count(scope, wrongContext))
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `every membership filter matches shared oracle in all three operations`(backend: Backend) {
        val rows = listOf(
            row("a", "A", 0.95).copy(
                mentions = listOf(EntityMention("x", "Thing", "x"), EntityMention("y", "Thing", "y")),
                level = 3, sourceIds = listOf("source"), importance = 0.9, reinforceCount = 4, pinned = true,
                metadata = mapOf(DiceMetadataKeys.TRUST_SCORE to 0.9),
            ),
            row("b", "B", 0.7).copy(
                mentions = listOf(EntityMention("y", "Thing", "y")),
                created = asOf, contentRevised = asOf, metadataRevised = asOf, lastAccessed = asOf,
                status = PropositionStatus.SUPERSEDED, level = 1, sourceIds = listOf("source"), importance = 0.3,
                metadata = mapOf(DiceMetadataKeys.TRUST_SCORE to 0.2),
            ),
            row("d", "B", 0.95).copy(decay = 0.8, metadataRevised = asOf),
            row("unscored", "A", 0.4),
            row("c", "C", 1.0).copy(level = 3, sourceIds = listOf("source"), importance = 1.0, pinned = true),
        )
        val reads = repository(backend, rows) as ContextSetReadCapable
        val base = PropositionQuery()
        val queries = listOf(
            base, base.copy(contextId = ContextId("A")), base.copy(entityId = "x"),
            base.copy(anyEntityIds = listOf("x", "y")), base.copy(anyEntityIds = emptyList()),
            base.copy(allEntityIds = listOf("x", "y")), base.copy(allEntityIds = emptyList()),
            base.copy(anyEntityIds = listOf("x"), allEntityIds = listOf("y")),
            base.copy(statuses = setOf(PropositionStatus.ACTIVE)), base.copy(statuses = emptySet()),
            base.copy(minLevel = 2), base.copy(maxLevel = 1),
            base.copy(createdAfter = asOf), base.copy(createdBefore = old),
            base.copy(revisedAfter = asOf), base.copy(revisedBefore = old),
            base.copy(accessedAfter = asOf), base.copy(accessedBefore = old),
            base.copy(minConfidence = 0.9), base.copy(minEffectiveConfidence = 0.8, decayK = 0.2),
            base.copy(belowEffectiveConfidence = 0.7, decayK = 0.2),
            base.copy(minEffectiveConfidence = 0.7, belowEffectiveConfidence = 0.8, decayK = 0.0),
            base.copy(minImportance = 0.8), base.copy(minReinforceCount = 1),
            base.copy(minTrustScore = 0.7), base.copy(pinned = true), base.copy(pinned = false),
        )
        for (query in queries) {
            val expected = rows.filter { it.contextId.value in scope.contextIdValues && query.matchesFilters(it, asOf) }
                .sortedWith(keyOrder).map { it.id }
            val restricted = spec.restrict(scope, query.copy(orderBy = PropositionQuery.OrderBy.CREATED_DESC, limit = 1))
            assertEquals(expected, reads.query(scope, restricted, ReadOrder.NONE, 20).map { it.id }, query.toString())
            assertEquals(expected.size.toLong(), reads.count(scope, restricted), query.toString())
            assertEquals(expected.toSet(), reads.keywordOverlap(scope, restricted, listOf("alpha"), 20).map { it.id }.toSet(), query.toString())
        }
        // The oracle's inclusive lower/upper timestamp bounds and strict confidence upper bound are observable.
        assertEquals(1L, reads.count(scope, spec.restrict(scope, base.copy(createdAfter = asOf, createdBefore = asOf))))
        assertEquals(2L, reads.count(scope, spec.restrict(scope, base.copy(belowEffectiveConfidence = 0.95, decayK = 0.0))))
        assertEquals(3L, reads.count(scope, spec.restrict(scope, base.copy(minTrustScore = 0.7))))
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `all fragments are retained and cannot replace earlier membership`(backend: Backend) {
        val rows = listOf(row("a", "A", 0.7), row("b", "B", 0.9), row("c", "C", 1.0))
        val reads = repository(backend, rows) as ContextSetReadCapable
        val restricted = spec.restrict(scope, PropositionQuery(contextId = ContextId("A")))
            .restrict(scope, PropositionQuery(minConfidence = 0.8))
        assertEquals(0L, reads.count(scope, restricted))
        assertTrue(reads.query(scope, restricted, ReadOrder.NONE, 10).isEmpty())
        assertTrue(reads.keywordOverlap(scope, restricted, listOf("alpha"), 10).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `every order is deterministic with context and id tie breaks`(backend: Backend) {
        val rows = listOf(
            row("a2", "A", 0.8), row("a1", "A", 0.8), row("b", "B", 0.8),
            row("best", "B", 0.9).copy(created = asOf, metadataRevised = asOf, lastAccessed = asOf, importance = 0.9, reinforceCount = 3),
            row("c", "C", 1.0).copy(created = asOf.plusSeconds(1), importance = 1.0, reinforceCount = 10),
        )
        val reads = repository(backend, rows) as ContextSetReadCapable
        for (order in ReadOrder.entries) {
            val expected = if (order == ReadOrder.NONE) listOf("a1", "a2", "b", "best") else listOf("best", "a1", "a2", "b")
            assertEquals(expected, reads.query(scope, spec, order, 10).map { it.id }, order.name)
            assertEquals(expected.take(2), reads.query(scope, spec, order, 2).map { it.id }, order.name)
        }
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `keyword uses global distinct normalized overlap then effective confidence and keys`(backend: Backend) {
        val rows = listOf(
            row("a1", "A", 0.7).copy(text = "ALPHA BETA"),
            row("a2", "A", 0.7).copy(text = "alpha beta"),
            row("b", "B", 0.95).copy(text = "alpha"),
            row("beta", "A", 1.0).copy(text = "beta"),
            row("decayed", "B", 0.99).copy(text = "alpha beta", decay = 1.0),
            row("none", "A", 1.0).copy(text = "unrelated"),
            row("c", "C", 1.0).copy(text = "alpha beta"),
        )
        val reads = repository(backend, rows) as ContextSetReadCapable
        // Repeated ALPHA must not outrank the two distinct overlaps; old raw confidence must not win a tie.
        val tokens = listOf(" ALPHA ", "alpha", "BETA", "", "   ")
        assertEquals(listOf("a1", "a2", "decayed", "beta", "b"), reads.keywordOverlap(scope, spec, tokens, 10).map { it.id })
        assertEquals(listOf("a1", "a2"), reads.keywordOverlap(scope, spec, tokens, 2).map { it.id })
        assertTrue(reads.keywordOverlap(scope, spec, listOf(" ", ""), 10).isEmpty())
        assertTrue(reads.keywordOverlap(scope, spec, emptyList(), 10).isEmpty())
        assertTrue(reads.keywordOverlap(scope, spec, tokens, 0).isEmpty())
        val onlyB = spec.restrict(scope, PropositionQuery(contextId = ContextId("B")))
        assertEquals(listOf("decayed", "b"), reads.keywordOverlap(scope, onlyB, tokens, 2).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `captured effective instant and ranking decay honor temporal windows and retraction`(backend: Backend) {
        val rows = listOf(
            row("old", "A", 0.99).copy(decay = 0.8),
            row("fresh", "B", 0.8).copy(contentRevised = asOf, lastAccessed = asOf),
            row("closed", "A", 0.9).copy(decay = 1.0, temporal = TemporalMetadata(validFrom = old, validTo = asOf.plusSeconds(1))),
            row("expired", "B", 0.95).copy(temporal = TemporalMetadata(validFrom = old, validTo = asOf)),
            row("retracted", "A", 1.0).copy(temporal = TemporalMetadata(invalidatedAt = asOf)),
        )
        val reads = repository(backend, rows) as ContextSetReadCapable
        assertEquals(listOf("closed", "fresh", "old", "retracted", "expired"), reads.query(scope, spec, ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 10).map { it.id })
        assertEquals(listOf("closed", "fresh", "old", "retracted", "expired"), reads.keywordOverlap(scope, spec, listOf("alpha"), 10).map { it.id })
        assertEquals(2L, reads.count(scope, spec.restrict(scope, PropositionQuery(minEffectiveConfidence = 0.8))))
        assertEquals("old", reads.query(scope, ScopedReadSpec.at(old, 0.0).excluding(scope, listOf(PropositionKey("A", "retracted"), PropositionKey("B", "expired"))), ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 1).single().id)
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `empty resident store and nonmatching scope have no rows`(backend: Backend) {
        val reads = repository(backend, emptyList()) as ContextSetReadCapable
        assertEquals(0L, reads.count(scope, spec))
        assertTrue(reads.query(scope, spec, ReadOrder.NONE, 5).isEmpty())
        assertTrue(reads.keywordOverlap(scope, spec, listOf("alpha"), 5).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ContextReadScope.ofValues(emptyList()) }
    }

    @Test
    fun `JSON operations retain resident snapshot after file becomes unreadable`() {
        val path = directory.resolve("resident.json")
        val repository = JsonFilePropositionRepository(path)
        repository.save(row("a", "A"))
        val reloaded = JsonFilePropositionRepository(path)
        Files.writeString(path, "this is deliberately not JSON")
        assertEquals(listOf("a"), reloaded.query(scope, spec, ReadOrder.NONE, 2).map { it.id })
        assertEquals(1L, reloaded.count(scope, spec))
        assertEquals(listOf("a"), reloaded.keywordOverlap(scope, spec, listOf("alpha"), 2).map { it.id })
    }

    @Test
    fun `JSON reload preserves temporal evidence and accepts legacy computed current property`() {
        val path = directory.resolve("temporal.json")
        val temporal = TemporalMetadata(
            observedAt = old, validFrom = old, validTo = asOf.plusSeconds(1),
            invalidatedAt = asOf.plusSeconds(2), supersedes = listOf("previous"), contradicts = listOf("other"),
        )
        val original = row("dated", "A").copy(decay = 0.8, temporal = temporal)
        JsonFilePropositionRepository(path).save(original)
        val json = Files.readString(path)
        assertTrue(json.contains("\"temporal\":{"))
        Files.writeString(path, json.replace("\"temporal\":{", "\"temporal\":{\"isCurrent\":true,"))
        val reloaded = JsonFilePropositionRepository(path)
        assertEquals(temporal, reloaded.findById("dated")!!.temporal)
        assertEquals(original.effectiveConfidenceAt(asOf, 0.7), reloaded.findById("dated")!!.effectiveConfidenceAt(asOf, 0.7))
        assertEquals(listOf("dated"), reloaded.query(scope, spec, ReadOrder.EFFECTIVE_CONFIDENCE_DESC, 1).map { it.id })
    }

    @Test
    fun `keyword case normalization is independent of Turkish default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val reads = LocalScopedReadOperations { listOf(row("a", "A").copy(text = "INDIGO")) }
            assertEquals(listOf("a"), reads.keywordOverlap(scope, spec, listOf("INDIGO", "indigo"), 1).map { it.id })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `resident engine deduplicates composite keys without collapsing different contexts`() {
        val a = row("same", "A")
        val b = row("same", "B")
        val reads = LocalScopedReadOperations { listOf(a, a, b, row("c", "C", 1.0)) }
        assertEquals(2L, reads.count(scope, spec))
        assertEquals(listOf("A", "B"), reads.query(scope, spec, ReadOrder.NONE, 10).map { it.contextId.value })
        assertEquals(listOf("A", "B"), reads.keywordOverlap(scope, spec, listOf("alpha"), 10).map { it.contextId.value })
    }
}
