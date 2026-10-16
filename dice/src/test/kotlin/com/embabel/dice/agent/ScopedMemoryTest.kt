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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.UnaryOperator
import javax.tools.ToolProvider

class ScopedMemoryTest {
    @TempDir
    lateinit var directory: Path

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
        var countCalls = 0
        var queryCalls = 0
        var vectorCalls = 0
        var failVector = false
        override fun touchAccessed(ids: Collection<String>) { error("Scoped reads must never touch access") }
        private fun eligible(scope: ContextReadScope, spec: ScopedReadSpec): List<Proposition> {
            specs += spec
            return rows.filter {
                val key = PropositionKey(it.contextId.value, it.id)
                it.contextId.value in scope.contextIdValues && key !in spec.excludedKeys &&
                    (spec.includedKeys == null || key in spec.includedKeys) && spec.fragments.all { q -> q.matchesFilters(it, spec.asOf) }
            }.sortedWith(compareByDescending<Proposition> { it.effectiveConfidenceAt(spec.asOf) }
                .thenBy { it.contextId.value }.thenBy { it.id })
        }
        override fun query(scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int): List<Proposition> {
            queryCalls++
            return eligible(scope, spec).take(limit)
        }
        override fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long {
            countCalls++
            return eligible(scope, spec).size.toLong()
        }
        override fun keywordOverlap(scope: ContextReadScope, spec: ScopedReadSpec, tokens: List<String>, limit: Int) =
            eligible(scope, spec).filter { row -> tokens.any { row.text.contains(it) } }.take(limit)
        override fun findSimilarWithScores(
            request: TextSimilaritySearchRequest, scope: ContextReadScope, spec: ScopedReadSpec, budget: ExactVectorBudget,
        ): List<SimilarityResult<Proposition>> {
            vectorCalls++
            if (failVector) throw ScopedReadException("SCOPED_READ_FAILED")
            val rows = eligible(scope, spec)
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
        val memory = memory(backend).withLimits(ScopedReadLimits.DEFAULT.withMaxResults(2))
        assertEquals(2, lines(output(memory, """{"query":"  ","limit":1000}""")).size)
        listOf("{", "[]", "null", """{} {"contextId":"C"}""", """{"contextId":"C"}""", """{"principal":"p"}""", """{"policy":{}}""",
            """{"topic":"C"}""", """{"query":1}""", """{"limit":0}""", """{"limit":-1}""",
            """{"limit":1.5}""", """{"limit":"1"}""").forEach { input ->
            assertThrows(ScopedReadException::class.java, { output(memory, input) }, input)
        }
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
    fun `input collections and Kotlin configuration are snapshotted`() {
        val backend = Backend().apply { rows += listOf(row("a"), row("b", entity = "other")) }
        val entities = mutableListOf("required")
        lateinit var captured: ScopedMemory.Configuration
        val memory = ScopedMemory(reader(backend)) {
            captured = this
            topic = "selected"
            narrowedBy = UnaryOperator { PropositionQuery(anyEntityIds = entities) }
            eagerQuery = UnaryOperator { it }
        }
        entities.clear()
        entities += "other"
        captured.topic = "changed"
        captured.minConfidence = 1.0
        assertTrue(memory.description.contains("selected"))
        assertEquals(listOf("- [A/a] fact a"), lines(memory.contribution()))
    }

    @Test
    fun `eager modes share clock globally fuse deduplicate and bound rendered keys`() {
        val backend = Backend().apply { rows += listOf(row("same"), row("same", "B"), row("third"), row("hidden", "C")) }
        val memory = memory(backend).withTopic("topic").withLimits(ScopedReadLimits.DEFAULT.withMaxEager(2))
            .withEagerTextSearch(TextSimilaritySearchRequest(query = "text", topK = 3, similarityThreshold = 0.0))
            .withEagerTopicSearch(3).withEagerQuery { it.withLimit(3) }
        val contribution = memory.contribution()
        assertEquals(2, lines(contribution).size)
        assertEquals(2, lines(contribution).distinct().size)
        assertTrue(contribution.contains("3 memories available"))
        assertEquals(1, backend.specs.map { it.asOf }.toSet().size)
        assertFalse(contribution.contains("hidden"))
        val remaining = output(memory, """{"limit":1}""")
        assertEquals(1, lines(remaining).size)
        assertTrue(lines(contribution).none { it == lines(remaining).single() })
        assertEquals(contribution, memory.contribution())
        assertEquals(1, backend.countCalls)
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
    fun `fresh facade grants and withers never share published cache`() {
        val backend = Backend().apply { rows += listOf(row("a"), row("b", "B")) }
        val original = memory(backend).withEagerQuery { it }
        val old = original.contribution()
        backend.rows += row("later")
        assertEquals(old, original.contribution())
        assertTrue(original.withTopic("new").contribution().contains("later"))
        val revoked = ScopedMemory.from(reader(backend).includingOnly(listOf(PropositionKey("B", "b"))))
            .withEagerQuery { it }
        assertEquals(listOf("- [B/b] fact b"), lines(revoked.contribution()))
        assertTrue(old.contains("[A/a]"))
    }

    @Test
    fun `Java immutable chain executes with Kotlin direct and DSL parity and fresh terminals`() {
        val source = directory.resolve("ScopedClient.java")
        java.nio.file.Files.writeString(source, """
            import com.embabel.dice.agent.*;
            import com.embabel.dice.proposition.*;
            import com.embabel.common.core.types.TextSimilaritySearchRequest;
            public class ScopedClient {
                public static ScopedMemory.Configuring chain(ScopedPropositionReader reader) {
                    return ScopedMemory.configuring(reader).withTopic("topic").withMinConfidence(0.8)
                        .withMinEffectiveConfidence(0.0).withLimits(ScopedReadLimits.DEFAULT)
                        .withNarrowedBy(q -> q.withEntityId("required"))
                        .withEagerQuery(q -> q.withLimit(1)).withEagerTopicSearch(1)
                        .withEagerTextSearch(TextSimilaritySearchRequest.create("text", 0.0, 1))
                        .withProvenance(keys -> java.util.Collections.emptyMap());
                }
                public static ScopedMemory single(PropositionRepository repository) {
                    return ScopedMemory.forContext("A", repository);
                }
                public static ScopedMemory union(PropositionRepository repository) {
                    return Memory.forContexts(ContextReadScope.ofValues(java.util.List.of("A", "B")), repository);
                }
            }
        """.trimIndent())
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull(compiler)
        val classpath = System.getProperty("surefire.test.class.path") ?: System.getProperty("java.class.path")
        assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", directory.toString(), source.toString()))
        val backend = Backend().apply { rows += row("a") }
        java.net.URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            val client = loader.loadClass("ScopedClient")
            val chain = client.getMethod("chain", ScopedPropositionReader::class.java).invoke(null, reader(backend)) as ScopedMemory.Configuring
            val javaMemory = chain.memory()
            val direct = memory(backend).withTopic("topic").withMinConfidence(0.8).withMinEffectiveConfidence(0.0)
                .withNarrowedBy { it.withEntityId("required") }.withEagerQuery { it.withLimit(1) }
                .withEagerTopicSearch(1).withEagerTextSearch(TextSimilaritySearchRequest("text", 0.0, 1))
                .withProvenance { emptyMap() }
            val dsl = ScopedMemory(reader(backend)) {
                topic = "topic"; minConfidence = 0.8; minEffectiveConfidence = 0.0
                narrowedBy = UnaryOperator { it.withEntityId("required") }
                eagerQuery = UnaryOperator { it.withLimit(1) }
                eagerTopicSearch = 1
                eagerTextSearch = TextSimilaritySearchRequest("text", 0.0, 1)
                provenance = ScopedProvenanceResolver { emptyMap() }
            }
            assertEquals(direct.contribution(), javaMemory.contribution())
            assertEquals(direct.contribution(), dsl.contribution())
            assertNotSame(javaMemory, chain.memory())
            assertEquals(1, lines(output(chain.memory())).size)
            assertTrue(output(javaMemory).contains("No additional"))
            assertEquals("Memories about topic", chain.memory().description)
            assertEquals("Memories about changed", chain.withTopic("changed").memory().description)
            assertInstanceOf(ScopedMemory::class.java, client.getMethod("single", PropositionRepository::class.java).invoke(null, backend))
            assertInstanceOf(ScopedMemory::class.java, client.getMethod("union", PropositionRepository::class.java).invoke(null, backend))
            assertEquals("Memories about the user & context", ScopedMemory.configuring(reader(backend)).memory().description)
        }
    }

    @Test
    fun `configuring validates at terminal and all direct withers validate eagerly`() {
        val base = ScopedMemory.configuring(reader(Backend()))
        val invalid = base.withMinConfidence(Double.NaN)
        assertThrows(ScopedReadException::class.java) { invalid.memory() }
        assertNotNull(base.memory())
        val memory = base.memory()
        assertThrows(ScopedReadException::class.java) { memory.withMinEffectiveConfidence(1.1) }
        assertThrows(ScopedReadException::class.java) { memory.withEagerTopicSearch(0) }
        assertThrows(ScopedReadException::class.java) { memory.withEagerTextSearch(TextSimilaritySearchRequest("x", similarityThreshold = 0.0, topK = 0)) }
        assertThrows(ScopedReadException::class.java) { memory.withEagerQuery { it.withLimit(-1) } }
    }

    @Test
    fun `generic eager publication initializes once concurrently and retries failed factory`() {
        val publication = EagerPublication<String>()
        val calls = AtomicInteger()
        assertNull(publication.current())
        assertThrows(IllegalStateException::class.java) { publication.getOrPublish { calls.incrementAndGet(); error("retry") } }
        assertNull(publication.current())
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..16).map {
                pool.submit<PublishedEagerSnapshot<String>> {
                    start.await()
                    publication.getOrPublish {
                        calls.incrementAndGet()
                        val key = PropositionKey("A", "id")
                        PublishedEagerSnapshot(listOf(CandidateEnvelope(key, "approved", setOf("entity"))), setOf(key), 7, "exact rendered text")
                    }
                }
            }
            start.countDown()
            val snapshots = tasks.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, calls.get())
            assertTrue(snapshots.all { it === publication.current() })
            assertEquals("exact rendered text", snapshots.first().renderedText)
            assertEquals(7L, snapshots.first().count)
            assertThrows(UnsupportedOperationException::class.java) { (snapshots.first().keys as MutableSet).clear() }
        } finally { pool.shutdownNow() }
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
        val bound = reader(backend).withLimits(ScopedReadLimits.DEFAULT.withMaxResults(1))
        val direct = ScopedMemory.from(bound)
        val dsl = ScopedMemory(bound) { }
        assertEquals(output(direct), output(dsl))
        assertEquals(listOf("- [A/a] first second"), lines(output(dsl)))
    }

    @Test
    fun `generic eager publication validates exact selected keys and snapshots caller collections`() {
        val publication = EagerPublication<String>()
        val key = PropositionKey("A", "id")
        val entries = mutableListOf(CandidateEnvelope(key, "projected", mutableSetOf("entity")))
        assertThrows(ScopedReadException::class.java) {
            publication.getOrPublish { PublishedEagerSnapshot(entries, emptySet(), 1, "text") }
        }
        assertNull(publication.current())
        val keys = mutableSetOf(key)
        val published = publication.getOrPublish { PublishedEagerSnapshot(entries, keys, 1, "text") }
        entries.clear(); keys.clear()
        assertEquals(listOf("projected"), published.entries.map { it.value })
        assertEquals(setOf(key), published.keys)
    }
}
