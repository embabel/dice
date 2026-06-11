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
import com.embabel.dice.common.DiceEventListener
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Guards against the mutual-recursion bug between [findByContextId] and [findByContextIdValue].
 * Neither default override is provided, so calls go through the decorator's `by delegate`
 * forwarding into the interface defaults. [findByContextIdValue] must filter [findAll] directly
 * rather than delegating back to [findByContextId], which previously caused a [StackOverflowError].
 */
class PropositionRepositoryDelegationTest {

    private val contextId = ContextId("ctx-a")
    private val otherContextId = ContextId("ctx-b")

    private fun proposition(ctx: ContextId, text: String): Proposition =
        Proposition(
            contextId = ctx,
            text = text,
            mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )

    /**
     * Overrides only the abstract members — deliberately leaves findByContextId and
     * findByContextIdValue to their default implementations, which is exactly the
     * recursion site under test.
     */
    private inner class MinimalRepository : PropositionRepository {
        private val store = mutableMapOf<String, Proposition>()
        fun seed(p: Proposition) { store[p.id] = p }

        override val luceneSyntaxNotes: String = "test"
        override fun save(proposition: Proposition): Proposition { store[proposition.id] = proposition; return proposition }
        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findSimilarWithScores(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<SimilarityResult<Proposition>> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store.values.toList()
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }

    @Test
    fun `findByContextId does not StackOverflow through the decorator`() {
        val delegate = MinimalRepository().apply {
            seed(proposition(contextId, "a1"))
            seed(proposition(contextId, "a2"))
            seed(proposition(otherContextId, "b1"))
        }
        val repo = EventEmittingPropositionRepository(delegate, DiceEventListener.DEV_NULL)

        val result = assertDoesNotThrow<List<Proposition>> {
            repo.findByContextId(contextId)
        }
        val expected = delegate.findAll().filter { it.contextId.value == contextId.value }
        assertEquals(expected.toSet(), result.toSet())
        assertEquals(2, result.size)
    }

    @Test
    fun `findByContextIdValue does not StackOverflow through the decorator`() {
        val delegate = MinimalRepository().apply {
            seed(proposition(contextId, "a1"))
            seed(proposition(otherContextId, "b1"))
        }
        val repo = EventEmittingPropositionRepository(delegate, DiceEventListener.DEV_NULL)

        val result = assertDoesNotThrow<List<Proposition>> {
            repo.findByContextIdValue(contextId.value)
        }
        val expected = delegate.findAll().filter { it.contextId.value == contextId.value }
        assertEquals(expected.toSet(), result.toSet())
        assertEquals(1, result.size)
    }
}
