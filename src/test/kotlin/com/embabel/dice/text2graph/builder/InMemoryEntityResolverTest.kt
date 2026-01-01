package com.embabel.dice.text2graph.builder

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.shell.Detective
import com.embabel.dice.shell.Doctor
import com.embabel.dice.shell.Place
import com.embabel.dice.text2graph.*
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.text2graph.support.UseNewEntityMergePolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InMemoryEntityResolverTest {

    private lateinit var resolver: InMemoryEntityResolver
    private val schema = DataDictionary.fromClasses(Person::class.java, Animal::class.java)

    // Schema with Detective and Doctor extending Person (using shell classes for proper hierarchy)
    private val holmesSchema = DataDictionary.fromClasses(
        com.embabel.dice.shell.Person::class.java,
        com.embabel.dice.shell.Animal::class.java,
        Detective::class.java,
        Doctor::class.java,
        Place::class.java,
    )

    @BeforeEach
    fun setUp() {
        resolver = InMemoryEntityResolver()
    }

    @Test
    fun `should create new entity when no match exists`() {
        val suggested = createSuggestedEntities(
            SuggestedEntity(
                labels = listOf("Person"),
                name = "Alice",
                summary = "A person"
            )
        )

        val resolutions = resolver.resolve(suggested, schema)

        assertEquals(1, resolutions.resolutions.size)
        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
        assertEquals("Alice", resolutions.resolutions[0].recommended?.name)
        assertEquals(1, resolver.size())
    }

    @Test
    fun `should match existing entity with exact same name`() {
        // First, add an entity
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person")
        )
        resolver.resolve(first, schema)

        // Then try to add the same entity
        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "Same person")
        )
        val resolutions = resolver.resolve(second, schema)

        assertEquals(1, resolutions.resolutions.size)
        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
        assertEquals(1, resolver.size()) // Should not create a new entity
    }

    @Test
    fun `should match with case-insensitive name`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person")
        )
        resolver.resolve(first, schema)

        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "ALICE", summary = "Same person")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
        assertEquals(1, resolver.size())
    }

    @Test
    fun `should match with different case variations`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Sherlock Holmes", summary = "A detective")
        )
        resolver.resolve(first, schema)

        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "sherlock holmes", summary = "Same detective")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
        assertEquals(1, resolver.size())
    }

    @Test
    fun `should match when title is added or removed`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Watson", summary = "A doctor")
        )
        resolver.resolve(first, schema)

        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Dr. Watson", summary = "Same doctor")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
        assertEquals(1, resolver.size())
    }

    @Test
    fun `should match when suffix is added or removed`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "John Smith Jr.", summary = "A person")
        )
        resolver.resolve(first, schema)

        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "John Smith", summary = "Same person")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
        assertEquals(1, resolver.size())
    }

    @Test
    fun `should match with small typos using Levenshtein distance`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Sherlock", summary = "A detective")
        )
        resolver.resolve(first, schema)

        // "Sherlok" is 1 character different from "Sherlock" (8 chars, 0.125 ratio < 0.2)
        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Sherlok", summary = "Same detective")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
        assertEquals(1, resolver.size())
    }

    @Test
    fun `should not match with too many differences`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person")
        )
        resolver.resolve(first, schema)

        // "Bobby" is too different from "Alice"
        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Bobby", summary = "Different person")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
        assertEquals(2, resolver.size())
    }

    @Test
    fun `should not match entities with incompatible labels`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Rex", summary = "A person named Rex")
        )
        resolver.resolve(first, schema)

        // Same name but different label
        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Animal"), name = "Rex", summary = "A dog named Rex")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
        assertEquals(2, resolver.size())
    }

    @Test
    fun `should match entities with case-insensitive labels`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person")
        )
        resolver.resolve(first, schema)

        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("PERSON"), name = "Alice", summary = "Same person")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
        assertEquals(1, resolver.size())
    }

    @Test
    fun `should not apply fuzzy matching to short names`() {
        val resolver = InMemoryEntityResolver(InMemoryEntityResolver.Config(minLengthForFuzzy = 4))

        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "A person")
        )
        resolver.resolve(first, schema)

        // "Rob" is 1 char different from "Bob" but should not match due to short length
        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Rob", summary = "Different person")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
        assertEquals(2, resolver.size())
    }

    @Test
    fun `should handle multiple entities in single resolution`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person"),
            SuggestedEntity(labels = listOf("Animal"), name = "Rex", summary = "A dog")
        )
        resolver.resolve(first, schema)

        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "alice", summary = "Same person"),
            SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "New person"),
            SuggestedEntity(labels = listOf("Animal"), name = "REX", summary = "Same dog")
        )
        val resolutions = resolver.resolve(second, schema)

        assertEquals(3, resolutions.resolutions.size)
        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity) // alice -> Alice
        assertTrue(resolutions.resolutions[1] is com.embabel.dice.common.NewEntity) // Bob is new
        assertTrue(resolutions.resolutions[2] is com.embabel.dice.common.ExistingEntity) // REX -> Rex
        assertEquals(3, resolver.size())
    }

    @Test
    fun `should clear resolved entities`() {
        val suggested = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person")
        )
        resolver.resolve(suggested, schema)
        assertEquals(1, resolver.size())

        resolver.clear()
        assertEquals(0, resolver.size())

        // Same entity should be new after clear
        val resolutions = resolver.resolve(suggested, schema)
        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
    }

    @Test
    fun `should use configurable distance ratio`() {
        // Use a very strict distance ratio
        val strictResolver = InMemoryEntityResolver(InMemoryEntityResolver.Config(maxDistanceRatio = 0.05))

        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Sherlock", summary = "A detective")
        )
        strictResolver.resolve(first, schema)

        // With strict ratio, "Sherlok" should NOT match "Sherlock"
        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Sherlok", summary = "Different")
        )
        val resolutions = strictResolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
    }

    @Test
    fun `should preserve chunk IDs in resolution`() {
        val chunkIds = setOf("chunk-1", "chunk-2")
        val suggested = SuggestedEntities(
            chunkIds = chunkIds,
            suggestedEntities = listOf(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person")
            )
        )

        val resolutions = resolver.resolve(suggested, schema)

        assertEquals(chunkIds, resolutions.chunkIds)
    }

    @Test
    fun `should match partial names - last name only`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Sherlock Holmes", summary = "A detective")
        )
        resolver.resolve(first, schema)

        // "Holmes" should match "Sherlock Holmes"
        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Holmes", summary = "Same detective")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
        assertEquals(1, resolver.size())
    }

    @Test
    fun `should match partial names - Victor Savage to Savage`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Victor Savage", summary = "A victim")
        )
        resolver.resolve(first, schema)

        // "Savage" should match "Victor Savage"
        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Savage", summary = "Same victim")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
        assertEquals(1, resolver.size())
    }

    @Test
    fun `should not match short partial names`() {
        val first = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "John Doe", summary = "A person")
        )
        resolver.resolve(first, schema)

        // "Doe" is only 3 chars, should not match
        val second = createSuggestedEntities(
            SuggestedEntity(labels = listOf("Person"), name = "Doe", summary = "Different person")
        )
        val resolutions = resolver.resolve(second, schema)

        assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
        assertEquals(2, resolver.size())
    }

    private fun createSuggestedEntities(vararg entities: SuggestedEntity): SuggestedEntities {
        return SuggestedEntities(
            chunkIds = setOf("test-chunk"),
            suggestedEntities = entities.toList()
        )
    }

    /**
     * Tests for cross-type hierarchy matching (e.g., Detective extends Person).
     */
    @Nested
    inner class TypeHierarchyTests {

        @BeforeEach
        fun setUp() {
            resolver = InMemoryEntityResolver()
        }

        /**
         * Verifies the schema has the correct type hierarchy.
         */
        @Test
        fun `schema should recognize Detective as subtype of Person`() {
            val detectiveType = holmesSchema.domainTypeForLabels(setOf("Detective"))
            val personType = holmesSchema.domainTypeForLabels(setOf("Person"))

            assertNotNull(detectiveType, "Detective should be in schema")
            assertNotNull(personType, "Person should be in schema")

            // Detective should have Person as parent
            val hasPersonParent = detectiveType!!.parents.any {
                it.name.contains("Person")
            }
            assertTrue(
                hasPersonParent,
                "Detective should have Person as parent. Parents: ${detectiveType.parents.map { it.name }}"
            )
        }

        /**
         * Bug reproduction: Person "Sherlock Holmes" first, then Detective "Holmes".
         * They should merge because Detective extends Person.
         */
        @Test
        fun `should merge Person Sherlock Holmes with Detective Holmes`() {
            // First chunk: "Sherlock Holmes" as Person
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Sherlock Holmes",
                        summary = "A remarkable lodger"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            assertEquals(1, chunk1Resolutions.resolutions.size)
            assertTrue(chunk1Resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
            val originalId = chunk1Resolutions.resolutions[0].recommended!!.id

            // Second chunk: "Holmes" as Detective
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Detective"),
                        name = "Holmes",
                        summary = "The detective solving the case"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            assertEquals(1, chunk2Resolutions.resolutions.size)
            assertTrue(
                chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity,
                "Detective 'Holmes' should match existing Person 'Sherlock Holmes' because Detective extends Person"
            )
            assertEquals(
                originalId,
                chunk2Resolutions.resolutions[0].recommended!!.id,
                "Should resolve to the same entity ID"
            )
            assertEquals(1, resolver.size(), "Should only have 1 entity in resolver")
        }

        /**
         * Reverse order: Detective first, then Person.
         */
        @Test
        fun `should merge Detective Holmes with Person Sherlock Holmes`() {
            // First chunk: "Holmes" as Detective
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Detective"),
                        name = "Holmes",
                        summary = "The detective"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            assertEquals(1, chunk1Resolutions.resolutions.size)
            assertTrue(chunk1Resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
            val originalId = chunk1Resolutions.resolutions[0].recommended!!.id

            // Second chunk: "Sherlock Holmes" as Person
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Sherlock Holmes",
                        summary = "The lodger"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            assertEquals(1, chunk2Resolutions.resolutions.size)
            assertTrue(
                chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity,
                "Person 'Sherlock Holmes' should match existing Detective 'Holmes' because Detective extends Person"
            )
            assertEquals(
                originalId,
                chunk2Resolutions.resolutions[0].recommended!!.id,
                "Should resolve to the same entity ID"
            )
            assertEquals(1, resolver.size(), "Should only have 1 entity in resolver")
        }

        /**
         * Test with fully qualified labels (as they appear in stored entities).
         */
        @Test
        fun `should handle fully qualified labels in stored entities`() {
            // First, manually check what labels look like after resolution
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Sherlock Holmes",
                        summary = "A person"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            val storedEntity = chunk1Resolutions.resolutions[0].recommended!!

            println("Stored entity labels: ${storedEntity.labels()}")

            // Second chunk with Detective label
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Detective"),
                        name = "Holmes",
                        summary = "A detective"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            assertTrue(
                chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity,
                "Should match even if stored labels are fully qualified"
            )
        }

        /**
         * Bug reproduction: LLM returns fully qualified labels like com.embabel.dice.shell.Person
         */
        @Test
        fun `should match when LLM returns fully qualified labels`() {
            // First verify schema can find types with both simple and fully qualified names
            val personBySimple = holmesSchema.domainTypeForLabels(setOf("Person"))
            val personByFull = holmesSchema.domainTypeForLabels(setOf("com.embabel.dice.shell.Person"))
            val detectiveBySimple = holmesSchema.domainTypeForLabels(setOf("Detective"))
            val detectiveByFull = holmesSchema.domainTypeForLabels(setOf("com.embabel.dice.shell.Detective"))

            println("Person by simple: $personBySimple")
            println("Person by full: $personByFull")
            println("Detective by simple: $detectiveBySimple")
            println("Detective by full: $detectiveByFull")

            // First chunk: LLM returns fully qualified Person label
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("com.embabel.dice.shell.Person"),
                        name = "Sherlock Holmes",
                        summary = "A remarkable lodger"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            assertEquals(1, chunk1Resolutions.resolutions.size)
            assertTrue(chunk1Resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
            val originalId = chunk1Resolutions.resolutions[0].recommended!!.id

            println("Stored entity labels: ${chunk1Resolutions.resolutions[0].recommended!!.labels()}")

            // Second chunk: LLM returns fully qualified Detective label
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("com.embabel.dice.shell.Detective"),
                        name = "Holmes",
                        summary = "The detective"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            println("Resolution type: ${chunk2Resolutions.resolutions[0]::class.simpleName}")
            println("Resolver size: ${resolver.size()}")

            assertEquals(1, chunk2Resolutions.resolutions.size)
            assertTrue(
                chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity,
                "Detective 'Holmes' should match Person 'Sherlock Holmes' even with fully qualified labels"
            )
            assertEquals(
                originalId,
                chunk2Resolutions.resolutions[0].recommended!!.id,
                "Should resolve to the same entity"
            )
            assertEquals(1, resolver.size(), "Should only have 1 entity")
        }

        /**
         * Verify that when a Person is later identified as a Detective,
         * the merged entity gets both labels.
         */
        @Test
        fun `merged entity should have both Person and Detective labels`() {
            // First chunk: Person
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Sherlock Holmes",
                        summary = "A remarkable lodger"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            val originalLabels = chunk1Resolutions.resolutions[0].recommended!!.labels()
            assertTrue(originalLabels.any { it.contains("Person") }, "Should have Person label")

            // Second chunk: Detective
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Detective"),
                        name = "Holmes",
                        summary = "The detective solving the case"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            val resolution = chunk2Resolutions.resolutions[0]
            assertTrue(resolution is com.embabel.dice.common.ExistingEntity, "Should match existing entity")

            val mergedLabels = resolution.recommended!!.labels()
            assertTrue(
                mergedLabels.any { it.contains("Person") },
                "Merged entity should retain Person label. Labels: $mergedLabels"
            )
            assertTrue(
                mergedLabels.any { it.contains("Detective") },
                "Merged entity should have Detective label. Labels: $mergedLabels"
            )
        }

        /**
         * Mixed: One with simple label, one with fully qualified.
         */
        @Test
        fun `should match simple label against fully qualified label`() {
            // First: fully qualified
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("com.embabel.dice.shell.Person"),
                        name = "Dr. Watson",
                        summary = "A doctor"
                    )
                )
            )
            resolver.resolve(chunk1, holmesSchema)

            // Second: simple label
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Watson",
                        summary = "Holmes's friend"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            assertTrue(
                chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity,
                "Simple 'Person' should match fully qualified 'com.embabel.dice.shell.Person'"
            )
            assertEquals(1, resolver.size())
        }
    }

    /**
     * Tests that verify multi-chunk resolution behavior and deduplication.
     * These tests ensure that when the same entity is mentioned across multiple chunks,
     * it is properly merged and not duplicated in the final delta.
     */
    @Nested
    inner class MultiChunkResolutionTests {

        /**
         * Verifies that InMemoryEntityResolver returns exactly one resolution per suggested entity,
         * not multiple resolutions for the same entity.
         */
        @Test
        fun `resolver should return exactly one resolution per suggested entity`() {
            // First chunk: create Watson
            val chunk1 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Dr. Watson", summary = "A doctor")
            )
            val chunk1Resolutions = resolver.resolve(chunk1, schema)

            assertEquals(1, chunk1Resolutions.resolutions.size, "Should have exactly 1 resolution")
            assertTrue(chunk1Resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)

            // Second chunk: Watson again (should match existing)
            val chunk2 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Watson", summary = "The doctor")
            )
            val chunk2Resolutions = resolver.resolve(chunk2, schema)

            assertEquals(1, chunk2Resolutions.resolutions.size, "Should have exactly 1 resolution")
            assertTrue(chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)

            // Third chunk: Watson again
            val chunk3 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Dr. Watson", summary = "Holmes's friend")
            )
            val chunk3Resolutions = resolver.resolve(chunk3, schema)

            assertEquals(1, chunk3Resolutions.resolutions.size, "Should have exactly 1 resolution")
            assertTrue(chunk3Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)

            // Should only have 1 unique entity in the resolver
            assertEquals(1, resolver.size(), "Should only have 1 unique entity")
        }

        /**
         * Simulates the full multi-chunk flow and verifies delta deduplication.
         */
        @Test
        fun `delta should not contain duplicate entities when same entity appears in multiple chunks`() {
            val entityMerges = mutableListOf<Merge<com.embabel.dice.common.SuggestedEntityResolution, NamedEntityData>>()

            // Chunk 1: Watson as new entity
            val chunk1 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Dr. Watson", summary = "A doctor")
            )
            val chunk1Resolutions = resolver.resolve(chunk1, schema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk1Resolutions, schema).merges

            // Chunk 2: Watson again (merged)
            val chunk2 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Watson", summary = "The doctor")
            )
            val chunk2Resolutions = resolver.resolve(chunk2, schema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk2Resolutions, schema).merges

            // Chunk 3: Watson again (merged)
            val chunk3 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Dr. Watson", summary = "Friend of Holmes")
            )
            val chunk3Resolutions = resolver.resolve(chunk3, schema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk3Resolutions, schema).merges

            // Chunk 4: Watson again (merged)
            val chunk4 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Watson", summary = "A loyal companion")
            )
            val chunk4Resolutions = resolver.resolve(chunk4, schema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk4Resolutions, schema).merges

            // Build the delta
            val delta = KnowledgeGraphDelta(
                chunkIds = setOf("chunk1", "chunk2", "chunk3", "chunk4"),
                entityMerges = Merges(entityMerges),
                relationshipMerges = Merges(emptyList())
            )

            // Get all entities - without deduplication this would have 4 Watsons
            val allEntities = delta.newOrModifiedEntities()

            // Count Watson occurrences
            val watsonCount = allEntities.count { it.name == "Dr. Watson" }

            // This test verifies the current behavior
            // If deduplication is working: watsonCount == 1
            // If deduplication is NOT working: watsonCount == 4
            assertEquals(
                1, watsonCount,
                "Watson should appear exactly once in newOrModifiedEntities(). " +
                        "Found $watsonCount instances. All entities: ${allEntities.map { it.name }}"
            )

            // Verify total count
            assertEquals(1, allEntities.size, "Should have exactly 1 entity after deduplication")
        }

        /**
         * Verifies that ExistingEntity resolutions all point to the same original entity ID.
         */
        @Test
        fun `all merged resolutions should reference the same original entity ID`() {
            // Create initial entity
            val chunk1 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Sherlock Holmes", summary = "A detective")
            )
            val chunk1Resolutions = resolver.resolve(chunk1, schema)
            val originalId = chunk1Resolutions.resolutions[0].recommended!!.id

            // Multiple subsequent mentions
            val names = listOf("Holmes", "Sherlock Holmes", "Mr. Holmes", "Sherlock")
            val allIds = mutableListOf(originalId)

            for (name in names) {
                val chunk = createSuggestedEntities(
                    SuggestedEntity(labels = listOf("Person"), name = name, summary = "The detective")
                )
                val resolutions = resolver.resolve(chunk, schema)
                assertEquals(1, resolutions.resolutions.size)

                val resolution = resolutions.resolutions[0]
                assertTrue(resolution is com.embabel.dice.common.ExistingEntity, "$name should match existing Holmes")

                val resolvedId = resolution.recommended!!.id
                allIds.add(resolvedId)

                assertEquals(originalId, resolvedId, "$name should resolve to original ID $originalId")
            }

            // All IDs should be the same
            assertEquals(1, allIds.toSet().size, "All resolutions should have the same ID")
        }

        /**
         * Verifies behavior with multiple different entities across chunks.
         */
        @Test
        fun `should correctly handle multiple different entities across chunks`() {
            val entityMerges = mutableListOf<Merge<com.embabel.dice.common.SuggestedEntityResolution, NamedEntityData>>()

            // Chunk 1: Holmes and Watson
            val chunk1 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Sherlock Holmes", summary = "Detective"),
                SuggestedEntity(labels = listOf("Person"), name = "Dr. Watson", summary = "Doctor")
            )
            val chunk1Resolutions = resolver.resolve(chunk1, schema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk1Resolutions, schema).merges

            // Chunk 2: Watson and Mrs Hudson (Holmes not mentioned)
            val chunk2 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Watson", summary = "The doctor"),
                SuggestedEntity(labels = listOf("Person"), name = "Mrs. Hudson", summary = "Landlady")
            )
            val chunk2Resolutions = resolver.resolve(chunk2, schema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk2Resolutions, schema).merges

            // Chunk 3: All three mentioned
            val chunk3 = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Holmes", summary = "The detective"),
                SuggestedEntity(labels = listOf("Person"), name = "Dr. Watson", summary = "His friend"),
                SuggestedEntity(labels = listOf("Person"), name = "Mrs Hudson", summary = "Their landlady")
            )
            val chunk3Resolutions = resolver.resolve(chunk3, schema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk3Resolutions, schema).merges

            val delta = KnowledgeGraphDelta(
                chunkIds = setOf("chunk1", "chunk2", "chunk3"),
                entityMerges = Merges(entityMerges),
                relationshipMerges = Merges(emptyList())
            )

            val allEntities = delta.newOrModifiedEntities()

            // Should have exactly 3 unique entities
            assertEquals(
                3, allEntities.size,
                "Should have exactly 3 entities (Holmes, Watson, Hudson). Found: ${allEntities.map { it.name }}"
            )

            // Verify each appears exactly once
            val holmesCount = allEntities.count { it.name == "Sherlock Holmes" }
            val watsonCount = allEntities.count { it.name == "Dr. Watson" }
            val hudsonCount = allEntities.count { it.name == "Mrs. Hudson" }

            assertEquals(1, holmesCount, "Holmes should appear once")
            assertEquals(1, watsonCount, "Watson should appear once")
            assertEquals(1, hudsonCount, "Hudson should appear once")
        }
    }

    /**
     * Tests for type upgrade scenarios where an entity is first identified with a
     * general type (e.g., Person) and later with a more specific type (e.g., Doctor).
     *
     * Bug reproduction: In "The Dying Detective", Dr. Ainstree is first seen as Person
     * but should be upgraded to Doctor when context reveals he's a "leading London
     * specialist in tropical disease".
     */
    @Nested
    inner class TypeUpgradeTests {

        @BeforeEach
        fun setUp() {
            resolver = InMemoryEntityResolver()
        }

        /**
         * Sibling types (Doctor and Detective) should be compatible because
         * they share a common parent (Person).
         * Bug reproduction: "Sherlock Holmes" as Detective, then "Holmes" as Doctor.
         */
        @Test
        fun `should merge sibling types that share a common parent`() {
            // First chunk: "Sherlock Holmes" as Detective
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Detective"),
                        name = "Sherlock Holmes",
                        summary = "A brilliant detective"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            assertEquals(1, chunk1Resolutions.resolutions.size)
            assertTrue(chunk1Resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
            val originalId = chunk1Resolutions.resolutions[0].recommended!!.id

            // Second chunk: "Holmes" as Doctor (LLM misclassified)
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Doctor"),
                        name = "Holmes",
                        summary = "A man who is ill"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            assertEquals(1, chunk2Resolutions.resolutions.size)
            assertTrue(
                chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity,
                "Doctor 'Holmes' should match Detective 'Sherlock Holmes' because they share parent Person"
            )
            assertEquals(
                originalId,
                chunk2Resolutions.resolutions[0].recommended!!.id,
                "Should resolve to the same entity ID"
            )
            assertEquals(1, resolver.size(), "Should only have 1 entity")
        }

        /**
         * Bug reproduction: Person first, then Doctor.
         * "Dr. Ainstree" seen first as Person, later identified as Doctor.
         */
        @Test
        fun `should upgrade Person to Doctor when more specific type discovered`() {
            // First chunk: "Dr. Ainstree" as Person (LLM didn't recognize doctor role yet)
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Dr. Ainstree",
                        summary = "A man mentioned in the conversation"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            assertEquals(1, chunk1Resolutions.resolutions.size)
            assertTrue(chunk1Resolutions.resolutions[0] is com.embabel.dice.common.NewEntity)
            val originalId = chunk1Resolutions.resolutions[0].recommended!!.id

            // Second chunk: Same person now identified as Doctor
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Doctor"),
                        name = "Dr. Ainstree",
                        summary = "A leading London specialist in tropical disease"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            assertEquals(1, chunk2Resolutions.resolutions.size)
            assertTrue(
                chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity,
                "Doctor 'Dr. Ainstree' should match existing Person 'Dr. Ainstree' because Doctor extends Person"
            )
            assertEquals(
                originalId,
                chunk2Resolutions.resolutions[0].recommended!!.id,
                "Should resolve to the same entity ID"
            )
            assertEquals(1, resolver.size(), "Should only have 1 entity in resolver")

            // The merged entity should have Doctor label
            val mergedLabels = chunk2Resolutions.resolutions[0].recommended!!.labels()
            assertTrue(
                mergedLabels.any { it.contains("Doctor") },
                "Merged entity should have Doctor label. Labels: $mergedLabels"
            )
        }

        /**
         * Doctor first, then Person - reverse order should also merge.
         */
        @Test
        fun `should merge Doctor with subsequent Person mention`() {
            // First chunk: Doctor
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Doctor"),
                        name = "Dr. Watson",
                        summary = "A general practitioner"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            val originalId = chunk1Resolutions.resolutions[0].recommended!!.id

            // Second chunk: Same person as Person (less specific)
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Watson",
                        summary = "Holmes's friend"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            assertTrue(
                chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity,
                "Person 'Watson' should match existing Doctor 'Dr. Watson'"
            )
            assertEquals(originalId, chunk2Resolutions.resolutions[0].recommended!!.id)
            assertEquals(1, resolver.size())
        }

        /**
         * Multiple doctors mentioned - each should get proper type.
         * Simulates "The Dying Detective" scenario with Dr. Watson, Dr. Ainstree,
         * Sir Jasper Meek, and Penrose Fisher.
         */
        @Test
        fun `should handle multiple doctors with varying type specificity`() {
            val entityMerges = mutableListOf<Merge<com.embabel.dice.common.SuggestedEntityResolution, NamedEntityData>>()

            // Chunk 1: Watson as Doctor, others as Person
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Doctor"),
                        name = "Dr. Watson",
                        summary = "A general practitioner"
                    ),
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Dr. Ainstree",
                        summary = "Someone Watson mentions"
                    ),
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Sir Jasper Meek",
                        summary = "A man mentioned"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk1Resolutions, holmesSchema).merges

            // Chunk 2: More context reveals Ainstree and Meek are doctors
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Doctor"),
                        name = "Dr. Ainstree",
                        summary = "A leading London specialist in tropical disease"
                    ),
                    SuggestedEntity(
                        labels = listOf("Doctor"),
                        name = "Sir Jasper Meek",
                        summary = "One of the best medical men in London"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk2Resolutions, holmesSchema).merges

            // Both should match existing entities
            assertTrue(
                chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity,
                "Doctor 'Dr. Ainstree' should match existing Person 'Dr. Ainstree'"
            )
            assertTrue(
                chunk2Resolutions.resolutions[1] is com.embabel.dice.common.ExistingEntity,
                "Doctor 'Sir Jasper Meek' should match existing Person 'Sir Jasper Meek'"
            )

            // Should only have 3 unique entities
            assertEquals(3, resolver.size(), "Should have 3 entities: Watson, Ainstree, Meek")

            // Verify upgraded labels
            val ainstreeLabels = chunk2Resolutions.resolutions[0].recommended!!.labels()
            assertTrue(
                ainstreeLabels.any { it.contains("Doctor") },
                "Ainstree should have Doctor label after upgrade. Labels: $ainstreeLabels"
            )
        }

        /**
         * Edge case: Same person classified as Person, then Doctor, then Person again.
         * All should merge to the same entity.
         */
        @Test
        fun `should merge multiple type transitions for same entity`() {
            // Round 1: Person
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Penrose Fisher",
                        summary = "A man mentioned"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            val originalId = chunk1Resolutions.resolutions[0].recommended!!.id

            // Round 2: Doctor (upgrade)
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Doctor"),
                        name = "Penrose Fisher",
                        summary = "One of the best medical men in London"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)

            // Round 3: Person again (should still match)
            val chunk3 = SuggestedEntities(
                chunkIds = setOf("chunk3"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Fisher",
                        summary = "Another doctor suggested"
                    )
                )
            )
            val chunk3Resolutions = resolver.resolve(chunk3, holmesSchema)

            // All should resolve to same entity
            assertTrue(chunk2Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
            assertTrue(chunk3Resolutions.resolutions[0] is com.embabel.dice.common.ExistingEntity)
            assertEquals(originalId, chunk2Resolutions.resolutions[0].recommended!!.id)
            assertEquals(originalId, chunk3Resolutions.resolutions[0].recommended!!.id)
            assertEquals(1, resolver.size())
        }

        /**
         * Test the full delta output to verify entities have correct labels after type upgrade.
         */
        @Test
        fun `delta newOrModifiedEntities should reflect upgraded labels`() {
            val entityMerges = mutableListOf<Merge<com.embabel.dice.common.SuggestedEntityResolution, NamedEntityData>>()

            // Chunk 1: Dr. Ainstree as Person
            val chunk1 = SuggestedEntities(
                chunkIds = setOf("chunk1"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Dr. Ainstree",
                        summary = "A man mentioned"
                    )
                )
            )
            val chunk1Resolutions = resolver.resolve(chunk1, holmesSchema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk1Resolutions, holmesSchema).merges

            // Chunk 2: Dr. Ainstree as Doctor (upgrade)
            val chunk2 = SuggestedEntities(
                chunkIds = setOf("chunk2"),
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Doctor"),
                        name = "Dr. Ainstree",
                        summary = "A leading London specialist in tropical disease"
                    )
                )
            )
            val chunk2Resolutions = resolver.resolve(chunk2, holmesSchema)
            entityMerges += UseNewEntityMergePolicy.determineEntities(chunk2Resolutions, holmesSchema).merges

            // Build the delta
            val delta = KnowledgeGraphDelta(
                chunkIds = setOf("chunk1", "chunk2"),
                entityMerges = Merges(entityMerges),
                relationshipMerges = Merges(emptyList())
            )

            val allEntities = delta.newOrModifiedEntities()

            // Should have exactly 1 entity
            assertEquals(1, allEntities.size, "Should have 1 unique entity")

            // The entity should have Doctor label
            val ainstree = allEntities.first()
            assertTrue(
                ainstree.labels().any { it.contains("Doctor") },
                "Final entity in delta should have Doctor label. Labels: ${ainstree.labels()}"
            )
        }
    }
}
