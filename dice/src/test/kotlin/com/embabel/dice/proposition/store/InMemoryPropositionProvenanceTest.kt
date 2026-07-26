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
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class PortableSourceQueryFixture {

    val contextId = ContextId("portable-source-query")
    private val foreignContextId = ContextId("portable-source-query-foreign")
    val locator = UriLocator("https://example.com/shared-source")
    val revisionOneRef = SourceRevisionRef(locator.key(), "r1")
    val revisionTwoRef = SourceRevisionRef(locator.key(), "r2")

    val revisionless = proposition(
        contextId = contextId,
        text = "revisionless",
        entries = listOf(entry()),
    )
    val revisionOne = proposition(
        contextId = contextId,
        text = "r1",
        entries = listOf(entry("r1")),
    )
    val revisionTwo = proposition(
        contextId = contextId,
        text = "r2",
        entries = listOf(entry("r2")),
    )
    val duplicateRevisionOne = proposition(
        contextId = contextId,
        text = "duplicate r1 evidence",
        entries = listOf(
            entry("r1", "duplicate-r1-a"),
            entry("r1", "duplicate-r1-b"),
        ),
    )
    val foreignContext = proposition(
        contextId = foreignContextId,
        text = "foreign context",
        entries = listOf(
            entry(),
            entry("r1", "foreign-r1-a"),
            entry("r1", "foreign-r1-b"),
            entry("r2"),
        ),
    )

    val propositions = listOf(
        revisionless,
        revisionOne,
        revisionTwo,
        duplicateRevisionOne,
        foreignContext,
    )

    private fun entry(
        sourceRevision: String? = null,
        chunkId: String? = null,
    ): ProvenanceEntry =
        ProvenanceEntry(
            locator = locator,
            sourceRevision = sourceRevision,
            chunkId = chunkId,
        )

    private fun proposition(
        contextId: ContextId,
        text: String,
        entries: List<ProvenanceEntry>,
    ): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = 0.9,
            provenanceEntries = entries,
        )
}

internal fun assertPortableSourceQueries(
    repository: PropositionRepository,
    fixture: PortableSourceQueryFixture,
) {
    val allSourceVersions = repository.findBySourceKey(
        fixture.contextId,
        fixture.locator.key(),
    )
    val exactRevisionOne = repository.findBySourceRevision(
        fixture.contextId,
        fixture.revisionOneRef,
    )
    val exactRevisionTwo = repository.findBySourceRevision(
        fixture.contextId,
        fixture.revisionTwoRef,
    )
    val revisionless = repository.findRevisionlessBySourceLocator(
        fixture.contextId,
        fixture.locator,
    )

    assertEquals(
        setOf(
            fixture.revisionless.id,
            fixture.revisionOne.id,
            fixture.revisionTwo.id,
            fixture.duplicateRevisionOne.id,
        ),
        allSourceVersions.map { it.id }.toSet(),
    )
    assertEquals(
        setOf(fixture.revisionOne.id, fixture.duplicateRevisionOne.id),
        exactRevisionOne.map { it.id }.toSet(),
    )
    assertEquals(setOf(fixture.revisionTwo.id), exactRevisionTwo.map { it.id }.toSet())
    assertEquals(setOf(fixture.revisionless.id), revisionless.map { it.id }.toSet())

    listOf(allSourceVersions, exactRevisionOne, exactRevisionTwo, revisionless).forEach { result ->
        assertEquals(result.size, result.map { it.id }.toSet().size)
        assertEquals(setOf(fixture.contextId), result.map { it.contextId }.toSet())
        assertEquals(false, result.any { it.id == fixture.foreignContext.id })
    }
}

/**
 * The provenance-management defaults on [com.embabel.dice.proposition.PropositionRepository] over the
 * in-memory backend: append, authoritative replace, read, and the absent-proposition contract.
 */
class InMemoryPropositionProvenanceTest {

    private lateinit var repo: InMemoryPropositionRepository

    @BeforeEach
    fun setUp() {
        val embeddingService = mock<EmbeddingService>()
        whenever(embeddingService.embed(any<String>())).thenReturn(floatArrayOf(0f, 0f, 0f))
        repo = InMemoryPropositionRepository(embeddingService)
    }

    private fun uri(u: String) = ProvenanceEntry(locator = UriLocator(u))

    private fun savedFact(vararg uris: String): Proposition =
        repo.save(
            Proposition(
                contextId = ContextId("ctx"),
                text = "fact",
                mentions = emptyList(),
                confidence = 0.9,
                provenanceEntries = uris.map(::uri),
            ),
        )

    @Test
    fun `addProvenance appends and dedups`() {
        val p = savedFact("https://example.com/a")
        repo.addProvenance(p.id, listOf(uri("https://example.com/b"), uri("https://example.com/a")))

        val uris = repo.provenanceOf(p.id).map { (it.locator as UriLocator).uri }.toSet()
        assertEquals(setOf("https://example.com/a", "https://example.com/b"), uris)
    }

    @Test
    fun `setProvenance replaces`() {
        val p = savedFact("https://example.com/a", "https://example.com/b")
        repo.setProvenance(p.id, listOf(uri("https://example.com/c")))

        assertEquals(
            listOf("https://example.com/c"),
            repo.provenanceOf(p.id).map { (it.locator as UriLocator).uri },
        )
    }

    @Test
    fun `clearProvenance empties`() {
        val p = savedFact("https://example.com/a")
        repo.clearProvenance(p.id)
        assertEquals(emptyList<ProvenanceEntry>(), repo.provenanceOf(p.id))
    }

    @Test
    fun `add and set return null for an unknown proposition`() {
        assertNull(repo.addProvenance("missing", listOf(uri("https://example.com/a"))))
        assertNull(repo.setProvenance("missing", listOf(uri("https://example.com/a"))))
        assertEquals(emptyList<ProvenanceEntry>(), repo.provenanceOf("missing"))
    }

    @Test
    fun `portable source queries distinguish revisions without duplicates or context leaks`() {
        val fixture = PortableSourceQueryFixture()
        repo.saveAll(fixture.propositions)

        assertPortableSourceQueries(repo, fixture)
    }
}
