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

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NamedEntityDataRepositoryGraphRelationshipPersisterTest {

    private val contextId = ContextId("test")

    private fun proposition(
        id: String = "prop-1",
        text: String = "Rod responds to emails from Tom",
        confidence: Double = 0.8,
    ): Proposition = Proposition(
        id = id,
        contextId = contextId,
        text = text,
        mentions = listOf(
            EntityMention(
                span = "Rod",
                type = "BlackmawUser",
                resolvedId = "user-rod",
                role = MentionRole.SUBJECT,
            ),
            EntityMention(
                span = "Tom",
                type = "Contact",
                resolvedId = "contact-tom",
                role = MentionRole.OBJECT,
            ),
        ),
        confidence = confidence,
    )

    private fun mockRepository(): NamedEntityDataRepository {
        val repo = mockk<NamedEntityDataRepository>()
        val sourceEntity = mockk<NamedEntityData>()
        val targetEntity = mockk<NamedEntityData>()

        every { sourceEntity.labels() } returns setOf("BlackmawUser")
        every { targetEntity.labels() } returns setOf("Contact")
        every { repo.findById("user-rod") } returns sourceEntity
        every { repo.findById("contact-tom") } returns targetEntity
        every { repo.mergeRelationship(any(), any(), any()) } just Runs

        return repo
    }

    @Nested
    inner class SynthesizeAndUpdateDescriptionsTests {

        @Test
        fun `synthesizes and persists description for entity pair`() {
            val repo = mockRepository()
            val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repo)

            val synthesizer = mockk<RelationshipDescriptionSynthesizer>()
            every { synthesizer.synthesize(any()) } returns SynthesisResult(
                description = "school friend",
                confidence = 0.9,
                sourcePropositionIds = listOf("prop-1"),
            )

            val entityPairs = listOf(
                EntityPairWithPropositions(
                    sourceId = "user-rod",
                    sourceName = "Rod",
                    targetId = "contact-tom",
                    targetName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                    existingDescription = null,
                )
            )

            val result = persister.synthesizeAndUpdateDescriptions(entityPairs, synthesizer)

            assertEquals(1, result.persistedCount)
            assertEquals(0, result.failedCount)

            verify {
                synthesizer.synthesize(match {
                    it.sourceEntityId == "user-rod" &&
                        it.targetEntityName == "Tom" &&
                        it.relationshipType == "KNOWS" &&
                        it.propositions.size == 1
                })
            }
            verify {
                repo.mergeRelationship(
                    match { it.id == "user-rod" },
                    match { it.id == "contact-tom" },
                    match { it.name == "KNOWS" && it.properties["description"] == "school friend" },
                )
            }
        }

        @Test
        fun `skips blank description without persisting`() {
            val repo = mockRepository()
            val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repo)

            val synthesizer = mockk<RelationshipDescriptionSynthesizer>()
            every { synthesizer.synthesize(any()) } returns SynthesisResult(
                description = "",
                confidence = 0.0,
                sourcePropositionIds = emptyList(),
            )

            val entityPairs = listOf(
                EntityPairWithPropositions(
                    sourceId = "user-rod",
                    sourceName = "Rod",
                    targetId = "contact-tom",
                    targetName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                    existingDescription = null,
                )
            )

            val result = persister.synthesizeAndUpdateDescriptions(entityPairs, synthesizer)

            assertEquals(0, result.persistedCount)
            assertEquals(0, result.failedCount)
            verify(exactly = 0) { repo.mergeRelationship(any(), any(), any()) }
        }

        @Test
        fun `handles synthesis exception gracefully`() {
            val repo = mockRepository()
            val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repo)

            val synthesizer = mockk<RelationshipDescriptionSynthesizer>()
            every { synthesizer.synthesize(any()) } throws RuntimeException("LLM unavailable")

            val entityPairs = listOf(
                EntityPairWithPropositions(
                    sourceId = "user-rod",
                    sourceName = "Rod",
                    targetId = "contact-tom",
                    targetName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                    existingDescription = null,
                )
            )

            val result = persister.synthesizeAndUpdateDescriptions(entityPairs, synthesizer)

            assertEquals(0, result.persistedCount)
            assertEquals(1, result.failedCount)
            assertTrue(result.errors[0].contains("LLM unavailable"))
        }

        @Test
        fun `processes multiple entity pairs independently`() {
            val repo = mockRepository()

            // Add entries for second pair
            val entity2 = mockk<NamedEntityData>()
            every { entity2.labels() } returns setOf("Contact")
            every { repo.findById("contact-alice") } returns entity2

            val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repo)

            val synthesizer = mockk<RelationshipDescriptionSynthesizer>()
            every { synthesizer.synthesize(match { it.targetEntityName == "Tom" }) } returns
                SynthesisResult("school friend", 0.9, listOf("prop-1"))
            every { synthesizer.synthesize(match { it.targetEntityName == "Alice" }) } returns
                SynthesisResult("colleague", 0.85, listOf("prop-2"))

            val entityPairs = listOf(
                EntityPairWithPropositions(
                    sourceId = "user-rod",
                    sourceName = "Rod",
                    targetId = "contact-tom",
                    targetName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                    existingDescription = null,
                ),
                EntityPairWithPropositions(
                    sourceId = "user-rod",
                    sourceName = "Rod",
                    targetId = "contact-alice",
                    targetName = "Alice",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition(id = "prop-2", text = "Rod works with Alice")),
                    existingDescription = null,
                ),
            )

            val result = persister.synthesizeAndUpdateDescriptions(entityPairs, synthesizer)

            assertEquals(2, result.persistedCount)
            assertEquals(0, result.failedCount)
        }

        @Test
        fun `passes existing description to synthesizer`() {
            val repo = mockRepository()
            val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repo)

            val requestSlot = slot<SynthesisRequest>()
            val synthesizer = mockk<RelationshipDescriptionSynthesizer>()
            every { synthesizer.synthesize(capture(requestSlot)) } returns
                SynthesisResult("updated school friend", 0.95, listOf("prop-1"))

            val entityPairs = listOf(
                EntityPairWithPropositions(
                    sourceId = "user-rod",
                    sourceName = "Rod",
                    targetId = "contact-tom",
                    targetName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                    existingDescription = "old pal",
                )
            )

            persister.synthesizeAndUpdateDescriptions(entityPairs, synthesizer)

            assertEquals("old pal", requestSlot.captured.existingDescription)
        }
    }

    @Nested
    inner class PersistRelationshipTests {

        @Test
        fun `persists relationship with correct properties`() {
            val repo = mockRepository()
            val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repo)

            val relationshipSlot = slot<RelationshipData>()
            every { repo.mergeRelationship(any(), any(), capture(relationshipSlot)) } just Runs

            val relationship = ProjectedRelationship(
                sourceId = "user-rod",
                targetId = "contact-tom",
                type = "KNOWS",
                confidence = 0.85,
                description = "school friend",
                sourcePropositionIds = listOf("prop-1"),
            )

            persister.persistRelationship(relationship)

            assertEquals("KNOWS", relationshipSlot.captured.name)
            assertEquals(0.85, relationshipSlot.captured.properties["confidence"])
            assertEquals("school friend", relationshipSlot.captured.properties["description"])
            assertEquals(listOf("prop-1"), relationshipSlot.captured.properties["sourcePropositions"])
        }

        @Test
        fun `uses Entity type when entity not found in repository`() {
            val repo = mockk<NamedEntityDataRepository>()
            every { repo.findById(any()) } returns null
            every { repo.mergeRelationship(any(), any(), any()) } just Runs

            val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repo)
            val sourceSlot = slot<RetrievableIdentifier>()
            val targetSlot = slot<RetrievableIdentifier>()
            every { repo.mergeRelationship(capture(sourceSlot), capture(targetSlot), any()) } just Runs

            persister.persistRelationship(
                ProjectedRelationship(
                    sourceId = "unknown-1",
                    targetId = "unknown-2",
                    type = "KNOWS",
                    confidence = 0.8,
                    sourcePropositionIds = listOf("p-1"),
                )
            )

            assertEquals("Entity", sourceSlot.captured.type)
            assertEquals("Entity", targetSlot.captured.type)
        }
    }
}
