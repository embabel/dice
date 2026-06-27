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
package com.embabel.dice.mcp

import com.embabel.agent.core.ContextId
import com.embabel.dice.entity.EntityAssertion
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.web.rest.TestPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DiceMcpToolsTest {

    private lateinit var repository: PropositionRepository
    private lateinit var tools: DiceMcpTools

    @BeforeEach
    fun setUp() {
        repository = TestPropositionRepository()
        tools = DiceMcpTools(repository, minConfidence = 0.0)
    }

    @Nested
    inner class StoreAndGetTests {

        @Test
        fun `store and get round trip`() {
            val stored = tools.storeMemory("session-1", "User likes jazz", confidence = 0.9)
            assertTrue(stored.startsWith("Stored proposition"))

            val listed = tools.listMemories("session-1", limit = 5)
            assertTrue(listed.contains("User likes jazz"))

            val id = stored.substringAfter("Stored proposition ").substringBefore(":")
            val fetched = tools.getProposition("session-1", id)
            assertTrue(fetched.contains("User likes jazz"))
        }

        @Test
        fun `get rejects wrong context`() {
            val proposition = repository.save(
                Proposition(
                    contextId = ContextId("other"),
                    text = "Secret fact",
                    mentions = emptyList(),
                    confidence = 0.8,
                ),
            )
            val result = tools.getProposition("session-1", proposition.id)
            assertTrue(result.contains("not in context"))
        }
    }

    @Nested
    inner class RecallTests {

        @Test
        fun `recall finds keyword match`() {
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Alice works at Acme Corp",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.ACTIVE,
                ),
            )

            val result = tools.recall("session-1", query = "Acme", limit = 5)
            assertTrue(result.contains("Acme"))
        }

        @Test
        fun `recall without query lists memories`() {
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "First fact",
                    mentions = emptyList(),
                    confidence = 0.8,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Second fact",
                    mentions = emptyList(),
                    confidence = 0.7,
                ),
            )

            val result = tools.recall("session-1", query = null, limit = 10)
            assertTrue(result.contains("First fact"))
            assertTrue(result.contains("Second fact"))
        }
    }

    @Nested
    inner class ContextIsolationTests {

        @Test
        fun `list does not leak across contexts`() {
            repository.save(
                Proposition(
                    contextId = ContextId("tenant-a"),
                    text = "Tenant A secret",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("tenant-b"),
                    text = "Tenant B fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )

            val listA = tools.listMemories("tenant-a", limit = 10)
            assertTrue(listA.contains("Tenant A secret"))
            assertTrue(!listA.contains("Tenant B fact"))

            val listB = tools.listMemories("tenant-b", limit = 10)
            assertTrue(listB.contains("Tenant B fact"))
            assertTrue(!listB.contains("Tenant A secret"))
        }

        @Test
        fun `get rejects proposition from another context`() {
            val proposition = repository.save(
                Proposition(
                    contextId = ContextId("tenant-a"),
                    text = "Scoped fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )
            val result = tools.getProposition("tenant-b", proposition.id)
            assertTrue(result.contains("not in context"))
        }
    }

    @Nested
    inner class ProfileTests {

        @Test
        fun `core profile exposes four tools`() {
            val names = DiceMcpProfile.CORE.toolNames()
            assertEquals(
                setOf(
                    DiceMcpTools.RECALL,
                    DiceMcpTools.LIST,
                    DiceMcpTools.STORE,
                    DiceMcpTools.GET,
                ),
                names,
            )
        }

        @Test
        fun `extended profile adds optional tools when wired`() {
            val without = DiceMcpProfile.EXTENDED.toolNames(
                pipelineAvailable = false,
                entityResolutionAvailable = false,
            )
            assertEquals(4, without.size)

            val withAll = DiceMcpProfile.EXTENDED.toolNames(
                pipelineAvailable = true,
                entityResolutionAvailable = true,
            )
            assertTrue(withAll.contains(DiceMcpTools.EXTRACT))
            assertTrue(withAll.contains(DiceMcpTools.ASSERT_ENTITIES))
        }
    }

    @Nested
    inner class OptionalToolTests {

        @Test
        fun `extract fails fast without pipeline`() {
            assertThrows<IllegalStateException> {
                tools.extract("session-1", "Some text about Brahms")
            }
        }

        @Test
        fun `assert entities fails fast without service`() {
            assertThrows<IllegalStateException> {
                tools.assertEntities(listOf(EntityAssertion(name = "Alice", labels = listOf("Person"))))
            }
        }
    }
}
