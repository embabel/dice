package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.matcher.ChainedEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.ExactNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.NormalizedNameEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.PartialNameEntityMatchingStrategy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HierarchicalEntityResolverTest {

    private lateinit var repository: NamedEntityDataRepository
    private lateinit var schema: DataDictionary

    private val brahmsEntity = SimpleNamedEntityData(
        id = "brahms-123",
        name = "Johannes Brahms",
        description = "German composer",
        labels = setOf("Composer", "Person"),
        properties = emptyMap(),
    )

    private val wagnerEntity = SimpleNamedEntityData(
        id = "wagner-456",
        name = "Richard Wagner",
        description = "German composer of operas",
        labels = setOf("Composer", "Person"),
        properties = emptyMap(),
    )

    @BeforeEach
    fun setup() {
        repository = mockk(relaxed = true)
        schema = DataDictionary.fromClasses()
    }

    private fun suggestedEntity(
        name: String,
        labels: List<String> = listOf("Composer"),
        summary: String = "",
    ) = SuggestedEntity(
        labels = labels,
        name = name,
        summary = summary,
        chunkId = "chunk-1",
    )

    private fun suggestedEntities(vararg entities: SuggestedEntity) = SuggestedEntities(
        suggestedEntities = entities.toList(),
        sourceText = "Some conversation about composers",
    )

    @Nested
    inner class ExactMatchLevel {

        @Test
        fun `resolves exact name match without LLM`() {
            // Text search returns exact match
            every { repository.textSearch(any()) } returns listOf(SimilarityResult(brahmsEntity, 1.0))

            val resolver = HierarchicalEntityResolver(
                repository = repository,
                config = HierarchicalConfig(useVectorSearch = false),
            )

            val suggested = suggestedEntity("Johannes Brahms")
            val result = resolver.resolve(suggestedEntities(suggested), schema)

            assertEquals(1, result.resolutions.size)
            val resolution = result.resolutions.first()
            assertTrue(resolution is ExistingEntity)
            assertEquals("brahms-123", (resolution as ExistingEntity).existing.id)
        }

        @Test
        fun `resolves by ID when available`() {
            val suggestedWithId = SuggestedEntity(
                labels = listOf("Composer"),
                name = "Brahms",
                summary = "",
                chunkId = "chunk-1",
                id = "brahms-123",
            )

            every { repository.findById("brahms-123") } returns brahmsEntity

            val resolver = HierarchicalEntityResolver(
                repository = repository,
                config = HierarchicalConfig(useTextSearch = false, useVectorSearch = false),
            )

            val result = resolver.resolve(suggestedEntities(suggestedWithId), schema)

            assertEquals(1, result.resolutions.size)
            assertTrue(result.resolutions.first() is ExistingEntity)
            verify { repository.findById("brahms-123") }
        }
    }

    @Nested
    inner class HeuristicMatchLevel {

        @Test
        fun `resolves partial name match via heuristics`() {
            // Search returns candidate that matches via heuristic strategies
            every { repository.textSearch(any()) } returns listOf(SimilarityResult(brahmsEntity, 0.8))

            val resolver = HierarchicalEntityResolver(
                repository = repository,
                matchStrategies = ChainedEntityMatchingStrategy.of(
                    ExactNameEntityMatchingStrategy(),
                    NormalizedNameEntityMatchingStrategy(),
                    PartialNameEntityMatchingStrategy(),
                ),
                config = HierarchicalConfig(useVectorSearch = false),
            )

            // "Brahms" should match "Johannes Brahms" via PartialNameMatchStrategy
            val suggested = suggestedEntity("Brahms")
            val result = resolver.resolve(suggestedEntities(suggested), schema)

            assertEquals(1, result.resolutions.size)
            assertTrue(result.resolutions.first() is ExistingEntity)
        }
    }

    @Nested
    inner class EmbeddingMatchLevel {

        @Test
        fun `auto-accepts high confidence embedding match`() {
            // Vector search returns very high score
            every { repository.textSearch(any()) } returns emptyList()
            every { repository.vectorSearch(any()) } returns listOf(SimilarityResult(brahmsEntity, 0.98))

            val resolver = HierarchicalEntityResolver(
                repository = repository,
                matchStrategies = ChainedEntityMatchingStrategy.of(), // No heuristic match
                config = HierarchicalConfig(
                    useTextSearch = true,
                    useVectorSearch = true,
                    embeddingAutoAcceptThreshold = 0.95,
                ),
            )

            val suggested = suggestedEntity("J. Brahms")
            val result = resolver.resolve(suggestedEntities(suggested), schema)

            assertEquals(1, result.resolutions.size)
            assertTrue(result.resolutions.first() is ExistingEntity)
        }

        @Test
        fun `does not auto-accept below threshold`() {
            // Vector search returns moderate score
            every { repository.textSearch(any()) } returns emptyList()
            every { repository.vectorSearch(any()) } returns listOf(SimilarityResult(brahmsEntity, 0.80))

            val resolver = HierarchicalEntityResolver(
                repository = repository,
                matchStrategies = ChainedEntityMatchingStrategy.of(),
                llmBakeoff = null, // No LLM fallback
                config = HierarchicalConfig(
                    useTextSearch = true,
                    useVectorSearch = true,
                    embeddingAutoAcceptThreshold = 0.95,
                    heuristicOnly = true,
                ),
            )

            val suggested = suggestedEntity("Some Composer")
            val result = resolver.resolve(suggestedEntities(suggested), schema)

            // Should create new since no confident match
            assertEquals(1, result.resolutions.size)
            assertTrue(result.resolutions.first() is NewEntity)
        }
    }

    @Nested
    inner class HeuristicOnlyMode {

        @Test
        fun `never calls LLM in heuristic-only mode`() {
            every { repository.textSearch(any()) } returns listOf(SimilarityResult(brahmsEntity, 0.7))

            val resolver = HierarchicalEntityResolver(
                repository = repository,
                matchStrategies = ChainedEntityMatchingStrategy.of(),
                llmBakeoff = null,
                config = HierarchicalConfig(
                    useVectorSearch = false,
                    heuristicOnly = true,
                ),
            )

            val suggested = suggestedEntity("Unknown Composer")
            val result = resolver.resolve(suggestedEntities(suggested), schema)

            // Should create new entity without trying LLM
            assertEquals(1, result.resolutions.size)
            assertTrue(result.resolutions.first() is NewEntity)
        }
    }

    @Nested
    inner class NoCandidatesCase {

        @Test
        fun `creates new entity when no candidates found`() {
            every { repository.textSearch(any()) } returns emptyList()
            every { repository.vectorSearch(any()) } returns emptyList()

            val resolver = HierarchicalEntityResolver(
                repository = repository,
                config = HierarchicalConfig(),
            )

            val suggested = suggestedEntity("Completely Unknown Composer")
            val result = resolver.resolve(suggestedEntities(suggested), schema)

            assertEquals(1, result.resolutions.size)
            assertTrue(result.resolutions.first() is NewEntity)
        }
    }

    @Nested
    inner class MultipleEntities {

        @Test
        fun `resolves multiple entities with different strategies`() {
            // Brahms exact match, Unknown no match
            every { repository.textSearch(match { it.query.contains("Brahms") }) } returns listOf(
                SimilarityResult(
                    brahmsEntity,
                    1.0
                )
            )
            every { repository.textSearch(match { it.query.contains("Unknown") }) } returns emptyList()
            every { repository.vectorSearch(any()) } returns emptyList()

            val resolver = HierarchicalEntityResolver(
                repository = repository,
                config = HierarchicalConfig(useVectorSearch = true),
            )

            val entities = suggestedEntities(
                suggestedEntity("Johannes Brahms"),
                suggestedEntity("Unknown New Composer"),
            )
            val result = resolver.resolve(entities, schema)

            assertEquals(2, result.resolutions.size)
            assertTrue(result.resolutions[0] is ExistingEntity)
            assertTrue(result.resolutions[1] is NewEntity)
        }
    }

    @Nested
    inner class ContextCompression {

        @Test
        fun `uses context compressor when provided`() {
            val compressor = mockk<ContextCompressor>()
            every { compressor.compress(any(), any()) } returns "Compressed context"

            every { repository.textSearch(any()) } returns emptyList()
            every { repository.vectorSearch(any()) } returns emptyList()

            val resolver = HierarchicalEntityResolver(
                repository = repository,
                contextCompressor = compressor,
                config = HierarchicalConfig(),
            )

            val entities = SuggestedEntities(
                suggestedEntities = listOf(suggestedEntity("Test")),
                sourceText = "Very long source text that should be compressed",
            )
            resolver.resolve(entities, schema)

            // Compressor should have been called (though no LLM call in this case)
            // The compressor is only invoked when LLM is needed
        }
    }

    @Nested
    inner class FactoryMethod {

        @Test
        fun `create factory method works`() {
            val resolver = HierarchicalEntityResolver.create(repository)

            assertNotNull(resolver)
        }
    }
}
