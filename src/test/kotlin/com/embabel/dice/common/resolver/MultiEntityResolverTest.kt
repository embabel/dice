package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.VetoedEntity
import com.embabel.dice.shell.Detective
import com.embabel.dice.shell.Doctor
import com.embabel.dice.shell.Place
import com.embabel.dice.common.Resolutions
import com.embabel.dice.text2graph.builder.Animal
import com.embabel.dice.text2graph.builder.Person
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MultiEntityResolverTest {

    private val schema = DataDictionary.fromClasses(Person::class.java, Animal::class.java)

    private val holmesSchema = DataDictionary.fromClasses(
        com.embabel.dice.shell.Person::class.java,
        com.embabel.dice.shell.Animal::class.java,
        Detective::class.java,
        Doctor::class.java,
        Place::class.java,
    )

    @Test
    fun `should require at least one resolver`() {
        assertThrows<IllegalArgumentException> {
            MultiEntityResolver(emptyList())
        }
    }

    @Test
    fun `should handle empty suggested entities`() {
        val resolver = MultiEntityResolver(listOf(AlwaysCreateEntityResolver))
        val suggested = SuggestedEntities(
            suggestedEntities = emptyList()
        )

        val resolutions = resolver.resolve(suggested, schema)

        assertTrue(resolutions.chunkIds.isEmpty())
        assertTrue(resolutions.resolutions.isEmpty())
    }

    @Test
    fun `should use single resolver when only one provided`() {
        val resolver = MultiEntityResolver(listOf(AlwaysCreateEntityResolver))
        val suggested = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person", chunkId = "test-chunk")
        )

        val resolutions = resolver.resolve(suggested, schema)

        assertEquals(1, resolutions.resolutions.size)
        assertTrue(resolutions.resolutions[0] is NewEntity)
        assertEquals("Alice", resolutions.resolutions[0].recommended?.name)
    }

    @Test
    fun `should preserve chunk IDs in resolution`() {
        val resolver = MultiEntityResolver(listOf(AlwaysCreateEntityResolver))
        val suggested = SuggestedEntities(
            suggestedEntities = listOf(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person", chunkId = "chunk-1"),
                SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "A person", chunkId = "chunk-2")
            )
        )

        val resolutions = resolver.resolve(suggested, schema)

        assertEquals(setOf("chunk-1", "chunk-2"), resolutions.chunkIds)
    }

    @Nested
    inner class MultipleResolverTests {

        @Test
        fun `should use first resolver result when it returns NewEntity and no other finds existing`() {
            val resolver1 = InMemoryEntityResolver()
            val resolver2 = InMemoryEntityResolver()
            val multiResolver = MultiEntityResolver(listOf(resolver1, resolver2))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person", chunkId = "test-chunk")
            )

            val resolutions = multiResolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is NewEntity)
            // First resolver should have created the entity
            assertEquals(1, resolver1.size())
        }

        @Test
        fun `should use second resolver when it finds ExistingEntity`() {
            // Pre-populate the second resolver with an entity
            val resolver1 = InMemoryEntityResolver()
            val resolver2 = InMemoryEntityResolver()

            // Add entity to resolver2 first
            val preExisting = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "Pre-existing", chunkId = "test-chunk")
            )
            resolver2.resolve(preExisting, schema)

            val multiResolver = MultiEntityResolver(listOf(resolver1, resolver2))

            // Now resolve the same entity
            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "New suggestion", chunkId = "test-chunk")
            )
            val resolutions = multiResolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
        }

        @Test
        fun `should stop trying resolvers once ExistingEntity is found`() {
            val resolver1 = InMemoryEntityResolver()
            val resolver2 = InMemoryEntityResolver()
            val resolver3 = InMemoryEntityResolver()

            // Pre-populate resolver2 with the entity
            resolver2.resolve(
                createSuggestedEntities(
                    SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "Pre-existing", chunkId = "test-chunk")
                ),
                schema
            )

            val multiResolver = MultiEntityResolver(listOf(resolver1, resolver2, resolver3))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "New suggestion", chunkId = "test-chunk")
            )
            val resolutions = multiResolver.resolve(suggested, schema)

            assertTrue(resolutions.resolutions[0] is ExistingEntity)
            // resolver1 should have tried (and created NewEntity, though we use resolver2's ExistingEntity)
            assertEquals(1, resolver1.size())
            // resolver2 should still have only the pre-existing entity
            assertEquals(1, resolver2.size())
            // resolver3 should not have been called for this entity
            assertEquals(0, resolver3.size())
        }

        @Test
        fun `should handle multiple entities with different resolutions`() {
            val resolver1 = InMemoryEntityResolver()
            val resolver2 = InMemoryEntityResolver()

            // Pre-populate resolver2 with only one entity
            resolver2.resolve(
                createSuggestedEntities(
                    SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "Pre-existing Bob", chunkId = "test-chunk")
                ),
                schema
            )

            val multiResolver = MultiEntityResolver(listOf(resolver1, resolver2))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "New Alice", chunkId = "test-chunk"),
                SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "Mentioned Bob", chunkId = "test-chunk")
            )
            val resolutions = multiResolver.resolve(suggested, schema)

            assertEquals(2, resolutions.resolutions.size)
            // Alice should be NewEntity (from resolver1)
            assertTrue(resolutions.resolutions[0] is NewEntity)
            assertEquals("Alice", resolutions.resolutions[0].recommended?.name)
            // Bob should be ExistingEntity (from resolver2)
            assertTrue(resolutions.resolutions[1] is ExistingEntity)
            assertEquals("Bob", resolutions.resolutions[1].recommended?.name)
        }

        @Test
        fun `should maintain entity order in results`() {
            val resolver1 = InMemoryEntityResolver()
            val resolver2 = InMemoryEntityResolver()

            // Pre-populate resolver2 with middle entity only
            resolver2.resolve(
                createSuggestedEntities(
                    SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "Pre-existing", chunkId = "test-chunk")
                ),
                schema
            )

            val multiResolver = MultiEntityResolver(listOf(resolver1, resolver2))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "First", chunkId = "test-chunk"),
                SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "Second", chunkId = "test-chunk"),
                SuggestedEntity(labels = listOf("Person"), name = "Charlie", summary = "Third", chunkId = "test-chunk")
            )
            val resolutions = multiResolver.resolve(suggested, schema)

            assertEquals(3, resolutions.resolutions.size)
            assertEquals("Alice", resolutions.resolutions[0].suggested.name)
            assertEquals("Bob", resolutions.resolutions[1].suggested.name)
            assertEquals("Charlie", resolutions.resolutions[2].suggested.name)

            assertTrue(resolutions.resolutions[0] is NewEntity)
            assertTrue(resolutions.resolutions[1] is ExistingEntity)
            assertTrue(resolutions.resolutions[2] is NewEntity)
        }
    }

    @Nested
    inner class ChainedResolutionTests {

        @Test
        fun `should work with InMemoryResolver followed by AlwaysCreate`() {
            val inMemory = InMemoryEntityResolver()
            val multiResolver = MultiEntityResolver(listOf(inMemory, AlwaysCreateEntityResolver))

            // First call - should use InMemoryResolver to create new entity
            val first = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "First mention", chunkId = "test-chunk")
            )
            val firstResolutions = multiResolver.resolve(first, schema)
            assertTrue(firstResolutions.resolutions[0] is NewEntity)

            // Second call - should find existing from InMemoryResolver
            val second = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "Second mention", chunkId = "test-chunk")
            )
            val secondResolutions = multiResolver.resolve(second, schema)
            assertTrue(secondResolutions.resolutions[0] is ExistingEntity)

            assertEquals(1, inMemory.size())
        }

        @Test
        fun `should work with multiple InMemoryResolvers for different contexts`() {
            // Scenario: First resolver for "session" context, second for "database" context
            val sessionResolver = InMemoryEntityResolver()
            val databaseResolver = InMemoryEntityResolver()

            // Simulate database having pre-existing entities
            databaseResolver.resolve(
                createSuggestedEntities(
                    SuggestedEntity(labels = listOf("Person"), name = "Sherlock Holmes", summary = "Famous detective", chunkId = "test-chunk")
                ),
                holmesSchema
            )

            val multiResolver = MultiEntityResolver(listOf(sessionResolver, databaseResolver))

            // Resolve a new entity and an existing one
            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Watson", summary = "Doctor", chunkId = "test-chunk"),
                SuggestedEntity(labels = listOf("Person"), name = "Holmes", summary = "Detective", chunkId = "test-chunk")
            )
            val resolutions = multiResolver.resolve(suggested, holmesSchema)

            // Watson is new (created by session resolver)
            assertTrue(resolutions.resolutions[0] is NewEntity)
            // Holmes matches existing (from database resolver)
            assertTrue(resolutions.resolutions[1] is ExistingEntity)

            // Session resolver is called first with both entities, so it creates NewEntity for both
            // Watson stays as NewEntity, Holmes gets upgraded to ExistingEntity from databaseResolver
            assertEquals(2, sessionResolver.size())
            // Database resolver is called for unresolved entities (Watson and Holmes)
            // Watson gets added as NewEntity, Holmes matches existing
            assertEquals(2, databaseResolver.size())
        }
    }

    @Nested
    inner class VetoedEntityTests {

        @Test
        fun `should propagate VetoedEntity from first resolver`() {
            val vetoingResolver = object : EntityResolver {
                override fun resolve(
                    suggestedEntities: SuggestedEntities,
                    schema: DataDictionary
                ): Resolutions<SuggestedEntityResolution> {
                    return Resolutions(
                        chunkIds = suggestedEntities.chunkIds,
                        resolutions = suggestedEntities.suggestedEntities.map { VetoedEntity(it) }
                    )
                }
            }

            val multiResolver = MultiEntityResolver(listOf(vetoingResolver, AlwaysCreateEntityResolver))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Banned", summary = "Should be vetoed", chunkId = "test-chunk")
            )
            val resolutions = multiResolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is VetoedEntity)
        }
    }

    @Nested
    inner class TypeHierarchyTests {

        @Test
        fun `should find existing across type hierarchy with multiple resolvers`() {
            val resolver1 = InMemoryEntityResolver()
            val resolver2 = InMemoryEntityResolver()

            // Pre-populate resolver2 with Person
            resolver2.resolve(
                createSuggestedEntities(
                    SuggestedEntity(labels = listOf("Person"), name = "Sherlock Holmes", summary = "A lodger", chunkId = "test-chunk")
                ),
                holmesSchema
            )

            val multiResolver = MultiEntityResolver(listOf(resolver1, resolver2))

            // Now suggest as Detective - should match in resolver2
            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Detective"), name = "Holmes", summary = "The detective", chunkId = "test-chunk")
            )
            val resolutions = multiResolver.resolve(suggested, holmesSchema)

            assertTrue(resolutions.resolutions[0] is ExistingEntity)
        }

        @Test
        fun `should merge labels when type is upgraded through chain`() {
            val resolver1 = InMemoryEntityResolver()
            val resolver2 = InMemoryEntityResolver()

            // Pre-populate resolver2 with Person
            resolver2.resolve(
                createSuggestedEntities(
                    SuggestedEntity(labels = listOf("Person"), name = "Dr. Ainstree", summary = "A man", chunkId = "test-chunk")
                ),
                holmesSchema
            )

            val multiResolver = MultiEntityResolver(listOf(resolver1, resolver2))

            // Suggest as Doctor - should match and merge
            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Doctor"), name = "Dr. Ainstree", summary = "A specialist", chunkId = "test-chunk")
            )
            val resolutions = multiResolver.resolve(suggested, holmesSchema)

            assertTrue(resolutions.resolutions[0] is ExistingEntity)
            val labels = resolutions.resolutions[0].recommended?.labels() ?: emptySet()
            assertTrue(labels.any { it.contains("Doctor") }, "Should have Doctor label. Labels: $labels")
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `should handle resolver that always returns ExistingEntity`() {
            val existingEntity = SimpleNamedEntityData(
                id = "fixed-id",
                name = "Fixed Entity",
                description = "Always returned",
                labels = setOf("Person"),
                properties = emptyMap()
            )

            val alwaysExistingResolver = object : EntityResolver {
                override fun resolve(
                    suggestedEntities: SuggestedEntities,
                    schema: DataDictionary
                ): Resolutions<SuggestedEntityResolution> {
                    return Resolutions(
                        chunkIds = suggestedEntities.chunkIds,
                        resolutions = suggestedEntities.suggestedEntities.map {
                            ExistingEntity(it, existingEntity)
                        }
                    )
                }
            }

            val multiResolver = MultiEntityResolver(listOf(alwaysExistingResolver, AlwaysCreateEntityResolver))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Anyone", summary = "Should match fixed", chunkId = "test-chunk")
            )
            val resolutions = multiResolver.resolve(suggested, schema)

            assertTrue(resolutions.resolutions[0] is ExistingEntity)
            assertEquals("fixed-id", resolutions.resolutions[0].recommended?.id)
        }

        @Test
        fun `should handle many entities efficiently`() {
            val resolver1 = InMemoryEntityResolver()
            val resolver2 = InMemoryEntityResolver()

            // Use distinct names to avoid fuzzy matching (Levenshtein distance)
            val existingNames = listOf("Alice", "Bob", "Charlie", "Diana", "Edward")
            val newNames = listOf("Frank", "Grace", "Henry", "Iris", "Jack")

            // Pre-populate resolver2 with some entities
            val preExisting = existingNames.map {
                SuggestedEntity(labels = listOf("Person"), name = it, summary = "Pre-existing $it", chunkId = "pre")
            }
            resolver2.resolve(
                SuggestedEntities(suggestedEntities = preExisting),
                schema
            )

            val multiResolver = MultiEntityResolver(listOf(resolver1, resolver2))

            // Request mix of new and existing
            val allNames = existingNames + newNames
            val suggested = allNames.map {
                SuggestedEntity(labels = listOf("Person"), name = it, summary = "Requested $it", chunkId = "test")
            }
            val resolutions = multiResolver.resolve(
                SuggestedEntities(suggestedEntities = suggested),
                schema
            )

            assertEquals(10, resolutions.resolutions.size)
            // First 5 should be existing (from resolver2)
            for (i in 0..4) {
                assertTrue(resolutions.resolutions[i] is ExistingEntity, "${existingNames[i]} should be existing")
            }
            // Last 5 should be new (from resolver1)
            for (i in 5..9) {
                assertTrue(resolutions.resolutions[i] is NewEntity, "${newNames[i - 5]} should be new")
            }
        }
    }

    private fun createSuggestedEntities(vararg entities: SuggestedEntity): SuggestedEntities {
        return SuggestedEntities(
            suggestedEntities = entities.toList()
        )
    }
}
