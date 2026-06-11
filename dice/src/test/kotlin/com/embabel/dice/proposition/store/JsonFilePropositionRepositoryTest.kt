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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JsonFilePropositionRepositoryTest {

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
    fun `propositions saved by one instance are recovered by a fresh instance over the same file`(
        @TempDir tempDir: Path,
    ) {
        val storeFile = tempDir.resolve("propositions.json")

        val first = proposition(contextA, "first statement")
        val second = proposition(contextB, "second statement")

        // Process 1: persist two propositions in different contexts.
        JsonFilePropositionRepository(storeFile).apply {
            save(first)
            save(second)
        }

        // Process restart: a brand-new instance over the same file must recover state.
        val reloaded = JsonFilePropositionRepository(storeFile)

        assertEquals(2, reloaded.count())
        assertEquals(first, reloaded.findById(first.id))

        val forA = reloaded.findByContextIdValue(contextA.value)
        assertEquals(1, forA.size)
        assertEquals(first, forA.single())
    }

    @Test
    fun `constructed without an embedding service findSimilarWithScores degrades to empty`(
        @TempDir tempDir: Path,
    ) {
        val repo = JsonFilePropositionRepository(tempDir.resolve("propositions.json"))
        repo.save(proposition(contextA, "first statement"))

        val results = repo.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "first", topK = 10, similarityThreshold = 0.0),
        )

        assertTrue(results.isEmpty())
    }
}
