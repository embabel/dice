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
package com.embabel.dice.entity

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.common.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EntityResolutionServiceTest {

    private lateinit var entityResolver: EntityResolver
    private lateinit var repository: NamedEntityDataRepository
    private lateinit var schema: DataDictionary
    private lateinit var service: EntityResolutionService

    @BeforeEach
    fun setUp() {
        entityResolver = mockk()
        repository = mockk(relaxed = true)
        schema = mockk()
        service = EntityResolutionService(entityResolver, repository, schema)
    }

    private fun suggestedEntity(name: String, labels: List<String> = emptyList()) =
        SuggestedEntity(
            labels = labels,
            name = name,
            summary = "",
            chunkId = EntityResolutionService.ASSERTION_CHUNK_ID,
        )

    private fun existingEntityData(id: String, name: String, labels: Set<String> = setOf("Entity")) =
        SimpleNamedEntityData(
            id = id,
            name = name,
            description = "",
            labels = labels,
            properties = emptyMap(),
        )

    private fun mockResolutions(vararg resolutions: SuggestedEntityResolution) {
        every { entityResolver.resolve(any(), any()) } returns Resolutions(
            chunkIds = setOf(EntityResolutionService.ASSERTION_CHUNK_ID),
            resolutions = resolutions.toList(),
        )
    }

    @Nested
    inner class EntityResolutionTests {

        @Test
        fun `new entity is saved to repository`() {
            val suggested = suggestedEntity("Alice", listOf("Person"))
            mockResolutions(NewEntity(suggested))

            val result = service.resolve(EntityAssertionRequest(
                entities = listOf(EntityAssertion(name = "Alice", labels = listOf("Person")))
            ))

            assertEquals(1, result.resolutions.size)
            assertEquals(ResolutionOutcome.NEW, result.resolutions[0].resolution)
            assertEquals("Alice", result.resolutions[0].name)
            assertTrue(result.resolutions[0].labels.contains("Person"))
            verify(exactly = 1) { repository.save(any()) }
        }

        @Test
        fun `existing entity is updated in repository`() {
            val suggested = suggestedEntity("Alice", listOf("Engineer"))
            val existing = existingEntityData("alice-123", "Alice", setOf("Person"))
            mockResolutions(ExistingEntity(suggested, existing))

            val result = service.resolve(EntityAssertionRequest(
                entities = listOf(EntityAssertion(name = "Alice", labels = listOf("Engineer")))
            ))

            assertEquals(1, result.resolutions.size)
            assertEquals(ResolutionOutcome.EXISTING, result.resolutions[0].resolution)
            assertEquals("alice-123", result.resolutions[0].entityId)
            verify(exactly = 1) { repository.update(any()) }
            verify(exactly = 0) { repository.save(any()) }
        }

        @Test
        fun `reference-only entity is not persisted`() {
            val suggested = suggestedEntity("System User")
            val existing = existingEntityData("sys-1", "System User", setOf("User"))
            mockResolutions(ReferenceOnlyEntity(suggested, existing))

            val result = service.resolve(EntityAssertionRequest(
                entities = listOf(EntityAssertion(name = "System User"))
            ))

            assertEquals(1, result.resolutions.size)
            assertEquals(ResolutionOutcome.REFERENCE_ONLY, result.resolutions[0].resolution)
            assertEquals("sys-1", result.resolutions[0].entityId)
            assertTrue(result.resolutions[0].labels.contains("User"))
            verify(exactly = 0) { repository.save(any()) }
            verify(exactly = 0) { repository.update(any()) }
        }

        @Test
        fun `vetoed entity is not persisted`() {
            val suggested = suggestedEntity("Banned", listOf("RestrictedType"))
            mockResolutions(VetoedEntity(suggested))

            val result = service.resolve(EntityAssertionRequest(
                entities = listOf(EntityAssertion(name = "Banned", labels = listOf("RestrictedType")))
            ))

            assertEquals(1, result.resolutions.size)
            assertEquals(ResolutionOutcome.VETOED, result.resolutions[0].resolution)
            assertEquals("", result.resolutions[0].entityId)
            assertEquals(emptySet<String>(), result.resolutions[0].labels)
            verify(exactly = 0) { repository.save(any()) }
            verify(exactly = 0) { repository.update(any()) }
        }

        @Test
        fun `labels from EntityAssertion flow through to SuggestedEntity`() {
            val labels = listOf("Person", "Engineer", "Manager")
            mockResolutions(NewEntity(suggestedEntity("Alice", labels)))

            service.resolve(EntityAssertionRequest(
                entities = listOf(EntityAssertion(name = "Alice", labels = labels))
            ))

            verify {
                entityResolver.resolve(
                    match { se ->
                        val suggested = se.suggestedEntities[0]
                        suggested.labels == labels && suggested.name == "Alice"
                    },
                    any(),
                )
            }
        }

        @Test
        fun `properties and description pass through to SuggestedEntity`() {
            val props = mapOf("age" to 30, "role" to "lead")
            mockResolutions(NewEntity(suggestedEntity("Alice")))

            service.resolve(EntityAssertionRequest(
                entities = listOf(EntityAssertion(
                    name = "Alice",
                    description = "Senior engineer",
                    properties = props,
                ))
            ))

            verify {
                entityResolver.resolve(
                    match { se ->
                        val suggested = se.suggestedEntities[0]
                        suggested.summary == "Senior engineer" &&
                            suggested.properties == props
                    },
                    any(),
                )
            }
        }

        @Test
        fun `mixed resolution types are handled correctly`() {
            val newSuggested = suggestedEntity("Alice", listOf("Person"))
            val existingSuggested = suggestedEntity("Bob", listOf("Person"))
            val existingData = existingEntityData("bob-1", "Bob", setOf("Person"))
            val vetoedSuggested = suggestedEntity("Banned", listOf("Restricted"))

            mockResolutions(
                NewEntity(newSuggested),
                ExistingEntity(existingSuggested, existingData),
                VetoedEntity(vetoedSuggested),
            )

            val result = service.resolve(EntityAssertionRequest(
                entities = listOf(
                    EntityAssertion(name = "Alice", labels = listOf("Person")),
                    EntityAssertion(name = "Bob", labels = listOf("Person")),
                    EntityAssertion(name = "Banned", labels = listOf("Restricted")),
                )
            ))

            assertEquals(3, result.resolutions.size)
            assertEquals(ResolutionOutcome.NEW, result.resolutions.first { it.name == "Alice" }.resolution)
            assertEquals(ResolutionOutcome.EXISTING, result.resolutions.first { it.name == "Bob" }.resolution)
            assertEquals(ResolutionOutcome.VETOED, result.resolutions.first { it.name == "Banned" }.resolution)
            verify(exactly = 1) { repository.save(any()) }
            verify(exactly = 1) { repository.update(any()) }
        }
    }

    @Nested
    inner class RelationshipTests {

        @Test
        fun `relationship uses resolved entity IDs`() {
            val aliceSuggested = suggestedEntity("Alice", listOf("Person"))
            val bobSuggested = suggestedEntity("Bob", listOf("Person"))
            val bobExisting = existingEntityData("bob-456", "Bob", setOf("Person"))

            mockResolutions(
                NewEntity(aliceSuggested),
                ExistingEntity(bobSuggested, bobExisting),
            )

            val sourceSlot = slot<RetrievableIdentifier>()
            val targetSlot = slot<RetrievableIdentifier>()
            every { repository.mergeRelationship(capture(sourceSlot), capture(targetSlot), any()) } just Runs

            service.resolve(EntityAssertionRequest(
                entities = listOf(
                    EntityAssertion(name = "Alice", labels = listOf("Person")),
                    EntityAssertion(name = "Bob", labels = listOf("Person")),
                ),
                relationships = listOf(
                    RelationshipAssertion(source = "Alice", target = "Bob", type = "KNOWS"),
                ),
            ))

            // Source should use Alice's generated ID (from NewEntity), target should use Bob's existing ID
            assertEquals("bob-456", targetSlot.captured.id)
            verify(exactly = 1) { repository.mergeRelationship(any(), any(), any()) }
        }

        @Test
        fun `vetoed source entity prevents relationship persistence`() {
            val aliceSuggested = suggestedEntity("Alice", listOf("Person"))
            val bannedSuggested = suggestedEntity("Banned", listOf("Restricted"))

            mockResolutions(
                NewEntity(aliceSuggested),
                VetoedEntity(bannedSuggested),
            )

            val result = service.resolve(EntityAssertionRequest(
                entities = listOf(
                    EntityAssertion(name = "Alice", labels = listOf("Person")),
                    EntityAssertion(name = "Banned", labels = listOf("Restricted")),
                ),
                relationships = listOf(
                    RelationshipAssertion(source = "Banned", target = "Alice", type = "KNOWS"),
                ),
            ))

            assertEquals(1, result.relationships.size)
            assertFalse(result.relationships[0].persisted)
            verify(exactly = 0) { repository.mergeRelationship(any(), any(), any()) }
        }

        @Test
        fun `vetoed target entity prevents relationship persistence`() {
            val aliceSuggested = suggestedEntity("Alice", listOf("Person"))
            val bannedSuggested = suggestedEntity("Banned", listOf("Restricted"))

            mockResolutions(
                NewEntity(aliceSuggested),
                VetoedEntity(bannedSuggested),
            )

            val result = service.resolve(EntityAssertionRequest(
                entities = listOf(
                    EntityAssertion(name = "Alice", labels = listOf("Person")),
                    EntityAssertion(name = "Banned", labels = listOf("Restricted")),
                ),
                relationships = listOf(
                    RelationshipAssertion(source = "Alice", target = "Banned", type = "KNOWS"),
                ),
            ))

            assertEquals(1, result.relationships.size)
            assertFalse(result.relationships[0].persisted)
            verify(exactly = 0) { repository.mergeRelationship(any(), any(), any()) }
        }

        @Test
        fun `relationship description and properties are passed through`() {
            val aliceSuggested = suggestedEntity("Alice", listOf("Person"))
            val bobSuggested = suggestedEntity("Bob", listOf("Person"))

            mockResolutions(
                NewEntity(aliceSuggested),
                NewEntity(bobSuggested),
            )

            val relDataSlot = slot<RelationshipData>()
            every { repository.mergeRelationship(any(), any(), capture(relDataSlot)) } just Runs

            service.resolve(EntityAssertionRequest(
                entities = listOf(
                    EntityAssertion(name = "Alice", labels = listOf("Person")),
                    EntityAssertion(name = "Bob", labels = listOf("Person")),
                ),
                relationships = listOf(
                    RelationshipAssertion(
                        source = "Alice",
                        target = "Bob",
                        type = "WORKS_WITH",
                        description = "Same team",
                        properties = mapOf("since" to 2020),
                    ),
                ),
            ))

            assertEquals("WORKS_WITH", relDataSlot.captured.name)
            assertEquals("Same team", relDataSlot.captured.properties["description"])
            assertEquals(2020, relDataSlot.captured.properties["since"])
        }

        @Test
        fun `unknown entity name in relationship results in not persisted`() {
            val aliceSuggested = suggestedEntity("Alice", listOf("Person"))
            mockResolutions(NewEntity(aliceSuggested))

            val result = service.resolve(EntityAssertionRequest(
                entities = listOf(
                    EntityAssertion(name = "Alice", labels = listOf("Person")),
                ),
                relationships = listOf(
                    RelationshipAssertion(source = "Alice", target = "Unknown", type = "KNOWS"),
                ),
            ))

            assertEquals(1, result.relationships.size)
            assertFalse(result.relationships[0].persisted)
            verify(exactly = 0) { repository.mergeRelationship(any(), any(), any()) }
        }
    }
}
