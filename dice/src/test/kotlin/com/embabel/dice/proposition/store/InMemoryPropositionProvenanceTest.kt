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
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
}
