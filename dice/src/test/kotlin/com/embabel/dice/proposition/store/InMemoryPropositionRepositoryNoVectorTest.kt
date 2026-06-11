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
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryPropositionRepositoryNoVectorTest {

    private val contextA = ContextId("user-a")
    private val contextB = ContextId("user-b")

    private fun proposition(contextId: ContextId, text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = 0.9,
        )

    @Test
    fun `repository constructs and stores without an embedding service`() {
        val repo = InMemoryPropositionRepository()

        val first = proposition(contextA, "first statement")
        val second = proposition(contextB, "second statement")
        repo.save(first)
        repo.save(second)

        assertEquals(2, repo.count())
        assertEquals(first, repo.findById(first.id))
        assertEquals(2, repo.findAll().size)
    }

    @Test
    fun `findByContextIdValue returns only the matching context subset`() {
        val repo = InMemoryPropositionRepository()
        repo.save(proposition(contextA, "first statement"))
        repo.save(proposition(contextA, "another for a"))
        repo.save(proposition(contextB, "second statement"))

        val forA = repo.findByContextIdValue(contextA.value)

        assertEquals(2, forA.size)
        assertTrue(forA.all { it.contextId == contextA })
    }

    @Test
    fun `findSimilarWithScores degrades to an empty list without throwing`() {
        val repo = InMemoryPropositionRepository()
        repo.save(proposition(contextA, "first statement"))

        val results = repo.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "first", topK = 10, similarityThreshold = 0.0),
        )

        assertTrue(results.isEmpty())
    }
}
