package com.embabel.dice.text2graph.builder

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.text2graph.SuggestedRelationship
import com.embabel.dice.text2graph.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

data class Animal(val name: String, val breed: String)

data class Person(
    val name: String,
    val age: Int,
    val pets: List<Animal> = emptyList(),
)

class GraphProjectorTest {

    private val schema = DataDictionary.fromClasses(Person::class.java, Animal::class.java)
    private val projector = InMemoryObjectGraphGraphProjector()

    @Test
    fun `should project entities to domain objects`() {
        val alice = SimpleNamedEntityData(
            id = "alice-id",
            name = "Alice",
            description = "A person",
            labels = setOf("Person"),
            properties = mapOf("age" to 30)
        )

        val rex = SimpleNamedEntityData(
            id = "rex-id",
            name = "Rex",
            description = "A dog",
            labels = setOf("Animal"),
            properties = mapOf("breed" to "German Shepherd")
        )

        val delta = createDelta(
            entities = listOf(alice, rex),
            relationships = emptyList()
        )

        val objects = projector.project(schema, delta)

        assertEquals(2, objects.size)
        val people = objects.filterIsInstance<Person>()
        val animals = objects.filterIsInstance<Animal>()

        assertEquals(1, people.size)
        assertEquals(1, animals.size)

        val person = people.first()
        assertEquals("Alice", person.name)
        assertEquals(30, person.age)

        val animal = animals.first()
        assertEquals("Rex", animal.name)
        assertEquals("German Shepherd", animal.breed)
    }

    @Test
    fun `should share same entity instance across multiple references`() {
        val alice = SimpleNamedEntityData(
            id = "alice-id",
            name = "Alice",
            description = "A person",
            labels = setOf("Person"),
            properties = mapOf("age" to 30)
        )

        val bob = SimpleNamedEntityData(
            id = "bob-id",
            name = "Bob",
            description = "Another person",
            labels = setOf("Person"),
            properties = mapOf("age" to 25)
        )

        val rex = SimpleNamedEntityData(
            id = "rex-id",
            name = "Rex",
            description = "A dog",
            labels = setOf("Animal"),
            properties = mapOf("breed" to "German Shepherd")
        )

        val delta = createDelta(
            entities = listOf(alice, bob, rex),
            relationships = listOf(
                SuggestedRelationship(
                    sourceId = "alice-id",
                    targetId = "rex-id",
                    type = "OWNS_PET",
                    description = "Alice owns Rex"
                ),
                SuggestedRelationship(
                    sourceId = "bob-id",
                    targetId = "rex-id",
                    type = "OWNS_PET",
                    description = "Bob also owns Rex"
                )
            )
        )

        val objects = projector.project(schema, delta)
        val people = objects.filterIsInstance<Person>()

        assertEquals(2, people.size)

        // Find Alice and Bob
        val alicePerson = people.find { it.name == "Alice" }
        val bobPerson = people.find { it.name == "Bob" }

        assertNotNull(alicePerson)
        assertNotNull(bobPerson)

        // Both should have Rex in their pets list
        assertEquals(1, alicePerson!!.pets.size)
        assertEquals(1, bobPerson!!.pets.size)
        assertEquals("Rex", alicePerson.pets.first().name)
        assertEquals("Rex", bobPerson.pets.first().name)

        // Both should reference the same Rex instance
        assertSame(alicePerson.pets.first(), bobPerson.pets.first())
    }

    @Test
    fun `should handle entities with no relationships`() {
        val charlie = SimpleNamedEntityData(
            id = "charlie-id",
            name = "Charlie",
            description = "A person with no pets",
            labels = setOf("Person"),
            properties = mapOf("age" to 35)
        )

        val delta = createDelta(
            entities = listOf(charlie),
            relationships = emptyList()
        )

        val objects = projector.project(schema, delta)
        val people = objects.filterIsInstance<Person>()

        assertEquals(1, people.size)
        val person = people.first()
        assertEquals("Charlie", person.name)
        assertEquals(35, person.age)
        assertTrue(person.pets.isEmpty())
    }

    @Test
    fun `should warn about unresolved entities without error`() {
        val alice = SimpleNamedEntityData(
            id = "alice-id",
            name = "Alice",
            description = "A person",
            labels = setOf("Person"),
            properties = mapOf("age" to 30)
        )

        val unknown = SimpleNamedEntityData(
            id = "unknown-id",
            name = "Unknown",
            description = "An unknown type",
            labels = setOf("UnknownType"),
            properties = emptyMap()
        )

        val delta = createDelta(
            entities = listOf(alice, unknown),
            relationships = emptyList()
        )

        // Should not throw an error, just warn
        val objects = projector.project(schema, delta)

        // Alice should be present
        val person = objects.filterIsInstance<Person>().firstOrNull()
        assertNotNull(person)
        assertEquals("Alice", person!!.name)

        // The unknown entity might or might not be instantiated depending on schema behavior
        // The important thing is no error was thrown
        assertTrue(objects.isNotEmpty())
    }

    private fun createDelta(
        entities: List<NamedEntityData>,
        relationships: List<SuggestedRelationship>
    ): KnowledgeGraphDelta {
        val chunk = Chunk(
            id = "test-chunk",
            text = "Test text",
            metadata = emptyMap(),
            parentId = "",
        )

        val entityMerges: Merges<com.embabel.dice.common.SuggestedEntityResolution, NamedEntityData> = Merges(
            merges = entities.map { entity ->
                val suggestedEntity = SuggestedEntity(
                    labels = entity.labels().toList(),
                    name = entity.name,
                    summary = entity.description
                )
                Merge<com.embabel.dice.common.SuggestedEntityResolution, NamedEntityData>(
                    resolution = _root_ide_package_.com.embabel.dice.common.NewEntity(suggestedEntity),
                    convergenceTarget = entity
                )
            }
        )

        val relationshipMerges: Merges<SuggestedRelationshipResolution, RelationshipInstance> = Merges(
            merges = relationships.map { relationship ->
                Merge<SuggestedRelationshipResolution, RelationshipInstance>(
                    resolution = NewRelationship(relationship),
                    convergenceTarget = relationship
                )
            }
        )

        return KnowledgeGraphDelta(
            chunkIds = setOf(chunk.id),
            entityMerges = entityMerges,
            relationshipMerges = relationshipMerges
        )
    }
}
