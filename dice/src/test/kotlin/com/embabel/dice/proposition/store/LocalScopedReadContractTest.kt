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
import com.embabel.dice.proposition.CodePointOrder
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ContextSetReadCapable
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedPropositionReader
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.matchesFilters
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

class LocalScopedReadContractTest {

    enum class Backend { MEMORY, JSON, JSON_RELOADED }

    @TempDir
    lateinit var directory: Path

    private val asOf = Instant.parse("2026-01-20T00:00:00Z")
    private val old = asOf.minusSeconds(10 * 86400L)
    private val scope = ContextReadScope.ofValues(listOf("B", "A", "A"))
    private val spec = ScopedReadSpec.of(scope)
    private val clock = ReadClock(asOf, 0.7)
    private val keyOrder = compareBy(CodePointOrder) { p: Proposition -> p.contextId.value }.thenBy(CodePointOrder) { it.id }

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
        assertEquals(listOf("b", "a"), session.query(PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, 2).map { it.id })
        assertEquals(listOf("b"), session.keywordOverlap(listOf("alpha"), 1).map { it.id })
        assertEquals(2L, session.count())
        assertEquals(listOf("a", "b"), session.query(PropositionQuery.OrderBy.NONE, 2).map { it.id })
        assertEquals(emptyList<Proposition>(), session.query(PropositionQuery.OrderBy.NONE, 0))
        assertEquals(2L, session.count())
        assertEquals(0L, (repository as ContextSetReadCapable).count(ScopedReadSpec.of(ContextReadScope.ofValues(listOf("D"))), clock))
        val narrowed = session.excluding(listOf(PropositionKey("B", "b")))
        assertEquals(listOf("a"), narrowed.query(PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, 1).map { it.id })
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
        assertEquals(listOf("a"), included.query(PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, 1).map { it.id })
        assertEquals(listOf("a"), included.keywordOverlap(listOf("alpha"), 1).map { it.id })
        assertEquals(1L, included.count())
        for (empty in listOf(session.includingOnly(emptyList()), included.excluding(listOf(PropositionKey("A", "a"))))) {
            assertEquals(emptyList<Proposition>(), empty.query(PropositionQuery.OrderBy.NONE, 10))
            assertEquals(emptyList<Proposition>(), empty.keywordOverlap(listOf("alpha"), 10))
            assertEquals(0L, empty.count())
        }
        val backendReads = repository as ContextSetReadCapable
        val wrongContext = spec.includingOnly(listOf(PropositionKey("B", "a")))
        assertEquals(0L, backendReads.count(wrongContext, clock))
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
            val restricted = spec.restrict(query)
            assertEquals(expected, reads.query(restricted, clock, PropositionQuery.OrderBy.NONE, 20).map { it.id }, query.toString())
            assertEquals(expected.size.toLong(), reads.count(restricted, clock), query.toString())
            assertEquals(expected.toSet(), reads.keywordOverlap(restricted, clock, listOf("alpha"), 20).map { it.id }.toSet(), query.toString())
        }
        // The oracle's inclusive lower/upper timestamp bounds and strict confidence upper bound are observable.
        assertEquals(1L, reads.count(spec.restrict(base.copy(createdAfter = asOf, createdBefore = asOf)), clock))
        assertEquals(2L, reads.count(spec.restrict(base.copy(belowEffectiveConfidence = 0.95, decayK = 0.0)), clock))
        assertEquals(3L, reads.count(spec.restrict(base.copy(minTrustScore = 0.7)), clock))
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `all fragments are retained and cannot replace earlier membership`(backend: Backend) {
        val rows = listOf(row("a", "A", 0.7), row("b", "B", 0.9), row("c", "C", 1.0))
        val reads = repository(backend, rows) as ContextSetReadCapable
        val restricted = spec.restrict(PropositionQuery(contextId = ContextId("A")))
            .restrict(PropositionQuery(minConfidence = 0.8))
        assertEquals(0L, reads.count(restricted, clock))
        assertTrue(reads.query(restricted, clock, PropositionQuery.OrderBy.NONE, 10).isEmpty())
        assertTrue(reads.keywordOverlap(restricted, clock, listOf("alpha"), 10).isEmpty())
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
        for (order in PropositionQuery.OrderBy.entries) {
            val expected = if (order == PropositionQuery.OrderBy.NONE) listOf("a1", "a2", "b", "best") else listOf("best", "a1", "a2", "b")
            assertEquals(expected, reads.query(spec, clock, order, 10).map { it.id }, order.name)
            assertEquals(expected.take(2), reads.query(spec, clock, order, 2).map { it.id }, order.name)
        }
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `E1 ties order by context then id in code point order`(backend: Backend) {
        // U+FF61 sorts before U+10000 by code point but after it in UTF-16, where U+10000 is U+D800 U+DC00.
        val reads = repository(backend, listOf(row("𐀀", "｡"), row("｡", "｡"), row("c1", "𐀀"), row("c2", "A"))) as ContextSetReadCapable
        val tied = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("A", "｡", "𐀀")))
        val expected = listOf("c2", "｡", "𐀀", "c1")   // UTF-16 would give c2, c1, 𐀀, ｡
        for (order in PropositionQuery.OrderBy.entries) {
            assertEquals(expected, reads.query(tied, clock, order, 10).map { it.id }, order.name)
            assertEquals(expected.take(2), reads.query(tied, clock, order, 2).map { it.id }, order.name)
        }
        assertEquals(expected.take(2), reads.keywordOverlap(tied, clock, listOf("alpha"), 2).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `composable query and scoped reads rank every order the same way`(backend: Backend) {
        fun day(n: Long) = old.plusSeconds(n * 86400L)
        val rows = listOf(
            row("p", "A", 0.95).copy(created = day(1), contentRevised = day(2), metadataRevised = day(2), lastAccessed = day(3), reinforceCount = 1, importance = 0.4),
            row("q", "B", 0.90).copy(created = day(4), contentRevised = day(1), metadataRevised = day(1), lastAccessed = day(2), reinforceCount = 3, importance = 0.2),
            row("r", "A", 0.85).copy(created = day(3), contentRevised = day(4), metadataRevised = day(4), lastAccessed = day(1), reinforceCount = 4, importance = 0.3),
            row("s", "B", 0.80).copy(created = day(2), contentRevised = day(3), metadataRevised = day(3), lastAccessed = day(4), reinforceCount = 2, importance = 0.9),
        )
        val expected = mapOf(
            PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC to listOf("p", "q", "r", "s"),
            PropositionQuery.OrderBy.CREATED_DESC to listOf("q", "r", "s", "p"),
            PropositionQuery.OrderBy.REVISED_DESC to listOf("r", "s", "p", "q"),
            PropositionQuery.OrderBy.LAST_ACCESSED_DESC to listOf("s", "p", "q", "r"),
            PropositionQuery.OrderBy.REINFORCE_COUNT_DESC to listOf("r", "q", "s", "p"),
            PropositionQuery.OrderBy.IMPORTANCE_DESC to listOf("s", "p", "r", "q"),
        )
        val repository = repository(backend, rows)
        val reads = repository as ContextSetReadCapable
        // Every ordering except NONE must be covered, or a new one could skip the shared comparator unseen.
        assertEquals(PropositionQuery.OrderBy.entries.toSet() - PropositionQuery.OrderBy.NONE, expected.keys)
        for ((order, ids) in expected) {
            val legacy = PropositionQuery(orderBy = order, effectiveConfidenceAsOf = asOf, decayK = 0.7)
            assertEquals(ids, repository.query(legacy).map { it.id }, order.name)
            assertEquals(ids, reads.query(spec, clock, order, 10).map { it.id }, order.name)
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
        assertEquals(listOf("a1", "a2", "decayed", "beta", "b"), reads.keywordOverlap(spec, clock, tokens, 10).map { it.id })
        assertEquals(listOf("a1", "a2"), reads.keywordOverlap(spec, clock, tokens, 2).map { it.id })
        assertTrue(reads.keywordOverlap(spec, clock, listOf(" ", ""), 10).isEmpty())
        assertTrue(reads.keywordOverlap(spec, clock, emptyList(), 10).isEmpty())
        assertTrue(reads.keywordOverlap(spec, clock, tokens, 0).isEmpty())
        val onlyB = spec.restrict(PropositionQuery(contextId = ContextId("B")))
        assertEquals(listOf("decayed", "b"), reads.keywordOverlap(onlyB, clock, tokens, 2).map { it.id })
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
        assertEquals(listOf("closed", "fresh", "old", "retracted", "expired"), reads.query(spec, clock, PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, 10).map { it.id })
        assertEquals(listOf("closed", "fresh", "old", "retracted", "expired"), reads.keywordOverlap(spec, clock, listOf("alpha"), 10).map { it.id })
        assertEquals(2L, reads.count(spec.restrict(PropositionQuery(minEffectiveConfidence = 0.8)), clock))
        assertEquals("old", reads.query(spec.excluding(listOf(PropositionKey("A", "retracted"), PropositionKey("B", "expired"))), ReadClock(old, 0.0), PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, 1).single().id)
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `a fragment without its own instant is evaluated at the read clock`(backend: Backend) {
        val rows = listOf(row("a", "A"), row("d", "B", 0.95).copy(decay = 0.8, contentRevised = asOf))
        val reads = repository(backend, rows) as ContextSetReadCapable
        val fragment = PropositionQuery(minEffectiveConfidence = 0.5)
        val atReadClock = rows.count { it.effectiveConfidenceAt(asOf, fragment.decayK) >= 0.5 }
        // The rows must pass the threshold differently now than at the read instant, or this test proves nothing.
        assertTrue(atReadClock != rows.count { it.effectiveConfidenceAt(Instant.now(), fragment.decayK) >= 0.5 })
        assertEquals(atReadClock.toLong(), reads.count(spec.restrict(fragment), clock))
    }

    @ParameterizedTest
    @EnumSource(Backend::class)
    fun `empty resident store has no rows`(backend: Backend) {
        val reads = repository(backend, emptyList()) as ContextSetReadCapable
        assertEquals(0L, reads.count(spec, clock))
        assertTrue(reads.query(spec, clock, PropositionQuery.OrderBy.NONE, 5).isEmpty())
        assertTrue(reads.keywordOverlap(spec, clock, listOf("alpha"), 5).isEmpty())
    }
}
