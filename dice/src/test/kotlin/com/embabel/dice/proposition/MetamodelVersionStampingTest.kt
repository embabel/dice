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
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MetamodelVersionStampingTest {

    private val testContextId = ContextId("test-context")
    private val testContentHash = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6a7b8c9d0"

    @Test
    fun `proposition metadata with METAMODEL_VERSION survives save and retrieve`() {
        val repo = InMemoryPropositionRepository()

        val original = Proposition(
            contextId = testContextId,
            text = "Alice works at Acme",
            mentions = listOf(EntityMention(span = "alice", type = "Person", resolvedId = "alice")),
            confidence = 0.95,
        ).withMetadataValue(DiceMetadataKeys.METAMODEL_VERSION, testContentHash)

        val saved = repo.save(original)
        val retrieved = repo.findById(saved.id)

        assertNotNull(retrieved)
        assertEquals(testContentHash, retrieved!!.metadata[DiceMetadataKeys.METAMODEL_VERSION])
    }

    @Test
    fun `propositions can be selected by METAMODEL_VERSION metadata value`() {
        val repo = InMemoryPropositionRepository()
        val hash1 = "hash1"
        val hash2 = "hash2"

        val prop1 = Proposition(
            contextId = testContextId,
            text = "Alice works at Acme",
            mentions = emptyList(),
            confidence = 0.95,
        ).withMetadataValue(DiceMetadataKeys.METAMODEL_VERSION, hash1)

        val prop2 = Proposition(
            contextId = testContextId,
            text = "Bob works at Globex",
            mentions = emptyList(),
            confidence = 0.95,
        ).withMetadataValue(DiceMetadataKeys.METAMODEL_VERSION, hash2)

        val prop3 = Proposition(
            contextId = testContextId,
            text = "Carol works at Initech",
            mentions = emptyList(),
            confidence = 0.95,
        ).withMetadataValue(DiceMetadataKeys.METAMODEL_VERSION, hash1)

        repo.save(prop1)
        repo.save(prop2)
        repo.save(prop3)

        val hash1Props = repo.findAll().filter {
            it.metadata[DiceMetadataKeys.METAMODEL_VERSION] == hash1
        }

        assertEquals(2, hash1Props.size)
        assertTrue(hash1Props.any { it.text.contains("Alice") })
        assertTrue(hash1Props.any { it.text.contains("Carol") })
    }

    @Test
    fun `proposition without METAMODEL_VERSION has no value for the key`() {
        val repo = InMemoryPropositionRepository()

        val prop = Proposition(
            contextId = testContextId,
            text = "Alice works at Acme",
            mentions = emptyList(),
            confidence = 0.95,
        )

        val saved = repo.save(prop)
        val retrieved = repo.findById(saved.id)

        assertNotNull(retrieved)
        assertNull(retrieved!!.metadata[DiceMetadataKeys.METAMODEL_VERSION])
    }
}
