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
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        repository = InMemoryPropositionRepository()
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
            assertTrue(fetched.contains("confidence=0.90"))
        }

        @Test
        fun `store trims text and clamps confidence`() {
            val stored = tools.storeMemory("session-1", "  trimmed fact  ", confidence = 1.7)
            val id = stored.substringAfter("Stored proposition ").substringBefore(":")
            val fetched = tools.getProposition("session-1", id)
            assertTrue(fetched.contains("trimmed fact"))
            assertFalse(fetched.contains("  trimmed"))
            assertTrue(fetched.contains("confidence=1.00"))
        }

        @Test
        fun `store rejects blank text`() {
            assertThrows<IllegalArgumentException> {
                tools.storeMemory("session-1", "   ")
            }
        }

        @Test
        fun `store rejects blank context`() {
            assertThrows<IllegalArgumentException> {
                tools.storeMemory("  ", "A fact")
            }
        }

        /**
         * A cross-context id and an id that does not exist must be indistinguishable, or the
         * tool confirms to a caller that an id it does not own exists somewhere else.
         * `MemoryController` collapses both into a 404 for the same reason.
         */
        @Test
        fun `get does not reveal that a foreign id exists`() {
            val proposition = repository.save(
                Proposition(
                    contextId = ContextId("other"),
                    text = "Secret fact",
                    mentions = emptyList(),
                    confidence = 0.8,
                ),
            )

            val foreign = tools.getProposition("session-1", proposition.id)
            val absent = tools.getProposition("session-1", proposition.id.reversed())

            assertFalse(foreign.contains("Secret fact"))
            assertFalse(foreign.contains("other"))
            assertEquals(
                absent.replace(proposition.id.reversed(), proposition.id),
                foreign,
                "a foreign id and an unknown id must produce the same answer",
            )
        }

        @Test
        fun `get reports missing id`() {
            val result = tools.getProposition("session-1", "no-such-id")
            assertEquals("No proposition with id 'no-such-id' in context 'session-1'.", result)
        }

        @Test
        fun `get rejects blank proposition id`() {
            assertThrows<IllegalArgumentException> {
                tools.getProposition("session-1", "  ")
            }
        }

        @Test
        fun `get rejects blank context`() {
            assertThrows<IllegalArgumentException> {
                tools.getProposition(" ", "any-id")
            }
        }

        @Test
        fun `store clamps negative confidence`() {
            val stored = tools.storeMemory("session-1", "Clamped low", confidence = -0.4)
            val id = stored.substringAfter("Stored proposition ").substringBefore(":")
            val fetched = tools.getProposition("session-1", id)
            assertTrue(fetched.contains("confidence=0.00"))
        }

        @Test
        fun `get of a stale proposition in the same context is allowed for inspection`() {
            val proposition = repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Old fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.STALE,
                ),
            )
            val fetched = tools.getProposition("session-1", proposition.id)
            assertTrue(fetched.contains("Old fact"))
        }

        @Test
        fun `whitespace around context_id is trimmed`() {
            val stored = tools.storeMemory("  session-1  ", "Padded context fact")
            val id = stored.substringAfter("Stored proposition ").substringBefore(":")
            val fetched = tools.getProposition(" session-1", id)
            assertTrue(fetched.contains("Padded context fact"))
            assertTrue(tools.listMemories("session-1 ", limit = 5).contains("Padded context fact"))
        }

        @Test
        fun `get trims proposition id`() {
            val stored = tools.storeMemory("session-1", "Padded id fact")
            val id = stored.substringAfter("Stored proposition ").substringBefore(":")
            val fetched = tools.getProposition("session-1", "  $id  ")
            assertTrue(fetched.contains("Padded id fact"))
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

        @Test
        fun `recall blank query lists memories`() {
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Only fact",
                    mentions = emptyList(),
                    confidence = 0.8,
                ),
            )
            val result = tools.recall("session-1", query = "  ", limit = 10)
            assertTrue(result.contains("Only fact"))
        }

        @Test
        fun `recall does not leak across contexts`() {
            repository.save(
                Proposition(
                    contextId = ContextId("tenant-a"),
                    text = "Tenant A knows Canva",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("tenant-b"),
                    text = "Tenant B knows Canva",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )

            val result = tools.recall("tenant-a", query = "Canva", limit = 10)
            assertTrue(result.contains("Tenant A knows Canva"))
            assertFalse(result.contains("Tenant B knows Canva"))
        }

        @Test
        fun `recall rejects blank context`() {
            assertThrows<IllegalArgumentException> {
                tools.recall(" ", query = "anything", limit = 5)
            }
        }

        @Test
        fun `recall excludes stale superseded and contradicted`() {
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Active Canva fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.ACTIVE,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Stale Canva fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.STALE,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Superseded Canva fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.SUPERSEDED,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Contradicted Canva fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.CONTRADICTED,
                ),
            )

            val result = tools.recall("session-1", query = "Canva", limit = 10)
            assertTrue(result.contains("Active Canva fact"))
            assertFalse(result.contains("Stale Canva fact"))
            assertFalse(result.contains("Superseded Canva fact"))
            assertFalse(result.contains("Contradicted Canva fact"))
        }

        @Test
        fun `recall respects minConfidence`() {
            val filtered = DiceMcpTools(repository, minConfidence = 0.7)
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Weak Canva guess",
                    mentions = emptyList(),
                    confidence = 0.4,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Strong Canva fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )
            val result = filtered.recall("session-1", query = "Canva", limit = 10)
            assertTrue(result.contains("Strong Canva fact"))
            assertFalse(result.contains("Weak Canva guess"))
        }

        @Test
        fun `recall unmatched query does not leak other-context hits`() {
            repository.save(
                Proposition(
                    contextId = ContextId("tenant-b"),
                    text = "Only tenant B knows Zephyr",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )
            val result = tools.recall("tenant-a", query = "Zephyr", limit = 10)
            assertFalse(result.contains("Only tenant B knows Zephyr"))
            assertEquals(
                "No memories matched 'Zephyr'. Context 'tenant-a' is empty.",
                result,
            )
        }

        @Test
        fun `recall miss on a populated context includes the count and a retry nudge`() {
            tools.storeMemory("session-1", "Alice works at Acme", confidence = 0.9)
            tools.storeMemory("session-1", "Bob prefers Kotlin", confidence = 0.8)
            val result = tools.recall("session-1", query = "Zephyr", limit = 10)
            assertEquals(
                "No memories matched 'Zephyr' in context 'session-1'. " +
                    "2 memories are stored there — try rephrasing or a broader query.",
                result,
            )
        }

        @Test
        fun `recall without a query on an empty context matches list`() {
            assertEquals(
                tools.listMemories("empty-session", limit = 10),
                tools.recall("empty-session", query = null, limit = 10),
            )
        }

        /**
         * `dice_get` advertises its id as "returned by recall, list, or store", so recall has to
         * actually emit one. Recall renders through the same formatter as list for that reason.
         */
        @Test
        fun `recall hits carry ids that get accepts`() {
            val stored = tools.storeMemory("session-1", "Acme uses Canva for design", confidence = 0.9)
            val id = stored.substringAfter("Stored proposition ").substringBefore(":")

            val recalled = tools.recall("session-1", query = "Canva", limit = 5)
            assertTrue(recalled.contains("id=$id"), "recall output should carry the proposition id")

            val fetched = tools.getProposition("session-1", id)
            assertTrue(fetched.contains("Acme uses Canva for design"))
        }

        @Test
        fun `recall with no query carries ids too`() {
            val stored = tools.storeMemory("session-1", "Listable fact", confidence = 0.9)
            val id = stored.substringAfter("Stored proposition ").substringBefore(":")

            val recalled = tools.recall("session-1", query = null, limit = 5)
            assertTrue(recalled.contains("id=$id"))
        }
    }

    @Nested
    inner class LimitTests {

        private fun seed(count: Int) {
            repeat(count) { i ->
                repository.save(
                    Proposition(
                        contextId = ContextId("session-1"),
                        text = "Canva fact $i",
                        mentions = emptyList(),
                        confidence = 0.9,
                    ),
                )
            }
        }

        /**
         * A limit from an MCP client is whatever number the caller's model wrote, so it is
         * clamped rather than trusted — the same bound [com.embabel.dice.query.discovery]'s
         * router applies before it does any work.
         */
        @Test
        fun `list caps an absurd limit at MAX_LIMIT`() {
            seed(DiceMcpTools.MAX_LIMIT + 5)
            val listed = tools.listMemories("session-1", limit = 10_000)
            assertEquals(DiceMcpTools.MAX_LIMIT, listed.lines().size - 1)
        }

        @Test
        fun `recall caps an absurd limit at MAX_LIMIT`() {
            seed(DiceMcpTools.MAX_LIMIT + 5)

            val listing = tools.recall("session-1", query = null, limit = 10_000)
            assertEquals(DiceMcpTools.MAX_LIMIT, listing.lines().size - 1)

            val searched = tools.recall("session-1", query = "Canva", limit = 10_000)
            assertTrue(searched.lines().size - 1 <= DiceMcpTools.MAX_LIMIT)
        }

        @Test
        fun `a limit below one is raised to one`() {
            seed(3)
            assertEquals(1, tools.listMemories("session-1", limit = 0).lines().size - 1)
            assertEquals(1, tools.listMemories("session-1", limit = -5).lines().size - 1)
            assertEquals(1, tools.recall("session-1", query = null, limit = 0).lines().size - 1)
        }
    }

    @Nested
    inner class ListAndFilterTests {

        @Test
        fun `list empty context`() {
            val result = tools.listMemories("empty-session", limit = 10)
            assertEquals("No memories in context 'empty-session'.", result)
        }

        @Test
        fun `list orders by effective confidence`() {
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Low",
                    mentions = emptyList(),
                    confidence = 0.2,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "High",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )
            val listed = tools.listMemories("session-1", limit = 10)
            assertTrue(listed.indexOf("High") < listed.indexOf("Low"))
        }

        @Test
        fun `list respects limit`() {
            repeat(5) { i ->
                repository.save(
                    Proposition(
                        contextId = ContextId("session-1"),
                        text = "Fact $i",
                        mentions = emptyList(),
                        confidence = 0.5,
                    ),
                )
            }
            val listed = tools.listMemories("session-1", limit = 2)
            val numbered = listed.lines().count { it.matches(Regex("^\\d+\\..*")) }
            assertEquals(2, numbered)
        }

        @Test
        fun `zero limit is coerced to at least one`() {
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Kept",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )
            val listed = tools.listMemories("session-1", limit = 0)
            assertTrue(listed.contains("Kept"))
        }

        @Test
        fun `default minConfidence excludes weak propositions`() {
            val filtered = DiceMcpTools(repository)
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Weak guess",
                    mentions = emptyList(),
                    confidence = 0.2,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Strong fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                ),
            )
            val listed = filtered.listMemories("session-1", limit = 10)
            assertTrue(listed.contains("Strong fact"))
            assertFalse(listed.contains("Weak guess"))
        }

        @Test
        fun `list rejects blank context`() {
            assertThrows<IllegalArgumentException> {
                tools.listMemories("  ", limit = 10)
            }
        }

        @Test
        fun `non-active statuses are excluded from list`() {
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Active fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.ACTIVE,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Stale fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.STALE,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Superseded fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.SUPERSEDED,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Contradicted fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.CONTRADICTED,
                ),
            )
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Promoted fact",
                    mentions = emptyList(),
                    confidence = 0.9,
                    status = PropositionStatus.PROMOTED,
                ),
            )
            val listed = tools.listMemories("session-1", limit = 10)
            assertTrue(listed.contains("Active fact"))
            assertFalse(listed.contains("Stale fact"))
            assertFalse(listed.contains("Superseded fact"))
            assertFalse(listed.contains("Contradicted fact"))
            assertFalse(listed.contains("Promoted fact"))
        }

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
            assertFalse(listA.contains("Tenant B fact"))

            val listB = tools.listMemories("tenant-b", limit = 10)
            assertTrue(listB.contains("Tenant B fact"))
            assertFalse(listB.contains("Tenant A secret"))
        }

        @Test
        fun `format includes entity mentions`() {
            repository.save(
                Proposition(
                    contextId = ContextId("session-1"),
                    text = "Jim knows Neo4j",
                    mentions = listOf(EntityMention(span = "Jim", type = "Person")),
                    confidence = 0.9,
                ),
            )
            val listed = tools.listMemories("session-1", limit = 10)
            assertTrue(listed.contains("Jim (Person)"))
        }
    }

    @Nested
    inner class ToolSurfaceTests {

        @Test
        fun `asTools exposes exactly the four named tools`() {
            val exported = DiceMcpTools.asTools(tools)
            assertEquals(DiceMcpTools.TOOL_NAMES, exported.map { it.definition.name }.toSet())
            assertEquals(4, exported.size)
        }

        @Test
        fun `constructor rejects invalid minConfidence`() {
            assertThrows<IllegalArgumentException> {
                DiceMcpTools(repository, minConfidence = 1.1)
            }
            assertThrows<IllegalArgumentException> {
                DiceMcpTools(repository, minConfidence = -0.1)
            }
        }

        @Test
        fun `constructor rejects non-positive defaultLimit`() {
            assertThrows<IllegalArgumentException> {
                DiceMcpTools(repository, defaultLimit = 0)
            }
            assertThrows<IllegalArgumentException> {
                DiceMcpTools(repository, defaultLimit = -3)
            }
        }

        /** A default above the clamp would be silently truncated at call time; reject it instead. */
        @Test
        fun `constructor rejects a defaultLimit above MAX_LIMIT`() {
            assertThrows<IllegalArgumentException> {
                DiceMcpTools(repository, defaultLimit = DiceMcpTools.MAX_LIMIT + 1)
            }
            DiceMcpTools(repository, defaultLimit = DiceMcpTools.MAX_LIMIT)
        }

        @Test
        fun `asTools names are the stable dice_ contract`() {
            val names = DiceMcpTools.asTools(tools).map { it.definition.name }.toSet()
            assertEquals(
                setOf("dice_recall", "dice_list", "dice_store", "dice_get"),
                names,
            )
        }
    }

    @Nested
    inner class ContextIsolationTests {

        @Test
        fun `store in one context cannot be listed recalled or fetched from another`() {
            val stored = tools.storeMemory("tenant-a", "Tenant A only")
            val id = stored.substringAfter("Stored proposition ").substringBefore(":")

            val listed = tools.listMemories("tenant-b", limit = 10)
            assertFalse(listed.contains("Tenant A only"))

            val recalled = tools.recall("tenant-b", query = "Tenant", limit = 10)
            assertFalse(recalled.contains("Tenant A only"))

            val fetched = tools.getProposition("tenant-b", id)
            assertTrue(fetched.contains("No proposition with id"))
            assertFalse(fetched.contains("Tenant A only"))
        }
    }

    /**
     * A live store or driver failure must not reach the MCP client. The leak string is
     * deliberately a Cypher fragment plus a bolt host — the same class of detail
     * [com.embabel.dice.web.rest.DiscoveryController] sanitizes out of its 500s.
     */
    @Nested
    inner class StoreFailureTests {

        private val leak = "MATCH (n) RETURN n; bolt://neo4j-prod.internal:7687"
        private lateinit var failing: DiceMcpTools

        @BeforeEach
        fun failingTools() {
            failing = DiceMcpTools(LeakingStore(leak), minConfidence = 0.0)
        }

        @Test
        fun `store failure is a generic error with no cause and no driver text`() {
            assertSanitized { failing.storeMemory("session-1", "A fact") }
        }

        @Test
        fun `list failure is a generic error with no cause and no driver text`() {
            assertSanitized { failing.listMemories("session-1", limit = 10) }
        }

        @Test
        fun `get failure is a generic error with no cause and no driver text`() {
            assertSanitized { failing.getProposition("session-1", "any-id") }
        }

        @Test
        fun `recall failure is a generic error with no cause and no driver text`() {
            assertSanitized { failing.recall("session-1", query = "Canva", limit = 10) }
        }

        @Test
        fun `recall listing failure is a generic error with no cause and no driver text`() {
            assertSanitized { failing.recall("session-1", query = null, limit = 10) }
        }

        @Test
        fun `caller validation still surfaces as IllegalArgumentException`() {
            assertThrows<IllegalArgumentException> {
                failing.storeMemory("  ", "A fact")
            }
            assertThrows<IllegalArgumentException> {
                failing.recall(" ", query = "anything", limit = 5)
            }
        }

        private fun assertSanitized(call: () -> String) {
            val thrown = assertThrows<IllegalStateException> { call() }
            assertTrue(thrown.message!!.endsWith("failed: the knowledge store is unavailable"))
            assertEquals(null, thrown.cause)
            assertFalse(thrown.message!!.contains(leak))
            assertFalse(thrown.stackTraceToString().contains(leak))
        }
    }

    /**
     * Implements [PropositionRepository] by delegation so only the MCP entry points
     * have to throw. [keywordOverlap] is overridden too: Kotlin `by` would otherwise
     * run the default on the in-memory delegate, and MemoryRetriever's keyword probe
     * would never see the failure. The leak text rides on the exception message the
     * way a real driver error would.
     */
    private class LeakingStore(
        private val leak: String,
    ) : PropositionRepository by InMemoryPropositionRepository() {
        override fun save(proposition: Proposition): Proposition = explode()
        override fun findById(id: String): Proposition? = explode()
        override fun query(query: PropositionQuery): List<Proposition> = explode()
        override fun findAll(): List<Proposition> = explode()
        override fun keywordOverlap(
            base: PropositionQuery,
            tokens: List<String>,
            limit: Int,
        ): List<Proposition> = explode()

        private fun explode(): Nothing =
            throw RuntimeException(leak)
    }
}
