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
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.UnaryOperator

class ScopedMemoryTest {
    private val scope = ContextReadScope.ofValues(listOf("A", "B"))
    private val now = Instant.now()

    private fun row(id: String, context: String = "A", confidence: Double = 0.9, entity: String = "required") = Proposition(
        id = id, contextId = ContextId(context), text = "fact $id", confidence = confidence,
        mentions = listOf(EntityMention(entity, "Entity", entity)),
        created = now, contentRevised = now, metadataRevised = now,
    )

    private class Backend : PropositionRepository by InMemoryPropositionRepository(), ContextSetVectorSearchCapable {
        val rows = mutableListOf<Proposition>()
        val specs = mutableListOf<ScopedReadSpec>()
        val clocks = mutableListOf<ReadClock>()
        var countCalls = 0
        var queryCalls = 0
        var vectorCalls = 0
        var failVector = false
        var beforeCount: () -> Unit = {}
        override fun touchAccessed(ids: Collection<String>) { error("Scoped reads must never touch access") }
        private fun eligible(spec: ScopedReadSpec, clock: ReadClock): List<Proposition> {
            specs += spec
            clocks += clock
            return rows.filter {
                val key = PropositionKey(it.contextId.value, it.id)
                it.contextId.value in spec.scope.contextIdValues && key !in spec.excludedKeys &&
                    (spec.includedKeys == null || key in spec.includedKeys) && spec.fragments.all { q -> q.matchesFilters(it, clock.asOf) }
            }.sortedWith(compareByDescending<Proposition> { it.effectiveConfidenceAt(clock.asOf) }
                .thenBy { it.contextId.value }.thenBy { it.id })
        }
        override fun query(spec: ScopedReadSpec, clock: ReadClock, order: PropositionQuery.OrderBy, limit: Int): List<Proposition> {
            queryCalls++
            return eligible(spec, clock).take(limit)
        }
        override fun count(spec: ScopedReadSpec, clock: ReadClock): Long {
            countCalls++
            beforeCount()
            return eligible(spec, clock).size.toLong()
        }
        override fun keywordOverlap(spec: ScopedReadSpec, clock: ReadClock, tokens: List<String>, limit: Int) =
            eligible(spec, clock).filter { row -> tokens.any { row.text.contains(it) } }.take(limit)
        override fun findSimilarWithScores(
            request: TextSimilaritySearchRequest, spec: ScopedReadSpec, clock: ReadClock, budget: ExactVectorBudget,
        ): List<SimilarityResult<Proposition>> {
            vectorCalls++
            if (failVector) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_FAILED)
            val rows = eligible(spec, clock)
            return (if (request.query == "topic") rows.reversed() else rows).take(request.topK).map { SimilarityResult(it, 1.0) }
        }
    }

    private fun reader(backend: Backend) = ScopedPropositionReader.open(backend, scope)
    private fun memory(backend: Backend) = ScopedMemory.from(reader(backend))
    private fun output(memory: ScopedMemory, input: String = "{}") = (memory.call(input) as Tool.Result.Text).content
    private fun lines(text: String) = text.lines().filter { it.startsWith("- [") }

    @Test
    fun `list limit one is global and identical IDs retain owners`() {
        val backend = Backend().apply { rows += listOf(row("same"), row("same", "B"), row("hidden", "C", 1.0)) }
        assertEquals(1, lines(output(memory(backend), """{"limit":1}""")).size)
        val output = output(memory(backend))
        assertTrue(output.contains("[A/same]"))
        assertTrue(output.contains("[B/same]"))
        assertFalse(output.contains("hidden"))
        assertEquals(2, lines(output).size)
    }

    @Test
    fun `tool accepts only query and positive integral limit and caps results`() {
        val backend = Backend().apply { rows += listOf(row("a"), row("b"), row("c")) }
        val memory = memory(backend).withMemoryLimits(MemoryLimits.DEFAULT.withMaxResults(2))
        assertEquals(2, lines(output(memory, """{"query":"  ","limit":1000}""")).size)
        val readsBefore = listOf(backend.specs.size, backend.queryCalls, backend.countCalls, backend.vectorCalls)
        listOf("{", "[]", "null", "{} {}", """{} {"contextId":"C"}""", """{"contextId":"C"}""", """{"principal":"p"}""",
            """{"policy":{}}""", """{"topic":"x"}""", """{"query":1}""", """{"limit":0}""", """{"limit":-1}""",
            """{"limit":1.2}""", """{"limit":1.5}""", """{"limit":"1"}""").forEach { input ->
            val result = memory.call(input)
            assertInstanceOf(Tool.Result.Error::class.java, result, input)
            assertEquals(MemoryToolInput.USAGE, (result as Tool.Result.Error).message, input)
        }
        assertEquals(readsBefore, listOf(backend.specs.size, backend.queryCalls, backend.countCalls, backend.vectorCalls))
        assertEquals(setOf("query", "limit"), memory.definition.inputSchema.parameters.map { it.name }.toSet())
    }

    @Test
    fun `cumulative narrowing retains raw status entity and effective floors`() {
        val backend = Backend().apply { rows += listOf(
            row("allowed"), row("other", entity = "other"), row("low", confidence = 0.4),
            row("stale").copy(status = PropositionStatus.STALE),
            row("decayed").copy(decay = 0.1, contentRevised = now.minusSeconds(86400000), lastAccessed = now.minusSeconds(86400000)), row("old").copy(created = now.minusSeconds(100)),
        ) }
        val memory = memory(backend).withMinConfidence(0.8).withMinEffectiveConfidence(0.7)
            .withNarrowedBy { it.withEntityId("required") }
            .withNarrowedBy { PropositionQuery(createdAfter = now.minusSeconds(20)) }
            .withEagerQuery { PropositionQuery() }
        assertEquals(listOf("- [A/allowed] fact allowed"), lines(memory.contribution()))
        assertTrue(output(memory).contains("No additional"))
        assertEquals(1, backend.countCalls)
        assertThrows(ScopedReadException::class.java) { memory.withNarrowedBy { it.withContextIdValue("C") } }
    }

    @Test
    fun `a confidence floor lowered after narrowing and eager hooks governs every read`() {
        val backend = Backend().apply { rows += listOf(row("high", confidence = 0.9), row("low", "B", confidence = 0.3)) }
        val memory = memory(backend).withMinConfidence(0.8)
            .withNarrowedBy { it.withEntityId("required") }
            .withEagerQuery { it }
            .withMinConfidence(0.1)
        assertEquals(listOf("- [A/high] fact high", "- [B/low] fact low"), lines(output(memory)))
        val contribution = memory.contribution()
        assertEquals(listOf("- [A/high] fact high", "- [B/low] fact low"), lines(contribution))
        assertTrue(contribution.contains("2 memories available"), contribution)
    }

    @Test
    fun `hooks that turn hostile after configuration still cannot widen the scope`() {
        val backend = Backend().apply {
            rows += listOf(row("a"), row("old", "B").copy(status = PropositionStatus.SUPERSEDED), row("hidden", "C"))
        }
        var hostile = false
        val otherContext = UnaryOperator<PropositionQuery> { if (hostile) it.withContextIdValue("C") else it }
        val allStatuses = UnaryOperator<PropositionQuery> {
            if (hostile) it.withStatuses(PropositionStatus.entries.toSet()) else it
        }
        val narrowed = memory(backend).withNarrowedBy(otherContext)
        val eager = memory(backend).withEagerQuery(otherContext)
        val widened = memory(backend).withNarrowedBy(allStatuses).withEagerQuery(allStatuses)
        hostile = true
        fun reads() = listOf(backend.specs.size, backend.queryCalls, backend.countCalls, backend.vectorCalls)
        val before = reads()
        listOf({ narrowed.call("{}") }, { narrowed.contribution() }, { eager.contribution() }).forEach { read ->
            assertEquals(ScopedReadFailure.INVALID_SCOPED_READ, assertThrows(ScopedReadException::class.java) { read() }.failure)
        }
        assertEquals(before, reads())

        // The base query's ACTIVE status always applies as well, so a hook that lists every status adds nothing.
        assertEquals(listOf("- [A/a] fact a"), lines(output(widened)))
        val contribution = widened.contribution()
        assertEquals(listOf("- [A/a] fact a"), lines(contribution))
        assertTrue(contribution.contains("1 memories available"), contribution)
    }

    @Test
    fun `a row that leaves between the eager read and the count still publishes what was read`() {
        listOf<(Backend) -> Unit>(
            { it.rows.removeAt(0) },
            { it.rows[0] = it.rows[0].copy(status = PropositionStatus.SUPERSEDED) },
        ).forEach { change ->
            val backend = Backend().apply { rows += listOf(row("a"), row("b", "B")) }
            backend.beforeCount = { change(backend) }
            val contribution = memory(backend).withEagerQuery { it }.contribution()
            assertEquals(listOf("- [A/a] fact a", "- [B/b] fact b"), lines(contribution))
            assertTrue(contribution.contains("2 memories available"), contribution)
            assertEquals(1, backend.countCalls)
        }
    }

    @Test
    fun `eager modes share clock globally fuse deduplicate and bound rendered keys`() {
        val backend = Backend().apply { rows += listOf(row("same"), row("same", "B"), row("third"), row("hidden", "C")) }
        val memory = memory(backend).withTopic("topic").withMemoryLimits(MemoryLimits.DEFAULT.withMaxEager(2))
            .withEagerTextSearch(TextSimilaritySearchRequest(query = "text", topK = 3, similarityThreshold = 0.0))
            .withEagerTopicSearch(3).withEagerQuery { it.withLimit(3) }
        val contribution = memory.contribution()
        assertEquals(2, lines(contribution).size)
        assertEquals(2, lines(contribution).distinct().size)
        assertTrue(contribution.contains("3 memories available"))
        assertEquals(1, backend.clocks.toSet().size)
        assertFalse(contribution.contains("hidden"))
        val remaining = output(memory, """{"limit":1}""")
        assertEquals(1, lines(remaining).size)
        assertTrue(lines(contribution).none { it == lines(remaining).single() })
        assertEquals(contribution, memory.contribution())
        assertEquals(1, backend.countCalls)
    }

    @Test
    fun `E1 eager fusion ties keep the key that sorts first by code point`() {
        // U+FF61 sorts before U+10000 by code point but after it in UTF-16, where U+10000 is U+D800 U+DC00.
        val backend = Backend().apply { rows += listOf(row("𐀀"), row("｡")) }
        val memory = memory(backend).withTopic("topic").withMemoryLimits(MemoryLimits.DEFAULT.withMaxEager(1))
            .withEagerTextSearch(TextSimilaritySearchRequest(query = "text", topK = 2, similarityThreshold = 0.0))
            .withEagerTopicSearch(2)
        assertEquals(listOf("- [A/｡] fact ｡"), lines(memory.contribution()))
    }

    @Test
    fun `call before contribution reserves no eager keys and later calls exclude before K`() {
        val backend = Backend().apply {
            repeat(40) { rows += row("eager-%02d".format(it), confidence = 1.0) }
            rows += row("remaining", "B")
        }
        val memory = memory(backend).withEagerQuery { it.withLimit(40) }
        assertEquals(1, lines(output(memory, """{"limit":1}""")).size)
        assertEquals(0, backend.countCalls)
        assertEquals(40, lines(memory.contribution()).size)
        assertEquals(listOf("- [B/remaining] fact remaining"), lines(output(memory, """{"limit":1}""")))
        assertEquals(listOf("- [B/remaining] fact remaining"), lines(output(memory, """{"query":"fact","limit":1}""")))
        assertTrue(backend.specs.last().excludedKeys.size == 40)
    }

    @Test
    fun `failed eager initialization retries and never suppresses unpublished rows`() {
        val backend = Backend().apply { rows += row("a"); failVector = true }
        val memory = memory(backend).withEagerTopicSearch(1)
        assertThrows(ScopedReadException::class.java) { memory.contribution() }
        assertEquals(0, backend.countCalls)
        assertTrue(output(memory).contains("[A/a]"))
        backend.failVector = false
        assertTrue(memory.contribution().contains("[A/a]"))
        assertTrue(output(memory).contains("No additional"))
    }

    @Test
    fun `provenance receives exactly selected owner keys and unsolicited keys abort publication`() {
        val backend = Backend().apply { rows += listOf(row("same"), row("same", "B")) }
        var fail = true
        val received = mutableListOf<List<PropositionKey>>()
        val memory = memory(backend).withEagerQuery { it }.withProvenance { keys ->
            received += keys
            if (fail) mapOf(PropositionKey("A", "unselected") to listOf("hidden"))
            else keys.associateWith { listOf("source ${it.contextIdValue}") }
        }
        assertThrows(ScopedReadException::class.java) { memory.contribution() }
        fail = false
        assertEquals(2, lines(output(memory)).size)
        val rendered = memory.contribution()
        assertTrue(rendered.contains("source A"))
        assertTrue(rendered.contains("source B"))
        assertEquals(setOf(PropositionKey("A", "same"), PropositionKey("B", "same")), received.first().toSet())
        assertThrows(UnsupportedOperationException::class.java) { (received.first() as MutableList).clear() }
    }

    @Test
    fun `memory and read limits that cannot fit together fail at configuration before any read`() {
        val backend = Backend().apply { rows += row("a") }
        val small = ScopedReadLimits.DEFAULT.withMaxRows(32)
        val tight = MemoryLimits(5, 5)
        fun invalid(build: () -> Any) =
            assertEquals(ScopedReadFailure.INVALID_SCOPED_READ, assertThrows(ScopedReadException::class.java) { build() }.failure)
        // Five results probe a 32-row pool plus one, which is 33 rows against a cap of 32.
        invalid { memory(backend).withMemoryLimits(tight).withLimits(small) }
        invalid { ScopedMemory.configuring(reader(backend)).withLimits(small).withMemoryLimits(tight).memory() }
        invalid { ScopedMemory(reader(backend)) { limits = small; memoryLimits = tight } }
        val fits = memory(backend).withMemoryLimits(tight).withLimits(small.withMaxRows(33))
        invalid { fits.withMemoryLimits(MemoryLimits.DEFAULT) }
        invalid { fits.withMemoryLimits(MemoryLimits(5, 34)) }
        assertEquals(listOf(0, 0, 0, 0), listOf(backend.specs.size, backend.queryCalls, backend.countCalls, backend.vectorCalls))
        assertEquals(listOf("- [A/a] fact a"), lines(output(fits)))
    }

    @Test
    fun `narrowing that carries a limit or an order fails before any read`() {
        val backend = Backend().apply { rows += row("a") }
        val memory = memory(backend)
        listOf<UnaryOperator<PropositionQuery>>(
            UnaryOperator { it.withLimit(5) }, UnaryOperator { it.orderedByEffectiveConfidence() },
        ).forEach { hook ->
            val failure = assertThrows(ScopedReadException::class.java) { memory.withNarrowedBy(hook) }.failure
            assertEquals(ScopedReadFailure.INVALID_SCOPED_READ, failure)
        }
        assertEquals(listOf(0, 0, 0, 0), listOf(backend.specs.size, backend.queryCalls, backend.countCalls, backend.vectorCalls))
    }

    @Test
    fun `concurrent contributions publish one count provenance and rendered snapshot`() {
        val backend = Backend().apply { rows += row("a") }
        val resolutions = AtomicInteger()
        val memory = memory(backend).withEagerQuery { it }.withProvenance { keys ->
            resolutions.incrementAndGet()
            keys.associateWith { listOf("source") }
        }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..16).map { pool.submit<String> { start.await(); memory.contribution() } }
            start.countDown()
            val text = jobs.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, text.toSet().size)
            assertEquals(1, backend.countCalls)
            assertEquals(1, backend.queryCalls)
            assertEquals(1, resolutions.get())
            assertTrue(output(memory).contains("No additional"))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `Kotlin defaults retain bound reader limits and multiline rows retain one owner line`() {
        val backend = Backend().apply { rows += listOf(row("a").copy(text = "first\nsecond"), row("b")) }
        val bound = reader(backend).withLimits(ScopedReadLimits { maxRows = 40 })
        // The default memory limits need 401 rows per call, so both entries fail on the bound 40-row cap.
        listOf({ ScopedMemory.from(bound) }, { ScopedMemory(bound) { } }).forEach {
            assertEquals(ScopedReadFailure.INVALID_SCOPED_READ, assertThrows(ScopedReadException::class.java) { it() }.failure)
        }
        val direct = ScopedMemory.configuring(bound).withMemoryLimits(MemoryLimits(5, 5)).memory()
        val dsl = ScopedMemory(bound) { memoryLimits = MemoryLimits(5, 5) }
        assertEquals(output(direct, """{"limit":1}"""), output(dsl, """{"limit":1}"""))
        assertEquals(listOf("- [A/a] first second"), lines(output(dsl, """{"limit":1}""")))
    }
}
