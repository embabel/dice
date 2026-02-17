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
package com.embabel.dice.projection.graph

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.Relations
import com.embabel.dice.proposition.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LlmGraphProjectorTest {

    private val contextId = ContextId("test")
    private val emptySchema = DataDictionary.fromDomainTypes("empty", emptyList())

    private fun proposition(
        text: String,
        subjectSpan: String,
        subjectType: String,
        subjectId: String?,
        objectSpan: String,
        objectType: String,
        objectId: String?,
        confidence: Double = 0.9,
    ): Proposition = Proposition(
        contextId = contextId,
        text = text,
        mentions = listOf(
            EntityMention(
                span = subjectSpan,
                type = subjectType,
                resolvedId = subjectId,
                role = MentionRole.SUBJECT,
            ),
            EntityMention(
                span = objectSpan,
                type = objectType,
                resolvedId = objectId,
                role = MentionRole.OBJECT,
            ),
        ),
        confidence = confidence,
    )

    /**
     * Create a mock Ai that returns the given classification for any prompt.
     */
    private fun mockAi(classification: RelationshipClassification): Ai {
        val mockAi = mockk<Ai>()
        val mockPromptRunner = mockk<PromptRunner>()
        val mockCreating = mockk<PromptRunner.Creating<RelationshipClassification>>()

        every { mockAi.withLlm(any<LlmOptions>()) } returns mockPromptRunner
        every { mockPromptRunner.withId(any()) } returns mockPromptRunner
        every { mockPromptRunner.creating(RelationshipClassification::class.java) } returns mockCreating
        every { mockCreating.fromTemplate(any(), any()) } returns classification

        return mockAi
    }

    /**
     * Create a mock Ai that captures the template model for inspection.
     */
    private fun mockAiCapturing(
        classification: RelationshipClassification,
        modelSlot: CapturingSlot<Map<String, Any>>,
    ): Ai {
        val mockAi = mockk<Ai>()
        val mockPromptRunner = mockk<PromptRunner>()
        val mockCreating = mockk<PromptRunner.Creating<RelationshipClassification>>()

        every { mockAi.withLlm(any<LlmOptions>()) } returns mockPromptRunner
        every { mockPromptRunner.withId(any()) } returns mockPromptRunner
        every { mockPromptRunner.creating(RelationshipClassification::class.java) } returns mockCreating
        every { mockCreating.fromTemplate(any(), capture(modelSlot)) } returns classification

        return mockAi
    }

    @Nested
    inner class RelationsIntegrationTests {

        @Test
        fun `projects using Relations predicate when schema has no matching relationships`() {
            val relations = Relations.empty()
                .withSemanticBetween("UrbotUser", "Pet", "owns", "user owns a pet")

            val ai = mockAi(RelationshipClassification(
                hasRelationship = true,
                relationshipType = "OWNS",
                fromMentionSpan = "Cassie",
                toMentionSpan = "Artemis",
                reasoning = "Cassie owns Artemis",
            ))

            val projector = LlmGraphProjector(ai, relations, LenientProjectionPolicy())
            val prop = proposition(
                text = "Cassie adopted Artemis as a kitten six years ago",
                subjectSpan = "Cassie", subjectType = "UrbotUser", subjectId = "user-1",
                objectSpan = "Artemis", objectType = "Pet", objectId = "pet-1",
            )

            val result = projector.project(prop, emptySchema)

            assertTrue(result is ProjectionSuccess)
            val success = result as ProjectionSuccess
            assertEquals("OWNS", success.projected.type)
            assertEquals("user-1", success.projected.sourceId)
            assertEquals("pet-1", success.projected.targetId)
        }

        @Test
        fun `filters Relations by mention types`() {
            val relations = Relations.empty()
                .withSemanticBetween("UrbotUser", "Pet", "owns", "user owns a pet")
                .withSemanticBetween("UrbotUser", "Place", "lives_in", "user lives in a place")

            val modelSlot = slot<Map<String, Any>>()
            val ai = mockAiCapturing(
                RelationshipClassification(
                    hasRelationship = true,
                    relationshipType = "OWNS",
                    fromMentionSpan = "Cassie",
                    toMentionSpan = "Artemis",
                    reasoning = "test",
                ),
                modelSlot,
            )

            val projector = LlmGraphProjector(ai, relations, LenientProjectionPolicy())
            val prop = proposition(
                text = "Cassie adopted Artemis",
                subjectSpan = "Cassie", subjectType = "UrbotUser", subjectId = "user-1",
                objectSpan = "Artemis", objectType = "Pet", objectId = "pet-1",
            )

            projector.project(prop, emptySchema)

            // Only "owns" relation should be passed (Pet matches, Place doesn't)
            @Suppress("UNCHECKED_CAST")
            val passedRelations = modelSlot.captured["relations"] as List<Map<String, String>>
            assertEquals(1, passedRelations.size)
            assertEquals("OWNS", passedRelations[0]["type"])
        }

        @Test
        fun `includes unconstrained Relations for any mention types`() {
            val relations = Relations.empty()
                .withSemantic("likes") // No type constraints

            val modelSlot = slot<Map<String, Any>>()
            val ai = mockAiCapturing(
                RelationshipClassification(
                    hasRelationship = true,
                    relationshipType = "LIKES",
                    fromMentionSpan = "Alice",
                    toMentionSpan = "jazz",
                    reasoning = "test",
                ),
                modelSlot,
            )

            val projector = LlmGraphProjector(ai, relations, LenientProjectionPolicy())
            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-1",
            )

            projector.project(prop, emptySchema)

            @Suppress("UNCHECKED_CAST")
            val passedRelations = modelSlot.captured["relations"] as List<Map<String, String>>
            assertEquals(1, passedRelations.size)
            assertEquals("LIKES", passedRelations[0]["type"])
        }

        @Test
        fun `rejects LLM response with unknown relationship type`() {
            val relations = Relations.empty()
                .withSemanticBetween("UrbotUser", "Pet", "owns", "user owns a pet")

            val ai = mockAi(RelationshipClassification(
                hasRelationship = true,
                relationshipType = "INVENTED_TYPE",
                fromMentionSpan = "Cassie",
                toMentionSpan = "Artemis",
                reasoning = "made up type",
            ))

            val projector = LlmGraphProjector(ai, relations, LenientProjectionPolicy())
            val prop = proposition(
                text = "Cassie adopted Artemis",
                subjectSpan = "Cassie", subjectType = "UrbotUser", subjectId = "user-1",
                objectSpan = "Artemis", objectType = "Pet", objectId = "pet-1",
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionFailed)
            assertTrue((result as ProjectionFailed).reason.contains("INVENTED_TYPE"))
        }

        @Test
        fun `fails when no Relations or schema match mention types`() {
            val relations = Relations.empty()
                .withSemanticBetween("UrbotUser", "Pet", "owns", "user owns a pet")

            val ai = mockAi(RelationshipClassification(
                hasRelationship = false, relationshipType = null,
                fromMentionSpan = null, toMentionSpan = null, reasoning = null,
            ))

            val projector = LlmGraphProjector(ai, relations, LenientProjectionPolicy())
            val prop = proposition(
                text = "Alice works at Acme",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "Acme", objectType = "Company", objectId = "company-1",
            )

            // Person and Company don't match any Relations (only UrbotUser-Pet)
            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionFailed)
            assertTrue((result as ProjectionFailed).reason.contains("No allowed relationships"))
        }
    }

    @Nested
    inner class PolicyTests {

        @Test
        fun `skips low confidence propositions`() {
            val relations = Relations.empty().withSemantic("likes")

            val ai = mockAi(RelationshipClassification(
                hasRelationship = true, relationshipType = "LIKES",
                fromMentionSpan = "Alice", toMentionSpan = "jazz", reasoning = "test",
            ))

            val projector = LlmGraphProjector(ai, relations) // Default policy
            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-1",
                confidence = 0.5,
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionSkipped)
        }

        @Test
        fun `skips unresolved entities`() {
            val relations = Relations.empty().withSemantic("likes")

            val ai = mockAi(RelationshipClassification(
                hasRelationship = true, relationshipType = "LIKES",
                fromMentionSpan = "Alice", toMentionSpan = "jazz", reasoning = "test",
            ))

            val projector = LlmGraphProjector(ai, relations) // Default policy
            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = null,
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-1",
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionSkipped)
        }
    }

    @Nested
    inner class LlmResponseHandlingTests {

        @Test
        fun `handles LLM saying no relationship`() {
            val relations = Relations.empty().withSemantic("likes")

            val ai = mockAi(RelationshipClassification(
                hasRelationship = false,
                relationshipType = null,
                fromMentionSpan = null,
                toMentionSpan = null,
                reasoning = "This is just a statement about preferences, no direct relationship",
            ))

            val projector = LlmGraphProjector(ai, relations, LenientProjectionPolicy())
            val prop = proposition(
                text = "Cassie really enjoys live jazz",
                subjectSpan = "Cassie", subjectType = "UrbotUser", subjectId = "user-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-1",
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionFailed)
        }

        @Test
        fun `uses RELATED_TO when relationship type is null but hasRelationship is true`() {
            val relations = Relations.empty().withSemantic("likes")

            val ai = mockAi(RelationshipClassification(
                hasRelationship = true,
                relationshipType = null,
                fromMentionSpan = "Alice",
                toMentionSpan = "jazz",
                reasoning = "related somehow",
            ))

            val projector = LlmGraphProjector(ai, relations, LenientProjectionPolicy())
            val prop = proposition(
                text = "Alice likes jazz",
                subjectSpan = "Alice", subjectType = "Person", subjectId = "alice-1",
                objectSpan = "jazz", objectType = "MusicGenre", objectId = "genre-1",
            )

            val result = projector.project(prop, emptySchema)
            assertTrue(result is ProjectionSuccess)
            assertEquals("RELATED_TO", (result as ProjectionSuccess).projected.type)
        }
    }
}
