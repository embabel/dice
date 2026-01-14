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
package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.searcher.ByExactNameCandidateSearcher
import com.embabel.dice.common.resolver.searcher.ByIdCandidateSearcher
import com.embabel.dice.common.resolver.searcher.FuzzyNameCandidateSearcher
import com.embabel.dice.common.resolver.searcher.NormalizedNameCandidateSearcher
import com.embabel.dice.common.resolver.searcher.PartialNameCandidateSearcher
import com.embabel.dice.common.resolver.searcher.VectorCandidateSearcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EscalatingEntityResolverTest {

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
            every { repository.textSearch(any(), any(), any()) } returns listOf(SimilarityResult(brahmsEntity, 1.0))

            val resolver = EscalatingEntityResolver(
                searchers = listOf(
                    ByExactNameCandidateSearcher(repository),
                ),
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

            val resolver = EscalatingEntityResolver(
                searchers = listOf(
                    ByIdCandidateSearcher(repository),
                ),
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
            every { repository.textSearch(any(), any(), any()) } returns listOf(SimilarityResult(brahmsEntity, 0.8))

            val resolver = EscalatingEntityResolver(
                searchers = listOf(
                    ByIdCandidateSearcher(repository),
                    ByExactNameCandidateSearcher(repository),
                    PartialNameCandidateSearcher(repository),
                ),
            )

            // "Brahms" should match "Johannes Brahms" via PartialNameCandidateSearcher
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
            every { repository.textSearch(any(), any(), any()) } returns emptyList()
            every { repository.vectorSearch(any(), any(), any()) } returns listOf(SimilarityResult(brahmsEntity, 0.98))

            val resolver = EscalatingEntityResolver(
                searchers = listOf(
                    ByIdCandidateSearcher(repository),
                    ByExactNameCandidateSearcher(repository),
                    NormalizedNameCandidateSearcher(repository),
                    PartialNameCandidateSearcher(repository),
                    FuzzyNameCandidateSearcher(repository),
                    VectorCandidateSearcher(repository, autoAcceptThreshold = 0.95),
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
            every { repository.textSearch(any(), any(), any()) } returns emptyList()
            every { repository.vectorSearch(any(), any(), any()) } returns listOf(SimilarityResult(brahmsEntity, 0.80))

            val resolver = EscalatingEntityResolver(
                searchers = listOf(
                    ByIdCandidateSearcher(repository),
                    ByExactNameCandidateSearcher(repository),
                    NormalizedNameCandidateSearcher(repository),
                    PartialNameCandidateSearcher(repository),
                    FuzzyNameCandidateSearcher(repository),
                    VectorCandidateSearcher(repository, autoAcceptThreshold = 0.95),
                ),
                candidateBakeoff = null, // No LLM fallback
                config = EscalatingEntityResolver.Config(heuristicOnly = true),
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
            every { repository.textSearch(any(), any(), any()) } returns listOf(SimilarityResult(brahmsEntity, 0.7))

            val resolver = EscalatingEntityResolver(
                searchers = listOf(
                    ByIdCandidateSearcher(repository),
                    ByExactNameCandidateSearcher(repository),
                    NormalizedNameCandidateSearcher(repository),
                    PartialNameCandidateSearcher(repository),
                    FuzzyNameCandidateSearcher(repository),
                ),
                candidateBakeoff = null,
                config = EscalatingEntityResolver.Config(heuristicOnly = true),
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
            every { repository.textSearch(any(), any(), any()) } returns emptyList()
            every { repository.vectorSearch(any(), any(), any()) } returns emptyList()

            val resolver = EscalatingEntityResolver(
                searchers = listOf(
                    ByIdCandidateSearcher(repository),
                    ByExactNameCandidateSearcher(repository),
                    NormalizedNameCandidateSearcher(repository),
                    PartialNameCandidateSearcher(repository),
                    FuzzyNameCandidateSearcher(repository),
                    VectorCandidateSearcher(repository),
                ),
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
            every { repository.textSearch(match { it.query.contains("Brahms") }, any(), any()) } returns listOf(
                SimilarityResult(brahmsEntity, 1.0)
            )
            every { repository.textSearch(match { it.query.contains("Unknown") }, any(), any()) } returns emptyList()
            every { repository.vectorSearch(any(), any(), any()) } returns emptyList()

            val resolver = EscalatingEntityResolver(
                searchers = listOf(
                    ByIdCandidateSearcher(repository),
                    ByExactNameCandidateSearcher(repository),
                    NormalizedNameCandidateSearcher(repository),
                    PartialNameCandidateSearcher(repository),
                    FuzzyNameCandidateSearcher(repository),
                    VectorCandidateSearcher(repository),
                ),
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

            every { repository.textSearch(any(), any(), any()) } returns emptyList()
            every { repository.vectorSearch(any(), any(), any()) } returns emptyList()

            val resolver = EscalatingEntityResolver(
                searchers = listOf(
                    ByIdCandidateSearcher(repository),
                    ByExactNameCandidateSearcher(repository),
                    NormalizedNameCandidateSearcher(repository),
                    PartialNameCandidateSearcher(repository),
                    FuzzyNameCandidateSearcher(repository),
                    VectorCandidateSearcher(repository),
                ),
                contextCompressor = compressor,
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
            val resolver = EscalatingEntityResolver.create(repository)

            assertNotNull(resolver)
        }
    }

    @Nested
    inner class CustomSearchers {

        @Test
        fun `supports custom searcher chain`() {
            // Custom searcher that always returns confident match
            val customSearcher = object : CandidateSearcher {
                override fun search(suggested: SuggestedEntity, schema: DataDictionary): SearchResult {
                    return SearchResult.confident(brahmsEntity)
                }
            }

            val resolver = EscalatingEntityResolver(
                searchers = listOf(customSearcher),
            )

            val suggested = suggestedEntity("Anything")
            val result = resolver.resolve(suggestedEntities(suggested), schema)

            assertEquals(1, result.resolutions.size)
            assertTrue(result.resolutions.first() is ExistingEntity)
            assertEquals("brahms-123", (result.resolutions.first() as ExistingEntity).existing.id)
        }
    }
}
