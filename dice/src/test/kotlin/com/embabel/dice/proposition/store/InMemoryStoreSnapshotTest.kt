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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunLineage
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.proposition.extraction.InMemoryExtractionRunStore
import com.embabel.dice.proposition.extraction.InMemoryPropositionRunLinkStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryStoreSnapshotTest {

    private val tenant = ContextId("snapshot-tenant")
    private val runs = InMemoryExtractionRunStore()
    private val claims = InMemoryPropositionRepository()
    private val links = InMemoryPropositionRunLinkStore(runs, claims)

    private fun run(id: String) = ExtractionRun(
        contextId = tenant,
        lineage = ExtractionRunLineage.root(ExtractionRunRef(id)),
        status = ExtractionRunStatus.RUNNING,
        startedAt = Instant.parse("2026-08-31T10:15:30Z"),
    ).also { runs.save(it) }

    private fun proposition(text: String, id: String) = Proposition(
        id = id,
        contextId = tenant,
        text = text,
        mentions = emptyList(),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
    )

    @Test
    fun `E1 restore puts back the original proposition and removes one that was absent`() {
        val original = claims.save(proposition("Alice likes tea", "a"))
        val snapshot = claims.snapshot(listOf("a", "b"))

        claims.save(original.copy(text = "Alice likes coffee"))
        claims.save(proposition("Bob likes tea", "b"))
        claims.restore(snapshot)

        assertSame(original, claims.findById("a"))
        assertNull(claims.findById("b"))
    }

    @Test
    fun `E2 restore never calls the embedding service`() {
        val service = mockk<EmbeddingService>()
        every { service.embed(any<String>()) } returns floatArrayOf(1f, 0f)
        val repository = InMemoryPropositionRepository(service)
        repository.save(proposition("Alice likes tea", "a"))
        val snapshot = repository.snapshot(listOf("a", "b"))
        repository.save(proposition("Bob likes tea", "b"))
        verify(exactly = 2) { service.embed(any<String>()) }

        repository.restore(snapshot)

        verify(exactly = 2) { service.embed(any<String>()) }
        assertEquals(listOf("a"), repository.findAll().map { it.id })
    }

    @Test
    fun `E3 restore leaves an id outside the snapshot untouched`() {
        claims.save(proposition("Alice likes tea", "a"))
        val snapshot = claims.snapshot(listOf("a"))
        val outside = claims.save(proposition("Carol likes tea", "c"))

        claims.restore(snapshot)

        assertSame(outside, claims.findById("c"))
    }

    @Test
    fun `E4 unlink removes only the named link from a run`() {
        val key = run("run-1").key()
        claims.save(proposition("Alice likes tea", "p1"))
        claims.save(proposition("Bob likes tea", "p2"))
        links.link(key, listOf("p1", "p2"))

        links.unlink(key, listOf("p2"))

        assertEquals(listOf("p1"), links.propositionsOf(key, 10))
        assertEquals(setOf("p1"), links.linkedTo(key))
    }

    @Test
    fun `E5 unlinking the last id empties the run and leaves other runs alone`() {
        val emptied = run("run-1").key()
        val other = run("run-2").key()
        claims.save(proposition("Alice likes tea", "p1"))
        claims.save(proposition("Bob likes tea", "p2"))
        links.link(emptied, listOf("p1"))
        links.link(other, listOf("p1", "p2"))

        links.unlink(emptied, listOf("p1"))

        assertTrue(links.linkedTo(emptied).isEmpty())
        assertEquals(setOf("p1", "p2"), links.linkedTo(other))
    }
}
