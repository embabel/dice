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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PropositionQueryTest {

    private val testContextId = ContextId("test-context")

    @Nested
    inner class MultiEntityFieldDefaults {

        @Test
        fun `anyEntityIds is null by default`() {
            val query = PropositionQuery()
            assertNull(query.anyEntityIds)
        }

        @Test
        fun `allEntityIds is null by default`() {
            val query = PropositionQuery()
            assertNull(query.allEntityIds)
        }
    }

    @Nested
    inner class WitherMethods {

        @Test
        fun `withAnyEntity sets anyEntityIds`() {
            val query = PropositionQuery().withAnyEntity("alice", "bob")
            assertEquals(listOf("alice", "bob"), query.anyEntityIds)
        }

        @Test
        fun `withAnyEntityIds sets anyEntityIds from list`() {
            val query = PropositionQuery().withAnyEntityIds(listOf("alice", "bob"))
            assertEquals(listOf("alice", "bob"), query.anyEntityIds)
        }

        @Test
        fun `withAllEntities sets allEntityIds`() {
            val query = PropositionQuery().withAllEntities("alice", "bob")
            assertEquals(listOf("alice", "bob"), query.allEntityIds)
        }

        @Test
        fun `withAllEntityIds sets allEntityIds from list`() {
            val query = PropositionQuery().withAllEntityIds(listOf("alice", "bob"))
            assertEquals(listOf("alice", "bob"), query.allEntityIds)
        }

        @Test
        fun `withers preserve existing fields`() {
            val query = PropositionQuery(contextId = testContextId, entityId = "carol")
                .withAnyEntity("alice", "bob")
                .withAllEntities("dave")

            assertEquals(testContextId, query.contextId)
            assertEquals("carol", query.entityId)
            assertEquals(listOf("alice", "bob"), query.anyEntityIds)
            assertEquals(listOf("dave"), query.allEntityIds)
        }
    }

    @Nested
    inner class CompanionFactories {

        @Test
        fun `mentioningAllEntities creates query with allEntityIds`() {
            val query = PropositionQuery.mentioningAllEntities("alice", "bob")
            assertEquals(listOf("alice", "bob"), query.allEntityIds)
            assertNull(query.anyEntityIds)
            assertNull(query.entityId)
        }

        @Test
        fun `mentioningAnyEntity creates query with anyEntityIds`() {
            val query = PropositionQuery.mentioningAnyEntity("alice", "bob")
            assertEquals(listOf("alice", "bob"), query.anyEntityIds)
            assertNull(query.allEntityIds)
            assertNull(query.entityId)
        }
    }

    @Nested
    inner class ImportanceSupport {

        @Test
        fun `minImportance is null by default`() {
            val query = PropositionQuery()
            assertNull(query.minImportance)
        }

        @Test
        fun `withMinImportance sets threshold`() {
            val query = PropositionQuery().withMinImportance(0.8)
            assertEquals(0.8, query.minImportance)
        }

        @Test
        fun `orderedByImportance sets IMPORTANCE_DESC`() {
            val query = PropositionQuery().orderedByImportance()
            assertEquals(PropositionQuery.OrderBy.IMPORTANCE_DESC, query.orderBy)
        }
    }

    @Nested
    inner class QueryFiltering {

        private fun createProposition(
            text: String,
            entityIds: List<String>,
            importance: Double = 0.5,
        ): Proposition = Proposition(
            contextId = testContextId,
            text = text,
            mentions = entityIds.map { EntityMention(span = it, type = "Person", resolvedId = it) },
            confidence = 0.9,
            importance = importance,
        )

        private fun queryFilter(
            propositions: List<Proposition>,
            query: PropositionQuery,
        ): List<Proposition> {
            // Use the default query() implementation via a trivial repo
            val repo = object : PropositionRepository {
                override fun save(proposition: Proposition) = proposition
                override fun findById(id: String) = propositions.find { it.id == id }
                override fun findByEntity(entityIdentifier: com.embabel.agent.rag.service.RetrievableIdentifier) = emptyList<Proposition>()
                override fun findSimilarWithScores(textSimilaritySearchRequest: com.embabel.common.core.types.TextSimilaritySearchRequest) = emptyList<com.embabel.common.core.types.SimilarityResult<Proposition>>()
                override fun findByStatus(status: PropositionStatus) = emptyList<Proposition>()
                override fun findByGrounding(chunkId: String) = emptyList<Proposition>()
                override fun findByMinLevel(minLevel: Int) = emptyList<Proposition>()
                override fun findAll() = propositions
                override fun delete(id: String) = false
                override fun count() = propositions.size
                override val luceneSyntaxNotes = ""
            }
            return repo.query(query)
        }

        @Test
        fun `anyEntityIds matches propositions mentioning any listed entity`() {
            val props = listOf(
                createProposition("Alice works at Acme", listOf("alice")),
                createProposition("Bob works at Globex", listOf("bob")),
                createProposition("Carol works at Initech", listOf("carol")),
            )

            val results = queryFilter(props, PropositionQuery(anyEntityIds = listOf("alice", "carol")))
            assertEquals(2, results.size)
            assertTrue(results.any { it.text.contains("Alice") })
            assertTrue(results.any { it.text.contains("Carol") })
        }

        @Test
        fun `allEntityIds matches only propositions mentioning all listed entities`() {
            val props = listOf(
                createProposition("Alice and Bob met", listOf("alice", "bob")),
                createProposition("Alice works alone", listOf("alice")),
                createProposition("Bob works alone", listOf("bob")),
            )

            val results = queryFilter(props, PropositionQuery(allEntityIds = listOf("alice", "bob")))
            assertEquals(1, results.size)
            assertTrue(results[0].text.contains("Alice and Bob"))
        }

        @Test
        fun `anyEntityIds with no matches returns empty`() {
            val props = listOf(
                createProposition("Alice works at Acme", listOf("alice")),
            )

            val results = queryFilter(props, PropositionQuery(anyEntityIds = listOf("bob", "carol")))
            assertTrue(results.isEmpty())
        }

        @Test
        fun `allEntityIds with partial matches returns empty`() {
            val props = listOf(
                createProposition("Alice works alone", listOf("alice")),
                createProposition("Bob works alone", listOf("bob")),
            )

            val results = queryFilter(props, PropositionQuery(allEntityIds = listOf("alice", "bob")))
            assertTrue(results.isEmpty())
        }

        @Test
        fun `entityId combined with anyEntityIds applies both constraints`() {
            val props = listOf(
                createProposition("Alice and Bob met", listOf("alice", "bob")),
                createProposition("Alice works alone", listOf("alice")),
                createProposition("Carol and Bob met", listOf("carol", "bob")),
            )

            // entityId = "alice" AND anyEntityIds includes "bob" or "carol"
            val results = queryFilter(
                props,
                PropositionQuery(entityId = "alice", anyEntityIds = listOf("bob", "carol")),
            )
            // Only "Alice and Bob met" matches both: mentions alice (entityId) AND mentions bob (anyEntityIds)
            assertEquals(1, results.size)
            assertTrue(results[0].text.contains("Alice and Bob"))
        }

        @Test
        fun `minImportance filters propositions below threshold`() {
            val props = listOf(
                createProposition("Weather is nice", listOf("alice"), importance = 0.2),
                createProposition("Alice is a senior engineer", listOf("alice"), importance = 0.5),
                createProposition("Mary is about to have surgery", listOf("mary"), importance = 1.0),
            )

            val results = queryFilter(props, PropositionQuery(minImportance = 0.5))
            assertEquals(2, results.size)
            assertTrue(results.none { it.text.contains("Weather") })
        }

        @Test
        fun `IMPORTANCE_DESC orders by importance descending`() {
            val props = listOf(
                createProposition("Low importance", listOf("a"), importance = 0.2),
                createProposition("High importance", listOf("b"), importance = 0.9),
                createProposition("Medium importance", listOf("c"), importance = 0.5),
            )

            val results = queryFilter(props, PropositionQuery(orderBy = PropositionQuery.OrderBy.IMPORTANCE_DESC))
            assertEquals(3, results.size)
            assertEquals("High importance", results[0].text)
            assertEquals("Medium importance", results[1].text)
            assertEquals("Low importance", results[2].text)
        }
    }
}
