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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Integration tests for [DrivineEmbeddingAccess] against a Neo4j testcontainer (provided by
 * Drivine's test support, same as [DrivinePropositionStoreIntegrationTest]).
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineEmbeddingAccessIntegrationTest {

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var embeddingAccess: DrivineEmbeddingAccess

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
    }

    private fun prop(text: String): Proposition =
        Proposition(
            contextId = ContextId("embed-ctx"),
            text = text,
            mentions = emptyList(),
            confidence = 0.8,
            decay = 0.05,
            status = PropositionStatus.ACTIVE,
        )

    @Test
    fun `write vector then read back identical`() {
        val saved = repository.save(prop("Has a vector"))
        val vector = listOf(0.25f, -0.5f, 1.0f, 0.0f, 3.75f)

        embeddingAccess.importEmbedding(saved.id, vector)
        val readBack = embeddingAccess.embeddingFor(saved.id)

        assertEquals(vector, readBack)
    }

    @Test
    fun `overwriting a vector replaces the previous one`() {
        val saved = repository.save(prop("Reembeddable"))
        embeddingAccess.importEmbedding(saved.id, listOf(1.0f, 1.0f))
        embeddingAccess.importEmbedding(saved.id, listOf(2.0f, 3.0f, 4.0f))

        assertEquals(listOf(2.0f, 3.0f, 4.0f), embeddingAccess.embeddingFor(saved.id))
    }

    @Test
    fun `missing node returns null`() {
        assertNull(embeddingAccess.embeddingFor("does-not-exist"))
    }

    @Test
    fun `node that exists but was never given an explicit vector via this port still returns something readable or null, never throws`() {
        // save() already computes and stores an embedding from the proposition's text (see
        // DrivinePropositionRepository.embeddingFor), so the node created here already has a
        // vector. This asserts embeddingFor never throws regardless, and that whatever comes
        // back is a plain float list matching what save() actually wrote.
        val saved = repository.save(prop("Auto-embedded on save"))
        val result = embeddingAccess.embeddingFor(saved.id)
        assertEquals(FakeEmbeddingService().dimensions, result?.size)
    }
}
