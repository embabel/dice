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
package com.embabel.dice.agent

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.tools.ToolProvider

class PolicyMemoryTest {
    @TempDir
    lateinit var directory: Path
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val scope = ContextReadScope.ofValues(listOf("private-owner-A", "private-owner-B"))
    private val stamp = ReadPolicyStamp("P1", "recall", "policy", "v1")
    private fun row(id: String, owner: String = "private-owner-A", text: String = "hidden stored $id") = Proposition(
        id = id, contextId = ContextId(owner), text = text, confidence = 0.99,
        created = now, contentRevised = now, metadataRevised = now,
        mentions = listOf(EntityMention("secret person", "Person", "hidden-entity")),
        metadata = mapOf("private-metadata" to "private-value", DiceMetadataKeys.TRUST_SCORE to 0.7),
        provenanceEntries = listOf(ProvenanceEntry(UriLocator("secret-location", "secret-descriptor"))),
    )
    private fun key(row: Proposition) = PropositionKey(row.contextId.value, row.id)
    private fun grant(row: Proposition, projection: RowProjection = RowProjection.text(ApprovedText.Literal("public summary")), embedding: Boolean = false) =
        RowGrant.of(key(row), projection, true, embedding)
    private fun plan(vararg tiers: PolicySearchTier) = PolicyMemoryPlan.listingOnly().withSearchTiers(tiers.toSet())
    private fun view(
        backend: Backend, grants: List<RowGrant>, policyStamp: ReadPolicyStamp = stamp,
        influence: HiddenFieldInfluence = HiddenFieldInfluence.STRICT,
        limits: ScopedReadLimits = ScopedReadLimits.DEFAULT,
    ) = PolicyReadView.open(ScopedPropositionReader.open(backend, scope, limits), RowPolicy.of(policyStamp, grants, influence))
    private fun memory(view: PolicyReadView, plan: PolicyMemoryPlan = PolicyMemoryPlan.listingOnly()) = PolicyMemory.from(view, plan)
    private fun output(memory: PolicyMemory, input: String = "{}") = (memory.call(input) as Tool.Result.Text).content
    private fun lines(text: String) = text.lines().filter { it.startsWith("- ") }
    private fun vectorProjection() = RowProjection.text(ApprovedText.Stored).withFields(setOf(
        MemoryField.EFFECTIVE_CONFIDENCE, MemoryField.OWNER, MemoryField.ID, MemoryField.SCORE, MemoryField.ENTITY_IDS,
    ))

    private class Backend : PropositionRepository by InMemoryPropositionRepository(), StrictPolicyReadCapable, ContextSetVectorSearchCapable {
        val rows = mutableListOf<Proposition>()
        val specs = mutableListOf<ScopedReadSpec>()
        var calls = 0
        var countCalls = 0
        var strictCalls = 0
        var ordinaryQueries = 0
        var vectorCalls = 0
        var touched = 0
        var poison: Proposition? = null
        var failure = false
        var vectorSupported = true
        var strictSupported = true
        override val supportsScopedVectors: Boolean get() = vectorSupported
        override val supportsStrictPolicyReads: Boolean get() = strictSupported
        override fun touchAccessed(ids: Collection<String>) { touched++; error("touch must never occur") }
        private fun eligible(scope: ContextReadScope, spec: ScopedReadSpec): List<Proposition> {
            calls++
            specs += spec
            if (failure) error("private-owner-C secret-backend-driver-detail")
            return rows.filter { row ->
                val key = PropositionKey(row.contextId.value, row.id)
                row.contextId.value in scope.contextIdValues && key !in spec.excludedKeys &&
                    (spec.includedKeys == null || key in spec.includedKeys) && spec.fragments.all { it.matchesFilters(row, spec.asOf) }
            }
        }
        override fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long {
            countCalls++
            return eligible(scope, spec).size.toLong()
        }
        override fun query(scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int): List<Proposition> {
            ordinaryQueries++
            val selected = eligible(scope, spec).let { rows ->
                if (order == ReadOrder.NONE) rows else rows.sortedByDescending { it.effectiveConfidenceAt(spec.asOf) }
            }
            return poison?.let { listOf(it) } ?: selected.take(limit)
        }
        override fun keywordOverlap(scope: ContextReadScope, spec: ScopedReadSpec, tokens: List<String>, limit: Int) =
            eligible(scope, spec).filter { row -> tokens.any { it in row.text.lowercase(Locale.ROOT) } }.take(limit)
        override fun findSimilarWithScores(
            request: TextSimilaritySearchRequest, scope: ContextReadScope, spec: ScopedReadSpec, budget: ExactVectorBudget,
        ): List<SimilarityResult<Proposition>> {
            vectorCalls++
            val selected = eligible(scope, spec)
            return (poison?.let { listOf(it) } ?: selected).take(request.topK).map { SimilarityResult(it, 1.0) }
        }
        override fun queryByGrantOrder(
            scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>, limit: Int, timeout: Duration,
        ): List<Proposition> {
            strictCalls++
            StrictReadRow.validateRequest(scope, spec, rows, limit, timeout)
            val order = rows.associate { it.key to it.ordinal }
            val selected = eligible(scope, spec.includingOnly(scope, order.keys))
                .sortedBy { order.getValue(PropositionKey(it.contextId.value, it.id)) }.take(limit)
            return poison?.let { listOf(it) } ?: selected
        }
        override fun keywordByApprovedText(
            scope: ContextReadScope, spec: ScopedReadSpec, rows: List<StrictReadRow>, tokens: List<String>, limit: Int, timeout: Duration,
        ): List<Proposition> {
            strictCalls++
            StrictReadRow.validateRequest(scope, spec, rows, limit, timeout)
            val order = rows.associateBy { it.key }
            val selected = eligible(scope, spec.includingOnly(scope, order.keys)).mapNotNull { row ->
                val grant = order.getValue(PropositionKey(row.contextId.value, row.id))
                val approved = when (val text = grant.text) {
                    ApprovedSearchText.Omitted -> return@mapNotNull null
                    ApprovedSearchText.Stored -> row.text
                    is ApprovedSearchText.Literal -> text.value
                }
                val score = tokens.distinct().count { it in approved.lowercase(Locale.ROOT) }
                if (score == 0) null else Triple(row, score, grant.ordinal)
            }.sortedWith(compareByDescending<Triple<Proposition, Int, Int>> { it.second }.thenBy { it.third })
                .take(limit).map { it.first }
            return poison?.let { listOf(it) } ?: selected
        }
    }

    @Test
    fun `strict list count and direct key expose only approved DTOs in grant order without touches`() {
        val a = row("private-id-a")
        val b = row("private-id-b", "private-owner-B")
        val backend = Backend().apply { rows += listOf(a, b, row("decoy", "private-owner-C"), row("ungranted")) }
        val view = view(backend, listOf(grant(b, RowProjection.text(ApprovedText.Literal("second approved"))), grant(a)))
        val session = view.snapshot(now, 2.0)
        assertEquals(2L, session.count())
        assertEquals(listOf("second approved", "public summary"), session.list(2).map { it.text })
        assertEquals("public summary", session.get(key(a))!!.text)
        val before = backend.calls
        assertNull(session.get(PropositionKey("private-owner-B", "ungranted")))
        assertEquals(before, backend.calls)
        assertThrows<ScopedReadException> { session.get(PropositionKey("private-owner-C", "decoy")) }
        val text = output(memory(view))
        listOf("hidden stored", "private-owner", "private-id", "secret", "private-metadata", "private-value", "confidence", "score").forEach {
            assertFalse(text.contains(it), it)
        }
        assertEquals(2, lines(text).size)
        assertTrue(backend.specs.all { it.includedKeys == setOf(key(a), key(b)) || it.includedKeys == setOf(key(a)) })
        assertEquals(0, backend.touched)
        assertEquals(1, backend.ordinaryQueries)
    }

    @Test
    fun `listing adds no active status or confidence floor and respects cumulative host filters`() {
        val stale = row("stale").copy(status = PropositionStatus.STALE, confidence = 0.01)
        val backend = Backend().apply { rows += stale }
        assertEquals(1, lines(output(memory(view(backend, listOf(grant(stale)))))).size)
        val allowed = grant(stale, RowProjection.text(ApprovedText.Literal("approved")).withFields(setOf(MemoryField.STATUS, MemoryField.RAW_CONFIDENCE)))
        val restricted = view(backend, listOf(allowed)).restrict(PropositionQuery(statuses = setOf(PropositionStatus.STALE)))
            .restrict(PropositionQuery(minConfidence = 0.5))
        assertEquals(0L, restricted.snapshot(now, 2.0).count())
        assertTrue(output(memory(restricted)).contains("No additional"))
    }

    @Test
    fun `strict criteria dependencies fail before count list get or eager IO`() {
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val base = ScopedPropositionReader.open(backend, scope)
        val queries = listOf(
            PropositionQuery(statuses = setOf(PropositionStatus.ACTIVE)), PropositionQuery(minConfidence = 0.1),
            PropositionQuery(minEffectiveConfidence = 0.1), PropositionQuery(minTrustScore = 0.1),
            PropositionQuery(anyEntityIds = listOf("hidden-entity")), PropositionQuery(createdAfter = now.minusSeconds(1)),
            PropositionQuery(revisedAfter = now.minusSeconds(1)), PropositionQuery(accessedAfter = now.minusSeconds(1)),
            PropositionQuery(minLevel = 0), PropositionQuery(minImportance = 0.1),
            PropositionQuery(minReinforceCount = 0), PropositionQuery(pinned = false),
        )
        queries.forEach { criteria ->
            val view = PolicyReadView.open(base.restrict(criteria), RowPolicy.of(stamp, listOf(grant(a))))
            val failure = assertThrows<ScopedReadException> { view.snapshot(now, 2.0).count() }
            assertEquals("INVALID_SCOPED_READ", failure.code)
            assertNull(failure.cause)
            assertThrows<ScopedReadException> { memory(view) }
        }
        assertEquals(0, backend.calls)
    }

    @Test
    fun `derived metric permissions never release raw confidence or metadata`() {
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val projection = RowProjection.text(ApprovedText.Omitted).withFields(setOf(MemoryField.EFFECTIVE_CONFIDENCE, MemoryField.TRUST_SCORE))
        val view = view(backend, listOf(grant(a, projection)))
            .restrict(PropositionQuery(minEffectiveConfidence = 0.0, minTrustScore = 0.1))
        val dto = view.snapshot(now, 2.0).list(1).single()
        assertEquals(0.99, dto.effectiveConfidence)
        assertEquals(0.7, dto.trustScore)
        assertNull(dto.confidence)
        assertTrue(dto.metadata.isEmpty())
        val text = output(memory(view))
        assertTrue(text.contains("effectiveConfidence"))
        assertFalse(text.contains("private-value"))
        assertFalse(text.contains("\"confidence\""))
        assertThrows<ScopedReadException> { memory(view.restrict(PropositionQuery(minConfidence = 0.0))) }
    }

    @Test
    fun `literal keyword searches use approved text and ties use public grant order`() {
        val a = row("first-hidden", text = "hidden pineapple")
        val b = row("second-hidden", "private-owner-B", text = "hidden mango")
        val backend = Backend().apply { rows += listOf(a, b, row("hidden-decoy", text = "mango")) }
        val grants = listOf(grant(b, RowProjection.text(ApprovedText.Literal("PINEAPPLE public"))), grant(a, RowProjection.text(ApprovedText.Literal("pineapple summary"))))
        val memory = memory(view(backend, grants), plan(PolicySearchTier.KEYWORD))
        val result = output(memory, """{"query":"pineapple","limit":2}""")
        assertEquals(2, lines(result).size)
        assertTrue(lines(result).first().contains("PINEAPPLE public"))
        assertFalse(result.contains("hidden"))
        assertTrue(output(memory, """{"query":"mango"}""").contains("No additional"))
        assertEquals(0, backend.ordinaryQueries)
        assertTrue(memory.notes().contains("keyword"))
    }

    @Test
    fun `unsupported and hidden tier dependencies are rejected before all IO`() {
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val view = view(backend, listOf(grant(a)))
        assertThrows<ScopedReadException> { memory(view, plan(PolicySearchTier.VECTOR)) }
        assertThrows<ScopedReadException> { memory(view, plan(PolicySearchTier.RELATED)) }
        val omitted = view(backend, listOf(grant(a, RowProjection.text(ApprovedText.Omitted))))
        assertThrows<ScopedReadException> { memory(omitted, plan(PolicySearchTier.KEYWORD)) }
        assertThrows<ScopedReadException> { memory(view).withEagerTopicSearch(1) }
        assertEquals("SCOPED_READ_UNSUPPORTED", assertThrows<ScopedReadException> {
            output(memory(view), """{"query":"something"}""")
        }.code)
        val full = view(backend, listOf(grant(a, vectorProjection(), true)))
        backend.vectorSupported = false
        assertEquals("SCOPED_VECTOR_UNSUPPORTED", assertThrows<ScopedReadException> { memory(full, plan(PolicySearchTier.VECTOR)) }.code)
        backend.strictSupported = false
        assertThrows<ScopedReadException> { memory(view) }
        assertEquals(0, backend.calls)
    }

    @Test
    fun `blank eager vector requests and corrupt approved text results fail without a fallback`() {
        val a = row("a", text = "private pineapple")
        val backend = Backend().apply { rows += a }
        val vector = memory(view(backend, listOf(grant(a, vectorProjection(), true))), plan(PolicySearchTier.VECTOR))
        assertThrows<ScopedReadException> { vector.withEagerTextSearch(TextSimilaritySearchRequest(" ", 0.0, 1)) }
        assertThrows<ScopedReadException> { vector.withTopic(" ").withEagerTopicSearch(1) }
        assertEquals(0, backend.calls)
        val keyword = memory(view(backend, listOf(grant(a))), plan(PolicySearchTier.KEYWORD))
        backend.poison = a
        assertEquals("SCOPED_READ_RESULT_INVALID", assertThrows<ScopedReadException> {
            output(keyword, """{"query":"pineapple"}""")
        }.code)
    }

    @Test
    fun `stored vector permission requires all score and tie dependencies and cannot be bypassed by SCORE`() {
        val a = row("a", text = "approved stored")
        val backend = Backend().apply { rows += a }
        listOf(setOf(MemoryField.SCORE), setOf(MemoryField.EFFECTIVE_CONFIDENCE), setOf(MemoryField.OWNER, MemoryField.ID)).forEach { fields ->
            val projection = RowProjection.text(ApprovedText.Stored).withFields(fields)
            assertThrows<ScopedReadException> { memory(view(backend, listOf(grant(a, projection, true))), plan(PolicySearchTier.VECTOR)) }
        }
        assertThrows<ScopedReadException> {
            memory(view(backend, listOf(grant(a, vectorProjection(), false))), plan(PolicySearchTier.VECTOR))
        }
        assertEquals(0, backend.calls)
        val noScore = vectorProjection().withFields(vectorProjection().fields - MemoryField.SCORE)
        val withoutScore = output(memory(view(backend, listOf(grant(a, noScore, true))), plan(PolicySearchTier.VECTOR)), """{"query":"approved"}""")
        assertTrue(withoutScore.contains("approved stored"))
        assertFalse(withoutScore.contains("\"score\""))
        val selected = output(memory(view(backend, listOf(grant(a, vectorProjection(), true))), plan(PolicySearchTier.VECTOR)), """{"query":"approved"}""")
        assertTrue(selected.contains("approved stored"))
        assertTrue(selected.contains("\"score\":1.0"))
        assertFalse(selected.contains("\"confidence\""))
        assertFalse(selected.contains("private-value"))
    }

    @Test
    fun `related seeds come only from projected approved handles and remain grant bound`() {
        val seed = row("seed", text = "approved seed")
        val related = row("related", text = "approved unrelated")
        val hidden = row("not-granted", text = "seed")
        val backend = Backend().apply { rows += listOf(seed, related, hidden) }
        val projection = RowProjection.text(ApprovedText.Stored).withFields(setOf(MemoryField.ENTITY_IDS))
        val view = view(backend, listOf(grant(seed, projection), grant(related, projection)))
        val result = output(memory(view, plan(PolicySearchTier.KEYWORD, PolicySearchTier.RELATED)), """{"query":"seed","limit":2}""")
        assertEquals(2, lines(result).size)
        assertFalse(result.contains("not-granted"))
        assertTrue(backend.specs.last().fragments.any { it.anyEntityIds == listOf("hidden-entity") })
        assertTrue(backend.specs.all { it.includedKeys == setOf(key(seed), key(related)) })
    }

    @Test
    fun `empty grants have no backend or provenance IO on every entry point`() {
        val backend = Backend().apply { rows += row("hidden") }
        val empty = view(backend, emptyList())
        val session = empty.snapshot(now, 2.0)
        assertEquals(0L, session.count())
        assertTrue(session.list(1).isEmpty())
        assertNull(session.get(PropositionKey("private-owner-A", "hidden")))
        val memory = memory(empty, plan(*PolicySearchTier.entries.toTypedArray())).withEagerQuery(PropositionQuery())
            .withEagerTopicSearch(1).withEagerTextSearch(TextSimilaritySearchRequest("query", 0.0, 1))
            .withProvenance { _, _ -> error("must never resolve") }
        assertTrue(memory.contribution().contains("0 memories available"))
        assertTrue(output(memory, """{"query":"anything"}""").contains("No additional"))
        assertTrue(output(memory).contains("No additional"))
        assertEquals(0, backend.calls)
        assertEquals(0, backend.vectorCalls)
    }

    @Test
    fun `probe capacity is preflighted before count eager or query and never silently shrinks pools`() {
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val limits = ScopedReadLimits(5, 32, 5, ExactVectorBudget.DEFAULT)
        val memory = memory(view(backend, listOf(grant(a)), limits = limits)).withEagerQuery(PropositionQuery())
        assertThrows<ScopedReadException> { output(memory, """{"limit":1}""") }
        assertThrows<ScopedReadException> { memory.contribution() }
        assertEquals(0, backend.calls)
        assertEquals(0, backend.countCalls)
    }

    @Test
    fun `hidden revision inputs reject real view and memory reads before backend access`() {
        val a = row("a")
        val revisionFields = setOf(MemoryField.CONTENT_REVISED, MemoryField.METADATA_REVISED)
        val criteria = listOf(
            PropositionQuery(revisedAfter = now), PropositionQuery(revisedBefore = now),
            PropositionQuery(revisedAfter = now, revisedBefore = now),
        )
        revisionFields.forEach { omitted ->
            criteria.forEach { query ->
                val backend = Backend().apply { rows += a }
                val projection = RowProjection.text(ApprovedText.Literal("public summary"))
                    .withFields(revisionFields - omitted)
                val base = view(backend, listOf(grant(a, projection)))
                val restricted = base.restrict(query)
                val reads = listOf<() -> Any?>(
                    { restricted.snapshot(now, 2.0).count() },
                    { restricted.snapshot(now, 2.0).list(1) },
                    { restricted.snapshot(now, 2.0).get(key(a)) },
                    { output(memory(restricted, plan(*PolicySearchTier.entries.toTypedArray())), """{"query":"public"}""") },
                    { memory(base).withEagerQuery(query).contribution() },
                    { memory(base).withEagerQuery(query.copy(orderBy = PropositionQuery.OrderBy.REVISED_DESC)) },
                )
                reads.forEach { read ->
                    val failure = assertThrows<ScopedReadException> { read() }
                    assertEquals("INVALID_SCOPED_READ", failure.code)
                    assertNull(failure.cause)
                    assertFalse(failure.toString().contains("hidden"))
                }
                assertEquals(0, backend.calls)
                assertEquals(0, backend.countCalls)
                assertEquals(0, backend.strictCalls)
                assertEquals(0, backend.ordinaryQueries)
                assertEquals(0, backend.vectorCalls)
                assertTrue(backend.specs.isEmpty())
            }
        }
    }

    @Test
    fun `approved revision inputs preserve inclusive last touched boundaries in view and memory`() {
        val revisions = listOf(
            row("earlier").copy(contentRevised = now.minusSeconds(1), metadataRevised = now.minusSeconds(2)),
            row("content-boundary").copy(metadataRevised = now.minusSeconds(1)),
            row("metadata-boundary").copy(contentRevised = now.minusSeconds(1)),
            row("content-later").copy(contentRevised = now.plusSeconds(1)),
            row("metadata-later").copy(metadataRevised = now.plusSeconds(1)),
        )
        val cases = listOf(
            PropositionQuery(revisedAfter = now) to revisions.drop(1),
            PropositionQuery(revisedBefore = now) to revisions.take(3),
            PropositionQuery(revisedAfter = now, revisedBefore = now) to revisions.subList(1, 3),
        )
        HiddenFieldInfluence.entries.forEach { influence ->
            val backend = Backend().apply { rows += revisions }
            val grants = revisions.map { row ->
                val fields = if (influence == HiddenFieldInfluence.STRICT) {
                    setOf(MemoryField.CONTENT_REVISED, MemoryField.METADATA_REVISED)
                } else emptySet()
                grant(row, RowProjection.text(ApprovedText.Literal(row.id)).withFields(fields))
            }
            val base = view(backend, grants, influence = influence)
            cases.forEach { (query, expected) ->
                val restricted = base.restrict(query)
                val session = restricted.snapshot(now, 2.0)
                assertEquals(expected.size.toLong(), session.count())
                assertEquals(expected.map { it.id }, session.list(5).map { it.text })
                revisions.forEach { row -> assertEquals(row.id.takeIf { row in expected }, session.get(key(row))?.text) }
                assertEquals(expected.size, lines(output(memory(restricted))).size)
                assertEquals(expected.size, lines(memory(base).withEagerQuery(query).contribution()).size)
            }
            assertTrue(backend.calls > 0)
        }
    }

    @Test
    fun `approved metadata revision changes membership while fixed content revision stays below after bound`() {
        val old = row("a").copy(contentRevised = now.minusSeconds(60), metadataRevised = now.minusSeconds(60))
        val backend = Backend().apply { rows += old }
        val projection = RowProjection.text(ApprovedText.Literal("public summary"))
            .withFields(setOf(MemoryField.CONTENT_REVISED, MemoryField.METADATA_REVISED))
        val restricted = view(backend, listOf(grant(old, projection))).restrict(PropositionQuery(revisedAfter = now))
        assertEquals(0L, restricted.snapshot(now, 2.0).count())
        assertTrue(restricted.snapshot(now, 2.0).list(1).isEmpty())
        backend.rows[0] = old.copy(metadataRevised = now)
        val session = restricted.snapshot(now, 2.0)
        assertEquals(1L, session.count())
        assertEquals("public summary", session.list(1).single().text)
        assertEquals("public summary", session.get(key(old))?.text)
        assertEquals(1, lines(output(memory(restricted, plan(PolicySearchTier.KEYWORD)), """{"query":"public"}""")).size)
    }

    @Test
    fun `permissive revised ordering follows both actual revision inputs without field grants`() {
        val metadataLatest = row("metadata-latest").copy(metadataRevised = now.plusSeconds(2))
        val contentNext = row("content-next").copy(contentRevised = now.plusSeconds(1))
        val oldest = row("oldest")
        val backend = InMemoryPropositionRepository()
        listOf(oldest, contentNext, metadataLatest).forEach(backend::save)
        val grants = listOf(oldest, contentNext, metadataLatest).map { grant(it, RowProjection.text(ApprovedText.Literal(it.id))) }
        val policy = RowPolicy.of(stamp, grants, HiddenFieldInfluence.ALLOW_SEARCH_INFLUENCE)
        val view = PolicyReadView.open(ScopedPropositionReader.open(backend, scope), policy)
        val selected = view.snapshot(now, 2.0).eagerList(PropositionQuery(orderBy = PropositionQuery.OrderBy.REVISED_DESC), 3)
        assertEquals(listOf("metadata-latest", "content-next", "oldest"), selected.map { it.value.text })
        val rendered = memory(view).withEagerQuery(PropositionQuery(orderBy = PropositionQuery.OrderBy.REVISED_DESC)).contribution()
        assertTrue(rendered.indexOf("metadata-latest") < rendered.indexOf("content-next"))
        assertTrue(rendered.indexOf("content-next") < rendered.indexOf("oldest"))
    }

    @Test
    fun `strict nonordinal eager ordering fails preflight even with all fields approved`() {
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val view = view(backend, listOf(grant(a, RowProjection.text(ApprovedText.Stored).withFields(MemoryField.entries.toSet()))))
        val memory = memory(view)
        assertEquals("SCOPED_READ_UNSUPPORTED", assertThrows<ScopedReadException> {
            memory.withEagerQuery(PropositionQuery(orderBy = PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC))
        }.code)
        assertEquals(0, backend.calls)
    }

    @Test
    fun `allow influence retains ordinary ordered eager selection without output permission escalation`() {
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val view = view(backend, listOf(grant(a)), influence = HiddenFieldInfluence.ALLOW_SEARCH_INFLUENCE)
        val result = memory(view).withEagerQuery(PropositionQuery(orderBy = PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC)).contribution()
        assertTrue(result.contains("public summary"))
        assertFalse(result.contains("private-owner"))
        assertFalse(result.contains("hidden stored"))
        assertEquals(1, backend.ordinaryQueries)
    }

    @Test
    fun `future revocation principal and same-version grant changes never inherit eager state`() {
        val a = row("private-a")
        val derived = row("private-derived").copy(level = 1, sourceIds = listOf(a.id))
        val b = row("private-b", "private-owner-B")
        val backend = Backend().apply { rows += listOf(a, derived, b) }
        val v1 = memory(view(backend, listOf(grant(a), grant(derived)))).withEagerQuery(PropositionQuery())
        val old = v1.contribution()
        assertEquals(2, lines(old).size)
        assertTrue(output(v1).contains("No additional"))
        val v2 = memory(view(backend, listOf(grant(b, RowProjection.text(ApprovedText.Literal("v2 approved")))), stamp.copy(version = "v2")))
            .withEagerQuery(PropositionQuery())
        assertTrue(v2.contribution().contains("v2 approved"))
        assertEquals(1, lines(v2.contribution()).size)
        assertEquals(1L, view(backend, listOf(grant(b)), stamp.copy(version = "v2")).snapshot(now, 2.0).count())
        val principal2 = memory(view(backend, listOf(grant(b, RowProjection.text(ApprovedText.Literal("P2 only")))), stamp.copy(principal = "P2")))
            .withEagerQuery(PropositionQuery())
        assertTrue(principal2.contribution().contains("P2 only"))
        val changed = memory(view(backend, listOf(grant(b, RowProjection.text(ApprovedText.Literal("same version changed"))))))
            .withEagerQuery(PropositionQuery())
        assertTrue(changed.contribution().contains("same version changed"))
        assertEquals(old, v1.contribution())
        assertEquals(2, lines(output(v1.withTopic("fresh"))).size)
        assertEquals(1, lines(output(memory(view(backend, listOf(grant(b)))))).size)
    }

    @Test
    fun `eager success caches count keys and rendering withers and retries get fresh publication`() {
        val a = row("a")
        val b = row("b", "private-owner-B")
        val backend = Backend().apply { rows += listOf(a, b) }
        val view = view(backend, listOf(grant(a), grant(b)))
        val memory = memory(view).withEagerQuery(PropositionQuery(limit = 1))
        assertEquals(2, lines(output(memory)).size)
        val first = memory.contribution()
        assertEquals(1, lines(first).size)
        assertEquals(1, lines(output(memory)).size)
        assertEquals(first, memory.contribution())
        assertEquals(1, backend.countCalls)
        assertEquals(2, lines(output(memory.withTopic("fresh"))).size)
        assertTrue(backend.specs.last().excludedKeys.isEmpty())
        val failing = memory(view).withEagerQuery(PropositionQuery())
        backend.failure = true
        val error = assertThrows<ScopedReadException> { failing.contribution() }
        assertEquals("SCOPED_READ_FAILED", error.code)
        assertNull(error.cause)
        assertFalse(error.toString().contains("secret"))
        backend.failure = false
        assertEquals(2, lines(output(failing)).size)
        assertEquals(2, lines(failing.contribution()).size)
        assertTrue(output(failing).contains("No additional"))
    }

    @Test
    fun `eager modes share frozen clock use deterministic mode order and one global cap`() {
        val a = row("a", text = "approved vector")
        val b = row("b", "private-owner-B", text = "approved vector second")
        val backend = Backend().apply { rows += listOf(a, b) }
        val limits = ScopedReadLimits.DEFAULT.withMaxEager(1)
        val view = view(backend, listOf(grant(b, vectorProjection(), true), grant(a, vectorProjection(), true)), limits = limits)
        val memory = memory(view, plan(PolicySearchTier.VECTOR)).withTopic("approved")
            .withEagerTextSearch(TextSimilaritySearchRequest("text", 0.0, 1)).withEagerTopicSearch(2)
            .withEagerQuery(PropositionQuery(limit = 2))
        val result = memory.contribution()
        assertEquals(1, lines(result).size)
        assertTrue(lines(result).single().contains("approved vector\""))
        assertTrue(result.contains("2 memories available"))
        assertEquals(1, backend.specs.map { it.asOf }.toSet().size)
        assertTrue(output(memory).contains("approved vector second"))
        assertEquals(0, backend.touched)
    }

    @Test
    fun `provenance requests contain selected keys and permitted fields without ID fallback`() {
        val a = row("private-id")
        val b = row("private-other", "private-owner-B")
        val source = AllowedSource("approved-source-handle", descriptor = true, locator = false)
        val projection = RowProjection.text(ApprovedText.Literal("public")).withSources(listOf(source))
        val backend = Backend().apply { rows += listOf(a, b) }
        var requests: List<PolicyProvenanceRequest>? = null
        var receivedStamp: ReadPolicyStamp? = null
        val memory = memory(view(backend, listOf(grant(a, projection), grant(b))))
            .withProvenance { policyStamp, input ->
                requests = input; receivedStamp = policyStamp
                input.associate { it.key to ProjectedSource("approved citation", null) }
            }.withEagerQuery(PropositionQuery(limit = 1))
        val result = memory.contribution()
        assertEquals(stamp, receivedStamp)
        assertEquals(listOf(PolicyProvenanceRequest(PolicySourceKey(key(a), source.sourceId), true, false)), requests)
        assertThrows<UnsupportedOperationException> { (requests as MutableList).clear() }
        assertTrue(result.contains("approved citation"))
        listOf("private-id", "private-owner", "secret", source.sourceId).forEach { assertFalse(result.contains(it), it) }
        output(memory)
        assertEquals(1, requests!!.size)
        var resolved = 0
        val noSources = memory(view(backend, listOf(grant(a)))).withProvenance { _, _ -> resolved++; emptyMap() }
        assertFalse(output(noSources).contains("private-id"))
        assertEquals(0, resolved)
    }

    @Test
    fun `unsolicited provenance keys and fields abort eager publication and sanitized failures retry`() {
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val source = AllowedSource("public-handle", true, false)
        val view = view(backend, listOf(grant(a, RowProjection.text(ApprovedText.Literal("public")).withSources(listOf(source)))))
        var mode = 0
        val memory = memory(view).withEagerQuery(PropositionQuery()).withProvenance { _, requests ->
            when (mode) {
                0 -> mapOf(PolicySourceKey(PropositionKey("private-owner-C", "evil"), "evil") to ProjectedSource("hidden", null))
                1 -> requests.associate { it.key to ProjectedSource("public", "hidden locator") }
                2 -> error("secret provenance detail")
                else -> requests.associate { it.key to ProjectedSource("approved citation", null) }
            }
        }
        repeat(3) { phase ->
            mode = phase
            val error = assertThrows<ScopedReadException> { memory.contribution() }
            assertNull(error.cause)
            assertFalse(error.toString().contains("secret"))
            assertFalse(error.toString().contains("hidden"))
        }
        mode = 3
        assertEquals(1, lines(output(memory)).size)
        assertTrue(memory.contribution().contains("approved citation"))
        assertTrue(output(memory).contains("No additional"))
    }

    @Test
    fun `backend key injection aborts strict list keyword direct get and vector without partial output`() {
        val a = row("a", text = "approved")
        val evil = row("evil", "private-owner-C", "secret injection")
        val backend = Backend().apply { rows += a; poison = evil }
        val view = view(backend, listOf(grant(a, vectorProjection(), true)))
        val memory = memory(view, plan(PolicySearchTier.KEYWORD, PolicySearchTier.VECTOR))
        listOf("{}", """{"query":"approved"}""").forEach { input ->
            val failure = assertThrows<ScopedReadException> { output(memory, input) }
            assertEquals("SCOPED_READ_RESULT_INVALID", failure.code)
            assertFalse(failure.toString().contains("evil"))
        }
        assertThrows<ScopedReadException> { view.snapshot(now, 2.0).get(key(a)) }
        assertThrows<ScopedReadException> { memory.withEagerQuery(PropositionQuery()).contribution() }
        assertEquals(0, backend.countCalls)
    }

    @Test
    fun `only query and positive integral finite limit are model controlled`() {
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val memory = memory(view(backend, listOf(grant(a))))
        listOf("{", "[]", "null", "{} {}", """{"principal":"P2"}""", """{"policy":"v2"}""", """{"contextId":"C"}""",
            """{"query":1}""", """{"limit":0}""", """{"limit":-1}""", """{"limit":1.2}""", """{"limit":"1"}""").forEach {
            assertThrows<ScopedReadException> { output(memory, it) }
        }
        assertEquals(0, backend.calls)
        assertEquals(setOf("query", "limit"), memory.definition.inputSchema.parameters.map { it.name }.toSet())
        assertEquals(1, lines(output(memory, """{"limit":999999}""")).size)
    }

    @Test
    fun `Java named chains execute all configured methods with Kotlin block and direct parity`() {
        val source = directory.resolve("PolicyClient.java")
        Files.writeString(source, """
            import com.embabel.dice.agent.*;
            import com.embabel.dice.proposition.*;
            import com.embabel.common.core.types.TextSimilaritySearchRequest;
            public class PolicyClient {
                public static PolicyMemoryPlan plan() {
                    return PolicyMemoryPlan.configuring().withSearchTiers(java.util.Set.of(PolicySearchTier.VECTOR)).plan();
                }
                public static PolicyMemory.Configuring chain(PolicyReadView view) {
                    return PolicyMemory.configuring(view).withTopic("topic").withPlan(plan())
                        .withEagerQuery(PropositionQuery.againstContext("private-owner-A").withLimit(1))
                        .withEagerTopicSearch(1).withEagerTextSearch(TextSimilaritySearchRequest.create("query", 0.0, 1))
                        .withProvenance((stamp, requests) -> java.util.Collections.emptyMap());
                }
                public static PolicyMemory direct(PolicyReadView view) {
                    return PolicyMemory.from(view, plan()).withTopic("topic");
                }
            }
        """.trimIndent())
        val compiler = ToolProvider.getSystemJavaCompiler()
        val classpath = System.getProperty("surefire.test.class.path") ?: System.getProperty("java.class.path")
        assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", directory.toString(), source.toString()))
        val a = row("a", text = "approved")
        val backend = Backend().apply { rows += a }
        val view = view(backend, listOf(grant(a, vectorProjection(), true)))
        java.net.URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            val client = loader.loadClass("PolicyClient")
            val chain = client.getMethod("chain", PolicyReadView::class.java).invoke(null, view) as PolicyMemory.Configuring
            val javaMemory = chain.memory()
            val direct = memory(view, plan(PolicySearchTier.VECTOR)).withTopic("topic")
                .withEagerQuery(PropositionQuery.againstContext("private-owner-A").withLimit(1)).withEagerTopicSearch(1)
                .withEagerTextSearch(TextSimilaritySearchRequest("query", 0.0, 1)).withProvenance { _, _ -> emptyMap() }
            lateinit var config: PolicyMemory.Configuration
            val dsl = PolicyMemory(view) {
                config = this
                topic = "topic"
                plan = PolicyMemoryPlan { searchTiers = setOf(PolicySearchTier.VECTOR) }
                eagerQuery = PropositionQuery.againstContext("private-owner-A").withLimit(1)
                eagerTopicSearch = 1
                eagerTextSearch = TextSimilaritySearchRequest("query", 0.0, 1)
                provenance = PolicyProvenanceResolver { _, _ -> emptyMap() }
            }
            config.topic = "mutated"
            assertEquals(direct.contribution(), javaMemory.contribution())
            assertEquals(direct.contribution(), dsl.contribution())
            assertNotSame(javaMemory, chain.memory())
            assertEquals(1, lines(output(chain.memory())).size)
            assertTrue(output(javaMemory).contains("No additional"))
            assertEquals("Memories about topic", chain.memory().description)
            assertEquals("Memories about changed", chain.withTopic("changed").memory().description)
            assertInstanceOf(PolicyMemory::class.java, client.getMethod("direct", PolicyReadView::class.java).invoke(null, view))
        }
    }

    @Test
    fun `named and receiver configurations snapshot mutable inputs and terminal caches are separate`() {
        val tiers = linkedSetOf(PolicySearchTier.KEYWORD)
        val javaPlan = PolicyMemoryPlan.configuring().withSearchTiers(tiers)
        lateinit var config: PolicyMemoryPlan.Configuration
        val dsl = PolicyMemoryPlan { config = this; searchTiers = tiers }
        tiers.clear()
        config.searchTiers = emptySet()
        assertEquals(setOf(PolicySearchTier.KEYWORD), javaPlan.plan().searchTiers)
        assertEquals(javaPlan.plan().searchTiers, dsl.searchTiers)
        assertThrows<UnsupportedOperationException> { (dsl.searchTiers as MutableSet).clear() }
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val statuses = linkedSetOf(PropositionStatus.ACTIVE)
        val projection = RowProjection.text(ApprovedText.Literal("public")).withFields(setOf(MemoryField.STATUS))
        val chain = PolicyMemory.configuring(view(backend, listOf(grant(a, projection))))
            .withEagerQuery(PropositionQuery(statuses = statuses))
        statuses.clear()
        assertEquals(1, lines(chain.memory().contribution()).size)
        val base = PolicyMemory.configuring(view(backend, listOf(grant(a))))
        val invalid = base.withEagerTopicSearch(0)
        assertThrows<ScopedReadException> { invalid.memory() }
        assertNotNull(base.memory())
    }

    @Test
    fun `concurrent policy contributions publish one count and resolver result`() {
        val a = row("a")
        val backend = Backend().apply { rows += a }
        val projection = RowProjection.text(ApprovedText.Literal("public")).withSources(listOf(AllowedSource("source", true, false)))
        val resolutions = AtomicInteger()
        val memory = memory(view(backend, listOf(grant(a, projection)))).withEagerQuery(PropositionQuery())
            .withProvenance { _, requests -> resolutions.incrementAndGet(); requests.associate { it.key to ProjectedSource("citation", null) } }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..16).map { pool.submit<String> { start.await(); memory.contribution() } }
            start.countDown()
            val results = jobs.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.toSet().size)
            assertEquals(1, backend.countCalls)
            assertEquals(1, resolutions.get())
            assertTrue(output(memory).contains("No additional"))
        } finally { pool.shutdownNow() }
    }
}
