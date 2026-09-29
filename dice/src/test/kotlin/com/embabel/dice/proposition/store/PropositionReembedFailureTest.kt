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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.ConsecutiveFailureBreaker
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionReembedIncompleteException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An embedding failure costs only the proposition it happened to, and is never swallowed: the
 * default re-embed carries on and names what failed, the in-memory save changes nothing, and the
 * JSON store still starts.
 */
class PropositionReembedFailureTest {

    private val context = ContextId("reembed")

    /** Texts the embedder currently refuses. Mutable so a test can "fix" the service and retry. */
    private val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val embedded: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val embeddingService: EmbeddingService = mock<EmbeddingService>().also { service ->
        whenever(service.embed(any<String>())).thenAnswer { invocation ->
            val text = invocation.getArgument<String>(0)
            if (text in failing) throw EmbedRefused(text)
            embedded += text
            floatArrayOf(1f, 0f, 0f)
        }
    }

    private class EmbedRefused(text: String) : RuntimeException("embedder refused '$text'")

    private fun proposition(text: String) =
        Proposition(contextId = context, text = text, mentions = emptyList(), confidence = 0.9)

    private fun similarTo(text: String) = TextSimilaritySearchRequest(query = text, similarityThreshold = 0.0, topK = 10)

    @Test
    fun `default reembedAll continues past a failure and names it`() {
        val repo = InMemoryPropositionRepository(embeddingService)
        val good1 = repo.save(proposition("good one"))
        val bad = repo.save(proposition("bad one"))
        val good2 = repo.save(proposition("good two"))
        embedded.clear()
        failing += "bad one"

        val thrown = assertThrows<PropositionReembedIncompleteException> { repo.reembedAll() }

        assertEquals(listOf(bad.id), thrown.failedPropositionIds)
        assertEquals(2, thrown.reembedded)
        assertFalse(thrown.report.indexRecreated)
        assertTrue(thrown.cause is EmbedRefused, "the underlying failure is the cause")
        assertEquals(setOf("good one", "good two"), embedded.toSet(), "every other proposition was still re-embedded")
        assertEquals(setOf(good1.id, bad.id, good2.id), repo.findAll().map { it.id }.toSet())

        failing.clear()
        val retry = repo.reembedAll()
        assertEquals(3, retry.propositions, "a rerun is the retry")
    }

    @Test
    fun `in-memory save stores nothing when the embed fails`() {
        val repo = InMemoryPropositionRepository(embeddingService)
        val original = repo.save(proposition("stable text"))
        failing += "never stored"
        failing += "revised text"

        assertThrows<EmbedRefused> { repo.save(proposition("never stored")) }
        assertEquals(1, repo.count())

        // A failed re-save of an existing id leaves the previous version (and its vector) in place.
        assertThrows<EmbedRefused> { repo.save(original.copy(text = "revised text")) }
        assertEquals("stable text", repo.findById(original.id)?.text)
        assertEquals(listOf(original.id), repo.findSimilar(similarTo("anything")).map { it.id })
    }

    @Test
    fun `json repository starts despite a failing embed and a later reembedAll retries it`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("propositions.json")
        val good = proposition("loads fine")
        val bad = proposition("fails to embed")
        JsonFilePropositionRepository(file).apply {
            save(good)
            save(bad)
        }
        failing += "fails to embed"

        val reloaded = JsonFilePropositionRepository(file, embeddingService)

        assertEquals(2, reloaded.count(), "every stored proposition is loaded")
        assertEquals(bad, reloaded.findById(bad.id))
        assertEquals(listOf(good.id), reloaded.findSimilar(similarTo("anything")).map { it.id },
            "the unembedded proposition is absent from vector search, not given a wrong vector")

        failing.clear()
        assertEquals(2, reloaded.reembedAll().propositions)
        assertEquals(setOf(good.id, bad.id), reloaded.findSimilar(similarTo("anything")).map { it.id }.toSet())
    }

    @Test
    fun `default reembedAll stops calling a dead embedder and reports the rest as not attempted`() {
        val repo = InMemoryPropositionRepository(embeddingService)
        val all = (1..20).map { repo.save(proposition("text $it")) }
        embedded.clear()
        val calls = AtomicInteger()
        whenever(embeddingService.embed(any<String>())).thenAnswer {
            calls.incrementAndGet()
            throw EmbedRefused(it.getArgument(0))
        }

        val thrown = assertThrows<PropositionReembedIncompleteException> { repo.reembedAll() }

        assertEquals(ConsecutiveFailureBreaker.DEFAULT_MAX_CONSECUTIVE_FAILURES, calls.get())
        assertEquals(ConsecutiveFailureBreaker.DEFAULT_MAX_CONSECUTIVE_FAILURES, thrown.failedPropositionIds.size)
        assertEquals(20 - ConsecutiveFailureBreaker.DEFAULT_MAX_CONSECUTIVE_FAILURES, thrown.notAttemptedPropositionIds.size)
        assertEquals(0, thrown.reembedded)
        assertEquals(all.map { it.id }.toSet(), (thrown.failedPropositionIds + thrown.notAttemptedPropositionIds).toSet())
    }

    @Test
    fun `json repository with a dead embedder starts after a few calls, not one per proposition`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("propositions.json")
        JsonFilePropositionRepository(file).apply { (1..20).forEach { save(proposition("text $it")) } }
        val calls = AtomicInteger()
        whenever(embeddingService.embed(any<String>())).thenAnswer {
            calls.incrementAndGet()
            throw EmbedRefused(it.getArgument(0))
        }

        val reloaded = JsonFilePropositionRepository(file, embeddingService)

        assertEquals(20, reloaded.count())
        assertEquals(ConsecutiveFailureBreaker.DEFAULT_MAX_CONSECUTIVE_FAILURES, calls.get())
    }
}
