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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant

class PolicyMemoryTest {
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
        RowGrant.of(key(row), projection, embedding)
    private fun store(vararg rows: Proposition) = CountingStore(InMemoryPropositionRepository(embedder())).apply { rows.forEach { save(it) } }
    private fun view(
        store: CountingStore, grants: List<RowGrant>, policyStamp: ReadPolicyStamp = stamp,
        influence: HiddenFieldInfluence = HiddenFieldInfluence.STRICT,
        limits: ScopedReadLimits = ScopedReadLimits.DEFAULT,
        memoryLimits: MemoryLimits = MemoryLimits.DEFAULT,
    ) = PolicyReadView.open(ScopedPropositionReader.open(store, scope, limits), RowPolicy.of(policyStamp, grants, influence), memoryLimits)
    private fun memory(view: PolicyReadView, tiers: Set<PolicySearchTier> = emptySet()) = PolicyMemory.from(view, tiers)
    private fun output(memory: PolicyMemory, input: String = "{}") = (memory.call(input) as Tool.Result.Text).content
    private fun lines(text: String) = text.lines().filter { it.startsWith("- ") }
    private fun vectorProjection() = RowProjection.text(ApprovedText.Stored).withFields(setOf(
        MemoryField.EFFECTIVE_CONFIDENCE, MemoryField.OWNER, MemoryField.ID, MemoryField.SCORE, MemoryField.ENTITY_IDS,
    ))

    /**
     * Text that mentions "approved" points one way and everything else the other, so only approved rows score.
     * Text that mentions "near" sits halfway between, so it scores below an approved row but still matches.
     */
    private fun embedder(): EmbeddingService {
        val service = mock<EmbeddingService>()
        whenever(service.embed(any<String>())).thenAnswer {
            val text = it.getArgument<String>(0)
            when {
                "approved" in text -> floatArrayOf(1f, 0f)
                "near" in text -> floatArrayOf(1f, 1f)
                else -> floatArrayOf(-1f, 0f)
            }
        }
        return service
    }

    /**
     * The real in-memory store with a thin layer on top. It records every scoped read the memory makes,
     * fails any attempt to touch access times, and can misbehave on demand: put a row the read never
     * allowed at the front of each result, throw a driver error, or claim it lacks vector or strict reads.
     */
    private class CountingStore(val inner: InMemoryPropositionRepository) :
        PropositionRepository by inner, StrictPolicyReadCapable, ContextSetVectorSearchCapable {
        val calls = mutableListOf<String>()
        val specs = mutableListOf<ScopedReadSpec>()
        var injected: Proposition? = null
        var failure = false
        val driverError = IllegalStateException("private-owner-C secret-backend-driver-detail")
        var vectorsSupported = true
        var strictSupported = true

        override val supportsScopedVectors: Boolean get() = vectorsSupported && inner.supportsScopedVectors
        override val supportsStrictPolicyReads: Boolean get() = strictSupported && inner.supportsStrictPolicyReads

        override fun touchAccessed(ids: Collection<String>) {
            calls += "touchAccessed"
            error("touch must never occur")
        }

        private fun <T> entry(name: String, spec: ScopedReadSpec, read: () -> T): T {
            calls += name
            specs += spec
            if (failure) throw driverError
            return read()
        }

        private fun inject(rows: List<Proposition>, limit: Int) = injected?.let { (listOf(it) + rows).take(limit) } ?: rows

        var beforeCount: () -> Unit = {}

        override fun count(spec: ScopedReadSpec, clock: ReadClock): Long = entry("count", spec) {
            beforeCount()
            inner.count(spec, clock)
        }

        override fun query(spec: ScopedReadSpec, clock: ReadClock, order: PropositionQuery.OrderBy, limit: Int) =
            entry("query", spec) { inject(inner.query(spec, clock, order, limit), limit) }

        override fun keywordOverlap(spec: ScopedReadSpec, clock: ReadClock, tokens: List<String>, limit: Int) =
            entry("keywordOverlap", spec) { inject(inner.keywordOverlap(spec, clock, tokens, limit), limit) }

        override fun findSimilarWithScores(
            request: TextSimilaritySearchRequest, spec: ScopedReadSpec, clock: ReadClock, budget: ExactVectorBudget,
        ): List<SimilarityResult<Proposition>> = entry("vector", spec) {
            val results = inner.findSimilarWithScores(request, spec, clock, budget)
            injected?.let { (listOf(SimilarityResult(it, 1.0)) + results).take(request.topK) } ?: results
        }

        override fun queryByGrantOrder(
            spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>, limit: Int, timeout: Duration,
        ) = entry("queryByGrantOrder", spec) { inject(inner.queryByGrantOrder(spec, clock, rows, limit, timeout), limit) }

        override fun keywordByApprovedText(
            spec: ScopedReadSpec, clock: ReadClock, rows: List<StrictReadRow>, tokens: List<String>, limit: Int, timeout: Duration,
        ) = entry("keywordByApprovedText", spec) {
            inject(inner.keywordByApprovedText(spec, clock, rows, tokens, limit, timeout), limit)
        }
    }

    @Test
    fun `strict list count and direct key expose only approved DTOs in grant order without touches`() {
        val a = row("private-id-a")
        val b = row("private-id-b", "private-owner-B")
        val store = store(a, b, row("decoy", "private-owner-C"), row("ungranted"))
        val view = view(store, listOf(grant(b, RowProjection.text(ApprovedText.Literal("second approved"))), grant(a)))
        val session = view.snapshot(now, MEMORY_RANKING_DECAY_K)
        assertEquals(2L, session.count())
        assertEquals(listOf("second approved", "public summary"), session.list(2).map { it.text })
        assertEquals("public summary", session.get(key(a))!!.text)
        val before = store.calls.size
        assertNull(session.get(PropositionKey("private-owner-A", "ungranted")))
        assertEquals(before, store.calls.size)
        assertThrows<ScopedReadException> { session.get(PropositionKey("private-owner-C", "decoy")) }
        val text = output(memory(view))
        listOf("hidden stored", "private-owner", "private-id", "secret", "private-metadata", "private-value", "confidence", "score").forEach {
            assertFalse(text.contains(it), it)
        }
        assertEquals(2, lines(text).size)
        assertTrue(store.specs.all { it.includedKeys == setOf(key(a), key(b)) || it.includedKeys == setOf(key(a)) })
        assertFalse("touchAccessed" in store.calls)
        assertEquals(1, store.calls.count { it == "query" })
    }

    @Test
    fun `strict criteria dependencies fail before count list get or eager IO`() {
        val a = row("a")
        val store = store(a)
        val base = ScopedPropositionReader.open(store, scope)
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
            val failure = assertThrows<ScopedReadException> { view.snapshot(now, MEMORY_RANKING_DECAY_K).count() }
            assertEquals(ScopedReadFailure.INVALID_SCOPED_READ, failure.failure)
            assertInstanceOf(IllegalArgumentException::class.java, failure.cause)
            assertThrows<ScopedReadException> { view.snapshot(now, MEMORY_RANKING_DECAY_K).list(1) }
            assertThrows<ScopedReadException> { view.snapshot(now, MEMORY_RANKING_DECAY_K).get(key(a)) }
            assertThrows<ScopedReadException> { memory(view) }
        }
        assertEquals(emptyList<String>(), store.calls)
    }

    @Test
    fun `literal keyword searches use approved text and ties use public grant order`() {
        val a = row("first-hidden", text = "hidden pineapple")
        val b = row("second-hidden", "private-owner-B", text = "hidden mango")
        val store = store(a, b, row("hidden-decoy", text = "mango"))
        val grants = listOf(grant(b, RowProjection.text(ApprovedText.Literal("PINEAPPLE public"))), grant(a, RowProjection.text(ApprovedText.Literal("pineapple summary"))))
        val memory = memory(view(store, grants), setOf(PolicySearchTier.KEYWORD))
        val result = output(memory, """{"query":"pineapple","limit":2}""")
        assertEquals(2, lines(result).size)
        assertTrue(lines(result).first().contains("PINEAPPLE public"))
        assertTrue(lines(result).last().contains("pineapple summary"))
        assertFalse(result.contains("hidden"))
        assertTrue(output(memory, """{"query":"mango"}""").contains("No additional"))
        assertTrue("keywordByApprovedText" in store.calls)
        assertFalse("query" in store.calls || "keywordOverlap" in store.calls)
        assertTrue(memory.notes().contains("keyword"))
    }

    @Test
    fun `unsupported and hidden tier dependencies and empty grants do no IO`() {
        val a = row("a")
        val store = store(a)
        val view = view(store, listOf(grant(a)))
        assertThrows<ScopedReadException> { memory(view, setOf(PolicySearchTier.VECTOR)) }
        assertThrows<ScopedReadException> { memory(view, setOf(PolicySearchTier.RELATED)) }
        val omitted = view(store, listOf(grant(a, RowProjection.text(ApprovedText.Omitted))))
        assertThrows<ScopedReadException> { memory(omitted, setOf(PolicySearchTier.KEYWORD)) }
        assertThrows<ScopedReadException> { PolicyMemory(view) { eagerTopicSearch = 1 } }
        val full = view(store, listOf(grant(a, vectorProjection(), true)))
        store.vectorsSupported = false
        assertEquals(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED, assertThrows<ScopedReadException> { memory(full, setOf(PolicySearchTier.VECTOR)) }.failure)
        store.strictSupported = false
        assertEquals(ScopedReadFailure.SCOPED_READ_UNSUPPORTED, assertThrows<ScopedReadException> { memory(view) }.failure)
        assertEquals(emptyList<String>(), store.calls)

        store.vectorsSupported = true
        store.strictSupported = true
        val empty = view(store, emptyList())
        val session = empty.snapshot(now, MEMORY_RANKING_DECAY_K)
        assertEquals(0L, session.count())
        assertTrue(session.list(1).isEmpty())
        assertNull(session.get(key(a)))
        val everything = PolicyMemory.configuring(empty).withSearchTiers(PolicySearchTier.entries.toSet())
            .withEagerQuery(PropositionQuery()).withEagerTopicSearch(1)
            .withEagerTextSearch(TextSimilaritySearchRequest("query", 0.0, 1))
            .withProvenance { _, _ -> error("must never resolve") }.memory()
        assertTrue(everything.contribution().contains("0 memories available"))
        assertTrue(output(everything, """{"query":"anything"}""").contains("No additional"))
        assertTrue(output(everything).contains("No additional"))
        assertEquals(emptyList<String>(), store.calls)
    }

    @Test
    fun `eager success publishes once and later calls exclude its keys`() {
        val a = row("a")
        val b = row("b", "private-owner-B")
        val store = store(a, b)
        val view = view(store, listOf(grant(a), grant(b)))
        val memory = PolicyMemory(view) { eagerQuery = PropositionQuery(limit = 1) }
        assertEquals(2, lines(output(memory)).size)
        val first = memory.contribution()
        assertEquals(1, lines(first).size)
        assertEquals(1, lines(output(memory)).size)
        assertEquals(setOf(key(a)), store.specs.last().excludedKeys)
        val calls = store.calls.size
        assertEquals(first, memory.contribution())
        assertEquals(calls, store.calls.size)
        assertEquals(1, store.calls.count { it == "count" })
        assertEquals(2, lines(output(PolicyMemory(view) { topic = "fresh"; eagerQuery = PropositionQuery(limit = 1) })).size)
        assertTrue(store.specs.last().excludedKeys.isEmpty())

        val failing = PolicyMemory(view) { eagerQuery = PropositionQuery() }
        store.failure = true
        val error = assertThrows<ScopedReadException> { failing.contribution() }
        assertEquals(ScopedReadFailure.SCOPED_READ_FAILED, error.failure)
        assertFalse(error.toString().contains("secret"))
        store.failure = false
        assertEquals(2, lines(output(failing)).size)
        assertEquals(2, lines(failing.contribution()).size)
        assertTrue(output(failing).contains("No additional"))
    }

    @Test
    fun `a row deleted between the eager read and the count still publishes what was read`() {
        val a = row("a")
        val b = row("b", "private-owner-B")
        val store = store(a, b)
        store.beforeCount = { store.inner.delete(a.id) }
        val memory = PolicyMemory(view(store, listOf(grant(a), grant(b)))) { eagerQuery = PropositionQuery() }
        val contribution = memory.contribution()
        assertEquals(2, lines(contribution).size)
        assertTrue(contribution.contains("2 memories available"), contribution)
        assertEquals(1, store.calls.count { it == "count" })
    }

    @Test
    fun `eager modes fuse by reciprocal rank and equal scores follow grant order`() {
        val first = row("text-first", text = "approved first")
        val twice = row("found-twice", "private-owner-B", text = "near second")
        val fused = PolicyMemory(view(store(first, twice), listOf(grant(twice, vectorProjection(), true), grant(first, vectorProjection(), true)))) {
            searchTiers = setOf(PolicySearchTier.VECTOR)
            eagerTextSearch = TextSimilaritySearchRequest("approved", 0.0, 2)
            eagerQuery = PropositionQuery(limit = 1)
        }
        // Text search ranks "approved first" above "near second"; the listing finds only "near second",
        // and being found by two modes outranks being first in one.
        val ranked = lines(fused.contribution())
        assertEquals(2, ranked.size)
        assertTrue(ranked[0].contains("near second"), ranked[0])
        assertTrue(ranked[1].contains("approved first"), ranked[1])

        val listed = row("listed-only", text = "hidden listed")
        val searched = row("searched-only", "private-owner-B", text = "approved searched")
        val tied = PolicyMemory(view(store(listed, searched), listOf(grant(listed, vectorProjection(), true), grant(searched, vectorProjection(), true)))) {
            searchTiers = setOf(PolicySearchTier.VECTOR)
            eagerTextSearch = TextSimilaritySearchRequest("approved", 0.5, 1)
            eagerQuery = PropositionQuery(limit = 1)
        }
        // Each row is first in exactly one mode, so their scores are equal and the grant list decides.
        val equal = lines(tied.contribution())
        assertEquals(2, equal.size)
        assertTrue(equal[0].contains("hidden listed"), equal[0])
        assertTrue(equal[1].contains("approved searched"), equal[1])
    }

    @Test
    fun `a new policy version gets fresh eager rows while the old memory keeps what it published`() {
        val a = row("a", text = "first stored text")
        val store = store(a)
        val stored = RowProjection.text(ApprovedText.Stored)
        val v1 = PolicyMemory(view(store, listOf(grant(a, stored)))) { eagerQuery = PropositionQuery() }
        val old = v1.contribution()
        assertTrue(lines(old).single().contains("first stored text"))

        val b = row("b", "private-owner-B", text = "added later")
        store.save(a.copy(text = "revised stored text"))
        store.save(b)
        val v2 = PolicyMemory(view(store, listOf(grant(a, stored), grant(b, stored)), stamp.copy(version = "v2"))) {
            eagerQuery = PropositionQuery()
        }
        val fresh = v2.contribution()
        assertEquals(2, lines(fresh).size)
        assertTrue(lines(fresh)[0].contains("revised stored text"))
        assertTrue(lines(fresh)[1].contains("added later"))
        assertFalse(fresh.contains("first stored text"))
        assertEquals(old, v1.contribution())
    }

    @Test
    fun `provenance requests carry selected keys and permitted fields and unsolicited keys abort publication`() {
        val a = row("private-id")
        val b = row("private-other", "private-owner-B")
        val source = AllowedSource("approved-source-handle", descriptor = true, locator = false)
        val projection = RowProjection.text(ApprovedText.Literal("public")).withSources(listOf(source))
        val store = store(a, b)
        var requests: List<PolicyProvenanceRequest>? = null
        var receivedStamp: ReadPolicyStamp? = null
        val memory = PolicyMemory(view(store, listOf(grant(a, projection), grant(b)))) {
            provenance = PolicyProvenanceResolver { policyStamp, input ->
                requests = input; receivedStamp = policyStamp
                input.associate { it.key to ProjectedSource("approved citation", null) }
            }
            eagerQuery = PropositionQuery(limit = 1)
        }
        val result = memory.contribution()
        assertEquals(stamp, receivedStamp)
        assertEquals(listOf(PolicyProvenanceRequest(PolicySourceKey(key(a), source.sourceId), true, false)), requests)
        assertThrows<UnsupportedOperationException> { (requests as MutableList).clear() }
        assertTrue(result.contains("approved citation"))
        listOf("private-id", "private-owner", "secret", source.sourceId).forEach { assertFalse(result.contains(it), it) }
        output(memory)
        assertEquals(1, requests!!.size)
        var resolved = 0
        val noSources = PolicyMemory(view(store, listOf(grant(a)))) { provenance = PolicyProvenanceResolver { _, _ -> resolved++; emptyMap() } }
        assertFalse(output(noSources).contains("private-id"))
        assertEquals(0, resolved)

        var mode = 0
        val guarded = PolicyMemory.configuring(view(store, listOf(grant(a, projection)))).withEagerQuery(PropositionQuery()).withProvenance { _, input ->
            when (mode) {
                0 -> mapOf(PolicySourceKey(PropositionKey("private-owner-C", "evil"), "evil") to ProjectedSource("hidden", null))
                1 -> input.associate { it.key to ProjectedSource("public", "hidden locator") }
                2 -> error("secret provenance detail")
                else -> input.associate { it.key to ProjectedSource("approved citation", null) }
            }
        }.memory()
        repeat(3) { phase ->
            mode = phase
            val error = assertThrows<ScopedReadException> { guarded.contribution() }
            // A broken resolver is an operator problem, so its error rides along as the cause. Rejected answers have none.
            if (phase == 2) assertEquals("secret provenance detail", error.cause?.message) else assertNull(error.cause)
            assertFalse(error.toString().contains("secret"))
            assertFalse(error.toString().contains("hidden"))
        }
        mode = 3
        assertEquals(1, lines(output(guarded)).size)
        assertTrue(guarded.contribution().contains("approved citation"))
        assertTrue(output(guarded).contains("No additional"))
    }

    @Test
    fun `an approved time renders as ISO text`() {
        val a = row("a")
        val projection = RowProjection.text(ApprovedText.Literal("public summary")).withFields(setOf(MemoryField.CREATED))
        val line = lines(output(memory(view(store(a), listOf(grant(a, projection)))))).single()
        assertTrue(line.contains("\"created\":\"2026-01-01T00:00:00Z\""), line)
    }

    @Test
    fun `an approved source renders once and a wired resolver supplies it`() {
        val a = row("a")
        // The row's own provenance entry, so the projected DTO already carries it before any resolver runs.
        val source = AllowedSource("uri:secret-location", descriptor = true, locator = false)
        val projection = RowProjection.text(ApprovedText.Literal("public")).withSources(listOf(source))
        val store = store(a)
        fun sources(memory: PolicyMemory) =
            jacksonObjectMapper().readTree(lines(output(memory)).single().removePrefix("- ")).get("sources")
        val resolved = sources(PolicyMemory(view(store, listOf(grant(a, projection)))) {
            provenance = PolicyProvenanceResolver { _, input -> input.associate { it.key to ProjectedSource("host citation", null) } }
        })
        assertEquals(1, resolved.size(), resolved.toString())
        assertEquals("host citation", resolved[0].get("descriptor").asText())
        // Without a resolver the row's own approved source stands, still once.
        val projected = sources(memory(view(store, listOf(grant(a, projection)))))
        assertEquals(1, projected.size(), projected.toString())
        assertEquals("secret-descriptor", projected[0].get("descriptor").asText())
    }

    @Test
    fun `a store failure keeps its cause for operators but not in the message`() {
        val a = row("a")
        val store = store(a)
        val view = view(store, listOf(grant(a)))
        val memory = memory(view)
        store.failure = true
        listOf<() -> Unit>({ view.snapshot(now, MEMORY_RANKING_DECAY_K).list(1) }, { memory.call("{}") }).forEach { read ->
            val error = assertThrows<ScopedReadException> { read() }
            assertEquals(ScopedReadFailure.SCOPED_READ_FAILED, error.failure)
            assertSame(store.driverError, error.cause)
            assertEquals("SCOPED_READ_FAILED", error.message)
            assertFalse(error.message!!.contains("private-owner-C"))
        }
        assertEquals(listOf("queryByGrantOrder", "queryByGrantOrder"), store.calls)
    }

    @Test
    fun `memory caps that need more candidate rows than the reader allows fail at open before any store call`() {
        val a = row("a")
        val store = store(a)
        fun open(maxRows: Int, memoryLimits: MemoryLimits) =
            view(store, listOf(grant(a)), limits = ScopedReadLimits.DEFAULT.withMaxRows(maxRows), memoryLimits = memoryLimits)
        // A cap of 100 probes 400 candidate rows plus one overflow row; a cap of 1 probes 32 plus one.
        listOf(400 to MemoryLimits.DEFAULT, 100 to MemoryLimits(1, 100), 100 to MemoryLimits(100, 1)).forEach { (maxRows, caps) ->
            val failure = assertThrows<ScopedReadException> { open(maxRows, caps) }
            assertEquals(ScopedReadFailure.INVALID_SCOPED_READ, failure.failure)
        }
        assertEquals(emptyList<String>(), store.calls)
        assertEquals(1, lines(output(memory(open(401, MemoryLimits.DEFAULT)))).size)
        assertEquals(1, lines(output(memory(open(33, MemoryLimits(1, 1))))).size)
    }

    @Test
    fun `restricted and excluded sessions return only granted rows over ungranted decoys`() {
        val a = row("granted-a")
        val b = row("granted-b", "private-owner-B")
        val c = row("granted-c")
        val store = store(row("decoy-1"), a, row("decoy-2", "private-owner-B"), b, c, row("decoy-3"))
        val view = view(store, listOf(grant(a), grant(b), grant(c)), influence = HiddenFieldInfluence.ALLOW_SEARCH_INFLUENCE)
        val session = view.restrict(PropositionQuery(anyEntityIds = listOf("hidden-entity")))
            .snapshot(now, MEMORY_RANKING_DECAY_K).excluding(setOf(key(a)))
        val granted = setOf(key(b), key(c))
        assertEquals(2L, session.count())
        assertEquals(granted, session.selection("", 10, emptySet()).entries.map { it.key }.toSet())
        assertEquals(granted, session.selection("stored", 10, setOf(MemoryTier.KEYWORD)).entries.map { it.key }.toSet())
        assertNull(session.get(PropositionKey("private-owner-A", "decoy-1")))
        assertNull(session.get(key(a)))
        // Every read the store saw was already narrowed to the grants, a direct get to its one key.
        assertTrue(store.specs.all { spec -> spec.includedKeys?.let { setOf(key(a), key(b), key(c)).containsAll(it) } == true })
    }

    @Test
    fun `a related search never reaches an ungranted row linked to a granted one`() {
        val seed = row("seed", text = "apple orchard")
        val linked = row("linked", "private-owner-B", text = "pear tree")
        val hidden = row("hidden-linked", text = "banana grove")
        val store = store(seed, linked, hidden)
        val projection = { text: String -> RowProjection.text(ApprovedText.Literal(text)).withFields(setOf(MemoryField.ENTITY_IDS)) }
        val view = view(store, listOf(grant(seed, projection("seed summary")), grant(linked, projection("linked summary"))),
            influence = HiddenFieldInfluence.ALLOW_SEARCH_INFLUENCE)
        val result = lines(output(memory(view, setOf(PolicySearchTier.KEYWORD, PolicySearchTier.RELATED)), """{"query":"apple","limit":5}"""))
        assertEquals(2, result.size)
        assertTrue(result[0].contains("seed summary"), result[0])
        assertTrue(result[1].contains("linked summary"), result[1])
        assertTrue("query" in store.calls)
        assertTrue(store.specs.all { spec -> spec.includedKeys?.let { key(hidden) !in it } == true })
    }

    @Test
    fun `a policy that fails validation keeps the reason as the cause`() {
        val outside = row("outside", "private-owner-C")
        val error = assertThrows<ScopedReadException> { view(store(outside), listOf(grant(outside))) }
        assertEquals(ScopedReadFailure.INVALID_SCOPED_READ, error.failure)
        assertEquals("INVALID_SCOPED_READ", error.message)
        val cause = assertInstanceOf(IllegalArgumentException::class.java, error.cause)
        assertEquals("Grant outside read scope", cause.message)
    }

    @Test
    fun `backend key injection aborts strict list keyword direct get and vector without partial output`() {
        val a = row("a", text = "approved")
        val store = store(a).apply { injected = row("evil", "private-owner-B", "secret injection") }
        val view = view(store, listOf(grant(a, vectorProjection(), true)))
        fun assertRejected(name: String, read: () -> Unit) {
            val before = store.calls.size
            val failure = assertThrows<ScopedReadException>(name) { read() }
            assertEquals(ScopedReadFailure.SCOPED_READ_RESULT_INVALID, failure.failure, name)
            assertFalse(failure.toString().contains("evil") || failure.toString().contains("secret"), name)
            assertTrue(name in store.calls.drop(before), name)
        }
        assertRejected("queryByGrantOrder") { output(memory(view)) }
        assertRejected("keywordByApprovedText") { output(memory(view, setOf(PolicySearchTier.KEYWORD)), """{"query":"approved"}""") }
        assertRejected("vector") { output(memory(view, setOf(PolicySearchTier.VECTOR)), """{"query":"approved"}""") }
        assertRejected("query") { view.snapshot(now, MEMORY_RANKING_DECAY_K).get(key(a)) }
        val eager = PolicyMemory(view) { eagerQuery = PropositionQuery() }
        assertRejected("queryByGrantOrder") { eager.contribution() }
        assertFalse("count" in store.calls)
        store.injected = null
        assertTrue(lines(eager.contribution()).single().contains("approved"))
    }

    @Test
    fun `only query and positive integral finite limit are model controlled`() {
        val a = row("a")
        val store = store(a)
        val memory = memory(view(store, listOf(grant(a))))
        listOf("{", "[]", "null", "{} {}", """{"principal":"P2"}""", """{"policy":"v2"}""", """{"contextId":"C"}""",
            """{"query":1}""", """{"limit":0}""", """{"limit":-1}""", """{"limit":1.2}""", """{"limit":"1"}""").forEach {
            val error = assertInstanceOf(Tool.Result.Error::class.java, memory.call(it), it)
            assertEquals(MemoryToolInput.USAGE, error.message, it)
        }
        assertEquals(emptyList<String>(), store.calls)
        assertEquals(1, lines(output(memory, """{"limit":999999}""")).size)
    }

    @Test
    fun `a listing-only memory offers only a limit and lists when the model sends a query anyway`() {
        val a = row("a")
        val b = row("b", "private-owner-B")
        val store = store(a, b)
        val view = view(store, listOf(grant(b, RowProjection.text(ApprovedText.Literal("second approved"))), grant(a)))
        val listing = memory(view)
        assertEquals(listOf("limit"), listing.definition.inputSchema.parameters.map { it.name })
        val result = output(listing, """{"query":"something"}""")
        assertEquals(2, lines(result).size)
        assertTrue(lines(result).first().contains("second approved"))
        assertTrue(lines(result).last().contains("public summary"))
        assertEquals(listOf("queryByGrantOrder"), store.calls)
        val searchable = memory(view, setOf(PolicySearchTier.KEYWORD))
        assertEquals(listOf("query", "limit"), searchable.definition.inputSchema.parameters.map { it.name })
    }
}
