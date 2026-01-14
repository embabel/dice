package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.matcher.ChainedEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.ExactNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.FuzzyNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.LabelCompatibilityStrategy
import com.embabel.dice.common.resolver.matcher.NormalizedNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.PartialNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.PredicateEntityMatchingStrategy
import com.embabel.dice.shell.Detective
import com.embabel.dice.shell.Doctor
import com.embabel.dice.shell.Place
import com.embabel.dice.text2graph.builder.Animal
import com.embabel.dice.text2graph.builder.Person
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NamedEntityDataRepositoryEntityResolverTest {

    private lateinit var repository: NamedEntityDataRepository
    private lateinit var resolver: NamedEntityDataRepositoryEntityResolver

    private val schema = DataDictionary.fromClasses(Person::class.java, Animal::class.java)

    private val holmesSchema = DataDictionary.fromClasses(
        com.embabel.dice.shell.Person::class.java,
        com.embabel.dice.shell.Animal::class.java,
        Detective::class.java,
        Doctor::class.java,
        Place::class.java,
    )

    @BeforeEach
    fun setUp() {
        repository = mockk()
        resolver = NamedEntityDataRepositoryEntityResolver(repository)
    }

    private fun createSuggestedEntities(vararg entities: SuggestedEntity): SuggestedEntities {
        return SuggestedEntities(suggestedEntities = entities.toList())
    }

    private fun createNamedEntity(
        id: String,
        name: String,
        labels: Set<String>,
        description: String = "Test entity"
    ): SimpleNamedEntityData {
        return SimpleNamedEntityData(
            id = id,
            name = name,
            description = description,
            labels = labels,
            properties = emptyMap()
        )
    }

    @Nested
    inner class BasicResolutionTests {

        @Test
        fun `should create new entity when no search results`() {
            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns emptyList()

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person", chunkId = "test-chunk")
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is NewEntity)
            assertEquals("Alice", resolutions.resolutions[0].suggested.name)
        }

        @Test
        fun `should find existing entity with exact name match`() {
            val existingEntity = createNamedEntity("alice-id", "Alice", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person", chunkId = "test-chunk")
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
            assertEquals("alice-id", resolutions.resolutions[0].recommended?.id)
        }

        @Test
        fun `should find existing entity with case-insensitive name match`() {
            val existingEntity = createNamedEntity("alice-id", "Alice", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "ALICE", summary = "A person", chunkId = "test-chunk")
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
        }

        @Test
        fun `should preserve chunk IDs in resolution`() {
            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns emptyList()

            val suggested = SuggestedEntities(
                suggestedEntities = listOf(
                    SuggestedEntity(
                        labels = listOf("Person"),
                        name = "Alice",
                        summary = "A person",
                        chunkId = "chunk-1"
                    ),
                    SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "A person", chunkId = "chunk-2")
                )
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(setOf("chunk-1", "chunk-2"), resolutions.chunkIds)
        }
    }

    @Nested
    inner class FindByIdTests {

        @Test
        fun `should use findById when suggested entity has ID`() {
            val existingEntity = createNamedEntity("alice-id", "Alice", setOf("Person"))

            every {
                repository.findById("alice-id")
            } returns existingEntity

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Alice",
                    summary = "A person",
                    id = "alice-id",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
            verify { repository.findById("alice-id") }
        }

        @Test
        fun `should fall back to text search when findById returns null`() {
            val existingEntity = createNamedEntity("alice-id-2", "Alice", setOf("Person"))

            every {
                repository.findById("alice-id")
            } returns null

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Alice",
                    summary = "A person",
                    id = "alice-id",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
            assertEquals("alice-id-2", resolutions.resolutions[0].recommended?.id)
        }

        @Test
        fun `should not use findById when disabled in config`() {
            val resolverNoFindById = NamedEntityDataRepositoryEntityResolver(
                repository,
                NamedEntityDataRepositoryEntityResolver.Config(useFindById = false)
            )

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns emptyList()

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Alice",
                    summary = "A person",
                    id = "alice-id",
                    chunkId = "test-chunk"
                )
            )

            resolverNoFindById.resolve(suggested, schema)

            verify(exactly = 0) { repository.findById(any()) }
        }
    }

    @Nested
    inner class TextSearchTests {

        @Test
        fun `should build correct Lucene query with phrase and terms`() {
            var capturedQuery: String? = null

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } answers {
                val request = firstArg<TextSimilaritySearchRequest>()
                capturedQuery = request.query
                emptyList()
            }

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Sherlock Holmes",
                    summary = "A detective",
                    chunkId = "test-chunk"
                )
            )

            resolver.resolve(suggested, schema)

            assertNotNull(capturedQuery)
            // Should contain exact phrase with boost
            assertTrue(capturedQuery!!.contains("\"Sherlock Holmes\"^2"))
            // Should contain individual terms
            assertTrue(capturedQuery!!.contains("Sherlock"))
            assertTrue(capturedQuery!!.contains("Holmes"))
            // Should contain fuzzy variants
            assertTrue(capturedQuery!!.contains("Sherlock~"))
            assertTrue(capturedQuery!!.contains("Holmes~"))
        }

        @Test
        fun `should not use fuzzy matching when disabled`() {
            val resolverNoFuzzy = NamedEntityDataRepositoryEntityResolver(
                repository,
                NamedEntityDataRepositoryEntityResolver.Config(useFuzzyTextSearch = false)
            )

            var capturedQuery: String? = null

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } answers {
                val request = firstArg<TextSimilaritySearchRequest>()
                capturedQuery = request.query
                emptyList()
            }

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Sherlock",
                    summary = "A detective",
                    chunkId = "test-chunk"
                )
            )

            resolverNoFuzzy.resolve(suggested, schema)

            assertNotNull(capturedQuery)
            assertFalse(capturedQuery!!.contains("~"))
        }

        @Test
        fun `should escape special Lucene characters in names`() {
            var capturedQuery: String? = null

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } answers {
                val request = firstArg<TextSimilaritySearchRequest>()
                capturedQuery = request.query
                emptyList()
            }

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "John+Doe",
                    summary = "A person",
                    chunkId = "test-chunk"
                )
            )

            resolver.resolve(suggested, schema)

            assertNotNull(capturedQuery)
            // The + should be escaped
            assertTrue(capturedQuery!!.contains("John\\+Doe"))
        }
    }

    @Nested
    inner class LabelCompatibilityTests {

        @Test
        fun `should not match entities with incompatible labels`() {
            val animalEntity = createNamedEntity("rex-id", "Rex", setOf("Animal"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(animalEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Rex",
                    summary = "A person named Rex",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, schema)

            // Should not match because Person and Animal are incompatible
            assertTrue(resolutions.resolutions[0] is NewEntity)
        }

        @Test
        fun `should match entities in type hierarchy - Detective extends Person`() {
            val personEntity = createNamedEntity("holmes-id", "Sherlock Holmes", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(personEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Detective"),
                    name = "Sherlock Holmes",
                    summary = "The detective",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, holmesSchema)

            // Should match because Detective extends Person
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
        }

        @Test
        fun `should match sibling types with common parent`() {
            val doctorEntity = createNamedEntity("watson-id", "Watson", setOf("Doctor"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(doctorEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Detective"),
                    name = "Watson",
                    summary = "A detective",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, holmesSchema)

            // Doctor and Detective share common parent Person
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
        }
    }

    @Nested
    inner class NameMatchingStrategyTests {

        @Test
        fun `should match with title normalization - Dr Watson to Watson`() {
            val existingEntity = createNamedEntity("watson-id", "Watson", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Dr. Watson",
                    summary = "A doctor",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertTrue(resolutions.resolutions[0] is ExistingEntity)
        }

        @Test
        fun `should match partial names - Holmes to Sherlock Holmes`() {
            val existingEntity = createNamedEntity("holmes-id", "Sherlock Holmes", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Holmes",
                    summary = "The detective",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertTrue(resolutions.resolutions[0] is ExistingEntity)
        }

        @Test
        fun `should not match short partial names - Doe to John Doe`() {
            val existingEntity = createNamedEntity("doe-id", "John Doe", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Doe", summary = "A person", chunkId = "test-chunk")
            )

            val resolutions = resolver.resolve(suggested, schema)

            // "Doe" is only 3 chars, below the minPartLength of 4
            assertTrue(resolutions.resolutions[0] is NewEntity)
        }

        @Test
        fun `should match with fuzzy matching - Sherlok to Sherlock`() {
            val existingEntity = createNamedEntity("sherlock-id", "Sherlock", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Sherlok",
                    summary = "A detective",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, schema)

            // 1 char difference in 8 chars = 12.5% < 20% threshold
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
        }

        @Test
        fun `should not match with too many differences`() {
            val existingEntity = createNamedEntity("alice-id", "Alice", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Bobby", summary = "A person", chunkId = "test-chunk")
            )

            val resolutions = resolver.resolve(suggested, schema)

            // Alice and Bobby are too different
            assertTrue(resolutions.resolutions[0] is NewEntity)
        }
    }

    @Nested
    inner class VectorSearchTests {

        @Test
        fun `should use vector search when enabled`() {
            val resolverWithVector = NamedEntityDataRepositoryEntityResolver(
                repository,
                NamedEntityDataRepositoryEntityResolver.Config(
                    useTextSearch = false,
                    useVectorSearch = true
                )
            )

            val existingEntity = createNamedEntity("alice-id", "Alice", setOf("Person"))

            every {
                repository.vectorSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.85))

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Alice",
                    summary = "A wonderful person",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolverWithVector.resolve(suggested, schema)

            assertTrue(resolutions.resolutions[0] is ExistingEntity)
            verify {
                repository.vectorSearch(any<TextSimilaritySearchRequest>())
            }
        }

        @Test
        fun `should not use vector search when disabled`() {
            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns emptyList()

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person", chunkId = "test-chunk")
            )

            resolver.resolve(suggested, schema)

            verify(exactly = 0) { repository.vectorSearch(any()) }
        }
    }

    @Nested
    inner class MultipleEntitiesTests {

        @Test
        fun `should handle multiple entities with different resolutions`() {
            val aliceEntity = createNamedEntity("alice-id", "Alice", setOf("Person"))

            every {
                repository.textSearch(match { it.query.contains("Alice") })
            } returns listOf(SimpleSimilaritySearchResult(aliceEntity, 0.9))

            every {
                repository.textSearch(match { it.query.contains("Bob") })
            } returns emptyList()

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Alice",
                    summary = "First person",
                    chunkId = "chunk-1"
                ),
                SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "Second person", chunkId = "chunk-2")
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(2, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is ExistingEntity) // Alice matched
            assertTrue(resolutions.resolutions[1] is NewEntity) // Bob is new
        }

        @Test
        fun `should maintain entity order in results`() {
            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns emptyList()

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "First", chunkId = "test-chunk"),
                SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "Second", chunkId = "test-chunk"),
                SuggestedEntity(labels = listOf("Person"), name = "Charlie", summary = "Third", chunkId = "test-chunk")
            )

            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(3, resolutions.resolutions.size)
            assertEquals("Alice", resolutions.resolutions[0].suggested.name)
            assertEquals("Bob", resolutions.resolutions[1].suggested.name)
            assertEquals("Charlie", resolutions.resolutions[2].suggested.name)
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should handle findById exception gracefully`() {
            every {
                repository.findById("bad-id")
            } throws RuntimeException("Database error")

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns emptyList()

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Alice",
                    summary = "A person",
                    id = "bad-id",
                    chunkId = "test-chunk"
                )
            )

            // Should not throw, should fall back to creating new entity
            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is NewEntity)
        }

        @Test
        fun `should handle textSearch exception gracefully`() {
            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } throws RuntimeException("Search error")

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person", chunkId = "test-chunk")
            )

            // Should not throw, should fall back to creating new entity
            val resolutions = resolver.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is NewEntity)
        }

        @Test
        fun `should handle vectorSearch exception gracefully`() {
            val resolverWithVector = NamedEntityDataRepositoryEntityResolver(
                repository,
                NamedEntityDataRepositoryEntityResolver.Config(
                    useTextSearch = false,
                    useVectorSearch = true
                )
            )

            every {
                repository.vectorSearch(any<TextSimilaritySearchRequest>())
            } throws RuntimeException("Vector search error")

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person", chunkId = "test-chunk")
            )

            // Should not throw, should fall back to creating new entity
            val resolutions = resolverWithVector.resolve(suggested, schema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is NewEntity)
        }
    }

    @Nested
    inner class CustomEntityMatchingStrategyTests {

        @Test
        fun `should allow custom match strategies`() {
            // Custom strategy that always matches entities with same first letter
            val firstLetterStrategy = PredicateEntityMatchingStrategy { suggested, candidate, _ ->
                val firstSuggested = suggested.name.firstOrNull()?.lowercaseChar()
                val firstCandidate = candidate.name.firstOrNull()?.lowercaseChar()
                if (firstSuggested == firstCandidate) {
                    MatchResult.Match
                } else {
                    MatchResult.NoMatch
                }
            }

            val customResolver = NamedEntityDataRepositoryEntityResolver(
                repository,
                matchStrategies = ChainedEntityMatchingStrategy.of(LabelCompatibilityStrategy(), firstLetterStrategy)
            )

            val existingEntity = createNamedEntity("alex-id", "Alexander", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.9))

            val suggested = createSuggestedEntities(
                SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "A person", chunkId = "test-chunk")
            )

            val resolutions = customResolver.resolve(suggested, schema)

            // Should match because both start with 'A'
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
        }

        @Test
        fun `should respect NoMatch from early strategy`() {
            // Strategy that vetoes any entity named "Villain"
            val vetoVillainStrategy = PredicateEntityMatchingStrategy { suggested, _, _ ->
                if (suggested.name.contains("Villain", ignoreCase = true)) {
                    MatchResult.NoMatch
                } else {
                    MatchResult.Inconclusive
                }
            }

            val customResolver = NamedEntityDataRepositoryEntityResolver(
                repository,
                matchStrategies = ChainedEntityMatchingStrategy.of(vetoVillainStrategy) + defaultMatchStrategies()
            )

            val existingEntity = createNamedEntity("villain-id", "Super Villain", setOf("Person"))

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.95))

            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("Person"),
                    name = "Super Villain",
                    summary = "A bad guy",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = customResolver.resolve(suggested, schema)

            // Should not match despite same name because of veto strategy
            assertTrue(resolutions.resolutions[0] is NewEntity)
        }
    }

    @Nested
    inner class EntityMatchingStrategyUnitTests {

        private val emptySchema = DataDictionary.fromClasses()

        @Test
        fun `ExactNameMatchStrategy should match exact names case-insensitively`() {
            val strategy = ExactNameEntityMatchingStrategy()
            val suggested = SuggestedEntity(listOf("Person"), "Alice", "test", chunkId = "test")
            val candidate = createNamedEntity("id", "ALICE", setOf("Person"))

            assertEquals(MatchResult.Match, strategy.evaluate(suggested, candidate, emptySchema))
        }

        @Test
        fun `ExactNameMatchStrategy should return Inconclusive for different names`() {
            val strategy = ExactNameEntityMatchingStrategy()
            val suggested = SuggestedEntity(listOf("Person"), "Alice", "test", chunkId = "test")
            val candidate = createNamedEntity("id", "Bob", setOf("Person"))

            assertEquals(MatchResult.Inconclusive, strategy.evaluate(suggested, candidate, emptySchema))
        }

        @Test
        fun `NormalizedNameMatchStrategy should match after removing titles`() {
            val strategy = NormalizedNameEntityMatchingStrategy()
            val suggested = SuggestedEntity(listOf("Person"), "Dr. Watson", "test", chunkId = "test")
            val candidate = createNamedEntity("id", "Watson", setOf("Person"))

            assertEquals(MatchResult.Match, strategy.evaluate(suggested, candidate, emptySchema))
        }

        @Test
        fun `NormalizedNameMatchStrategy should match after removing suffixes`() {
            val strategy = NormalizedNameEntityMatchingStrategy()
            val suggested = SuggestedEntity(listOf("Person"), "John Smith Jr.", "test", chunkId = "test")
            val candidate = createNamedEntity("id", "John Smith", setOf("Person"))

            assertEquals(MatchResult.Match, strategy.evaluate(suggested, candidate, emptySchema))
        }

        @Test
        fun `PartialNameMatchStrategy should match single word to last name`() {
            val strategy = PartialNameEntityMatchingStrategy()
            val suggested = SuggestedEntity(listOf("Person"), "Holmes", "test", chunkId = "test")
            val candidate = createNamedEntity("id", "Sherlock Holmes", setOf("Person"))

            assertEquals(MatchResult.Match, strategy.evaluate(suggested, candidate, emptySchema))
        }

        @Test
        fun `PartialNameMatchStrategy should not match short single words`() {
            val strategy = PartialNameEntityMatchingStrategy(minPartLength = 4)
            val suggested = SuggestedEntity(listOf("Person"), "Doe", "test", chunkId = "test")
            val candidate = createNamedEntity("id", "John Doe", setOf("Person"))

            assertEquals(MatchResult.Inconclusive, strategy.evaluate(suggested, candidate, emptySchema))
        }

        @Test
        fun `FuzzyNameMatchStrategy should match names within distance threshold`() {
            val strategy = FuzzyNameEntityMatchingStrategy(maxDistanceRatio = 0.2, minLengthForFuzzy = 4)
            val suggested = SuggestedEntity(listOf("Person"), "Sherlok", "test", chunkId = "test")
            val candidate = createNamedEntity("id", "Sherlock", setOf("Person"))

            // Distance = 1, length = 7, ratio = 0.14 < 0.2
            assertEquals(MatchResult.Match, strategy.evaluate(suggested, candidate, emptySchema))
        }

        @Test
        fun `FuzzyNameMatchStrategy should not match names beyond distance threshold`() {
            val strategy = FuzzyNameEntityMatchingStrategy(maxDistanceRatio = 0.1, minLengthForFuzzy = 4)
            val suggested = SuggestedEntity(listOf("Person"), "Sherlok", "test", chunkId = "test")
            val candidate = createNamedEntity("id", "Sherlock", setOf("Person"))

            // Distance = 1, length = 7, ratio = 0.14 > 0.1
            assertEquals(MatchResult.Inconclusive, strategy.evaluate(suggested, candidate, emptySchema))
        }

        @Test
        fun `FuzzyNameMatchStrategy should skip short names`() {
            val strategy = FuzzyNameEntityMatchingStrategy(minLengthForFuzzy = 5)
            val suggested = SuggestedEntity(listOf("Person"), "Bob", "test", chunkId = "test")
            val candidate = createNamedEntity("id", "Rob", setOf("Person"))

            assertEquals(MatchResult.Inconclusive, strategy.evaluate(suggested, candidate, emptySchema))
        }
    }

    @Nested
    inner class CreationPermittedTests {

        @Test
        fun `should veto entity when type has creationPermitted false and no match found`() {
            val mockSchema = mockk<DataDictionary>()
            val mockDomainType = mockk<com.embabel.agent.core.DomainType>(relaxed = true)

            every { mockSchema.domainTypeForLabels(setOf("RestrictedType")) } returns mockDomainType
            every { mockDomainType.creationPermitted } returns false

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns emptyList()

            val resolver = NamedEntityDataRepositoryEntityResolver(repository)
            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("RestrictedType"),
                    name = "Some Entity",
                    summary = "A restricted entity",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, mockSchema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is com.embabel.dice.common.VetoedEntity)
        }

        @Test
        fun `should create new entity when type has creationPermitted true`() {
            val mockSchema = mockk<DataDictionary>()
            val mockDomainType = mockk<com.embabel.agent.core.DomainType>(relaxed = true)

            every { mockSchema.domainTypeForLabels(setOf("CreatableType")) } returns mockDomainType
            every { mockDomainType.creationPermitted } returns true

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns emptyList()

            val resolver = NamedEntityDataRepositoryEntityResolver(repository)
            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("CreatableType"),
                    name = "Some Entity",
                    summary = "A creatable entity",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, mockSchema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is NewEntity)
        }

        @Test
        fun `should find relaxed match when creationPermitted false`() {
            val mockSchema = mockk<DataDictionary>()
            val mockDomainType = mockk<com.embabel.agent.core.DomainType>(relaxed = true)

            every { mockSchema.domainTypeForLabels(setOf("RestrictedType")) } returns mockDomainType
            every { mockDomainType.creationPermitted } returns false
            every { mockDomainType.isAssignableFrom(any<com.embabel.agent.core.DomainType>()) } returns true

            val existingEntity = createNamedEntity("existing-id", "Similar Entity", setOf("RestrictedType"))

            // First search returns nothing (normal threshold)
            // Second search (relaxed) returns the entity
            every {
                repository.textSearch(match { it.similarityThreshold >= 0.4 })
            } returns emptyList()

            every {
                repository.textSearch(match { it.similarityThreshold < 0.4 })
            } returns listOf(SimpleSimilaritySearchResult(existingEntity, 0.3))

            val resolver = NamedEntityDataRepositoryEntityResolver(repository)
            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("RestrictedType"),
                    name = "Some Entity",
                    summary = "A restricted entity",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, mockSchema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is ExistingEntity)
            assertEquals("existing-id", resolutions.resolutions[0].recommended?.id)
        }

        @Test
        fun `should default to creatable when type not found in schema`() {
            val mockSchema = mockk<DataDictionary>()

            every { mockSchema.domainTypeForLabels(any()) } returns null

            every {
                repository.textSearch(any<TextSimilaritySearchRequest>())
            } returns emptyList()

            val resolver = NamedEntityDataRepositoryEntityResolver(repository)
            val suggested = createSuggestedEntities(
                SuggestedEntity(
                    labels = listOf("UnknownType"),
                    name = "Unknown Entity",
                    summary = "An unknown entity",
                    chunkId = "test-chunk"
                )
            )

            val resolutions = resolver.resolve(suggested, mockSchema)

            assertEquals(1, resolutions.resolutions.size)
            assertTrue(resolutions.resolutions[0] is NewEntity)
        }
    }
}
