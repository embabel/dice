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
package com.embabel.dice.projection.prolog

import com.embabel.dice.projection.graph.ProjectedRelationship
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PrologProjectorTest {

    private val schema = PrologSchema.withDefaults()
    private val projector = DefaultPrologProjector(prologSchema = schema)

    @Nested
    inner class ProjectRelationshipTests {

        @Test
        fun `projectRelationship converts relationship to fact`() {
            val relationship = ProjectedRelationship(
                sourceId = "alice-uuid",
                targetId = "kubernetes-uuid",
                type = "EXPERT_IN",
                confidence = 0.9,
                sourcePropositionIds = listOf("prop-1"),
            )

            val fact = projector.projectRelationship(relationship)

            assertEquals("expert_in", fact.predicate)
            assertEquals(listOf("alice-uuid", "kubernetes-uuid"), fact.args)
            assertEquals(0.9, fact.confidence)
            assertEquals(listOf("prop-1"), fact.sourcePropositionIds)
        }

        @Test
        fun `projectRelationship handles camelCase relationship types`() {
            val relationship = ProjectedRelationship(
                sourceId = "alice",
                targetId = "techcorp",
                type = "worksAt",
                confidence = 0.85,
                sourcePropositionIds = listOf("prop-1"),
            )

            val fact = projector.projectRelationship(relationship)

            assertEquals("works_at", fact.predicate)
        }

        @Test
        fun `projectRelationship derives predicate for unknown types`() {
            val relationship = ProjectedRelationship(
                sourceId = "alice",
                targetId = "project-x",
                type = "contributesTo",
                confidence = 0.8,
                sourcePropositionIds = listOf("prop-1"),
            )

            val fact = projector.projectRelationship(relationship)

            assertEquals("contributes_to", fact.predicate)
        }

        @Test
        fun `projectRelationship preserves multiple source proposition ids`() {
            val relationship = ProjectedRelationship(
                sourceId = "alice",
                targetId = "bob",
                type = "FRIEND_OF",
                confidence = 0.95,
                sourcePropositionIds = listOf("prop-1", "prop-2", "prop-3"),
            )

            val fact = projector.projectRelationship(relationship)

            assertEquals(listOf("prop-1", "prop-2", "prop-3"), fact.sourcePropositionIds)
        }
    }

    @Nested
    inner class ProjectAllTests {

        @Test
        fun `projectAll generates facts for all relationships`() {
            val relationships = listOf(
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "kubernetes",
                    type = "EXPERT_IN",
                    confidence = 0.9,
                    sourcePropositionIds = listOf("prop-1"),
                ),
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "techcorp",
                    type = "WORKS_AT",
                    confidence = 0.85,
                    sourcePropositionIds = listOf("prop-2"),
                ),
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "bob",
                    type = "FRIEND_OF",
                    confidence = 0.95,
                    sourcePropositionIds = listOf("prop-3"),
                ),
            )

            val result = projector.projectAll(relationships)

            assertEquals(3, result.factCount)
            assertEquals(3, result.facts.size)

            val predicates = result.facts.map { it.predicate }.toSet()
            assertEquals(setOf("expert_in", "works_at", "friend_of"), predicates)
        }

        @Test
        fun `projectAll generates confidence facts when enabled`() {
            val relationships = listOf(
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "kubernetes",
                    type = "EXPERT_IN",
                    confidence = 0.9,
                    sourcePropositionIds = listOf("prop-1"),
                ),
            )

            val result = projector.projectAll(relationships)

            assertEquals(1, result.confidenceFacts.size)
            assertEquals(0.9, result.confidenceFacts.first().fact.confidence)
        }

        @Test
        fun `projectAll generates grounding facts when enabled`() {
            val relationships = listOf(
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "kubernetes",
                    type = "EXPERT_IN",
                    confidence = 0.9,
                    sourcePropositionIds = listOf("prop-1", "prop-2"),
                ),
            )

            val result = projector.projectAll(relationships)

            assertEquals(2, result.groundingFacts.size)
            val propIds = result.groundingFacts.map { it.propositionId }.toSet()
            assertEquals(setOf("prop-1", "prop-2"), propIds)
        }

        @Test
        fun `projectAll with confidence disabled omits confidence facts`() {
            val projectorNoConfidence = DefaultPrologProjector(
                prologSchema = schema,
                includeConfidence = false,
            )

            val relationships = listOf(
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "kubernetes",
                    type = "EXPERT_IN",
                    confidence = 0.9,
                    sourcePropositionIds = listOf("prop-1"),
                ),
            )

            val result = projectorNoConfidence.projectAll(relationships)

            assertTrue(result.confidenceFacts.isEmpty())
            assertEquals(1, result.facts.size)
        }

        @Test
        fun `projectAll with grounding disabled omits grounding facts`() {
            val projectorNoGrounding = DefaultPrologProjector(
                prologSchema = schema,
                includeGrounding = false,
            )

            val relationships = listOf(
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "kubernetes",
                    type = "EXPERT_IN",
                    confidence = 0.9,
                    sourcePropositionIds = listOf("prop-1"),
                ),
            )

            val result = projectorNoGrounding.projectAll(relationships)

            assertTrue(result.groundingFacts.isEmpty())
            assertEquals(1, result.facts.size)
        }

        @Test
        fun `projectAll handles empty relationship list`() {
            val result = projector.projectAll(emptyList())

            assertEquals(0, result.factCount)
            assertTrue(result.facts.isEmpty())
            assertTrue(result.confidenceFacts.isEmpty())
            assertTrue(result.groundingFacts.isEmpty())
        }
    }

    @Nested
    inner class IntegrationTests {

        @Test
        fun `projected facts can be loaded into engine and queried`() {
            val relationships = listOf(
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "kubernetes",
                    type = "EXPERT_IN",
                    confidence = 0.9,
                    sourcePropositionIds = listOf("prop-1"),
                ),
                ProjectedRelationship(
                    sourceId = "bob",
                    targetId = "python",
                    type = "EXPERT_IN",
                    confidence = 0.85,
                    sourcePropositionIds = listOf("prop-2"),
                ),
            )

            val prologResult = projector.projectAll(relationships)
            val engine = PrologEngine.fromProjection(prologResult, schema)

            // Query the facts
            assertTrue(engine.query("expert_in('alice', 'kubernetes')"))
            assertTrue(engine.query("expert_in('bob', 'python')"))
            assertFalse(engine.query("expert_in('alice', 'python')"))

            // Query with variables
            val aliceExpertise = engine.findAll("expert_in('alice', X)", "X")
            assertEquals(listOf("kubernetes"), aliceExpertise)
        }

        @Test
        fun `projected facts work with inference rules`() {
            val relationships = listOf(
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "kubernetes",
                    type = "EXPERT_IN",
                    confidence = 0.9,
                    sourcePropositionIds = listOf("prop-1"),
                ),
                ProjectedRelationship(
                    sourceId = "bob",
                    targetId = "alice",
                    type = "FRIEND_OF",
                    confidence = 0.95,
                    sourcePropositionIds = listOf("prop-2"),
                ),
            )

            val prologResult = projector.projectAll(relationships)

            // Schema includes can_help_with rule: can_help_with(Expert, Topic) :- expert_in(Expert, Topic)
            val engine = PrologEngine.fromProjection(prologResult, schema)

            // Verify basic facts are queryable
            assertTrue(engine.query("expert_in('alice', 'kubernetes')"))
            assertTrue(engine.query("friend_of('bob', 'alice')"))

            // can_help_with: someone can help with a topic if they're an expert in it
            assertTrue(engine.query("can_help_with('alice', 'kubernetes')"))
        }

        @Test
        fun `projected facts preserve source ids for provenance`() {
            val relationships = listOf(
                ProjectedRelationship(
                    sourceId = "alice-uuid-123",
                    targetId = "techcorp-uuid-456",
                    type = "WORKS_AT",
                    confidence = 0.9,
                    sourcePropositionIds = listOf("prop-1", "prop-2"),
                ),
            )

            val prologResult = projector.projectAll(relationships)

            // Check grounding facts preserve source proposition IDs
            assertEquals(2, prologResult.groundingFacts.size)
            val propIds = prologResult.groundingFacts.map { it.propositionId }.toSet()
            assertEquals(setOf("prop-1", "prop-2"), propIds)
        }
    }

    @Nested
    inner class CustomSchemaTests {

        @Test
        fun `custom predicate mappings are respected`() {
            val customMapping = PredicateMapping("MENTORS", "teaches")
            val customSchema = PrologSchema.withDefaults(additionalMappings = listOf(customMapping))
            val customProjector = DefaultPrologProjector(prologSchema = customSchema)

            val relationship = ProjectedRelationship(
                sourceId = "alice",
                targetId = "bob",
                type = "MENTORS",
                confidence = 0.8,
                sourcePropositionIds = listOf("prop-1"),
            )

            val fact = customProjector.projectRelationship(relationship)

            assertEquals("teaches", fact.predicate)
        }

        @Test
        fun `custom rules are included in engine`() {
            val customRules = """
                senior_expert(X) :- expert_in(X, _), expert_in(X, _).
            """.trimIndent()
            val customSchema = PrologSchema.withRules(customRules)
            val customProjector = DefaultPrologProjector(prologSchema = customSchema)

            val relationships = listOf(
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "kubernetes",
                    type = "EXPERT_IN",
                    confidence = 0.9,
                    sourcePropositionIds = listOf("prop-1"),
                ),
                ProjectedRelationship(
                    sourceId = "alice",
                    targetId = "docker",
                    type = "EXPERT_IN",
                    confidence = 0.85,
                    sourcePropositionIds = listOf("prop-2"),
                ),
            )

            val prologResult = customProjector.projectAll(relationships)
            val engine = PrologEngine.fromProjection(prologResult, customSchema)

            assertTrue(engine.query("senior_expert('alice')"))
        }
    }
}
