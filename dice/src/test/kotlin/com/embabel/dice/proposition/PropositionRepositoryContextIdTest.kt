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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Guards the [PropositionRepository] default-method contract: a backend that overrides only the
 * abstract primitives (and neither context-id query) must still get a working `findByContextId`.
 * The two context-id defaults must not delegate to each other (mutual recursion → StackOverflow).
 */
class PropositionRepositoryContextIdTest {

    /** Minimal backend: implements only the abstract members, relies entirely on interface defaults. */
    private class MinimalRepository(private val store: List<Proposition>) : PropositionRepository {
        override fun save(proposition: Proposition): Proposition = proposition
        override fun findById(id: String): Proposition? = store.firstOrNull { it.id == id }
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findSimilarWithScores(
            textSimilaritySearchRequest: TextSimilaritySearchRequest,
        ): List<SimilarityResult<Proposition>> = emptyList()

        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store
        override fun delete(id: String): Boolean = false
        override fun count(): Int = store.size
    }

    @Test
    fun `findByContextId works on a backend that overrides neither context-id query`() {
        val match = Proposition(contextId = ContextId("ctx-a"), text = "in a", mentions = emptyList(), confidence = 0.9)
        val other = Proposition(contextId = ContextId("ctx-b"), text = "in b", mentions = emptyList(), confidence = 0.9)
        val repo = MinimalRepository(listOf(match, other))

        val byId = assertDoesNotThrow { repo.findByContextId(ContextId("ctx-a")) }
        assertEquals(listOf(match.id), byId.map { it.id })

        val byValue = assertDoesNotThrow { repo.findByContextIdValue("ctx-a") }
        assertEquals(listOf(match.id), byValue.map { it.id })
    }
}
