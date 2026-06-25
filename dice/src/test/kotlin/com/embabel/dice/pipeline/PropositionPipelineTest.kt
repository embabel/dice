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
package com.embabel.dice.pipeline

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.dice.common.*
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.common.filter.CompositeMentionFilter
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.filter.SchemaValidatedMentionFilter
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValidatedPropertyDefinition
import com.embabel.dice.common.validation.NoVagueReferences
import com.embabel.dice.common.validation.LengthConstraint
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.InMemoryChunkHistoryStore
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.revision.LlmPropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.text2graph.builder.Animal
import com.embabel.dice.text2graph.builder.Person
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val testContextId = ContextId("test-context")

/**
 * Repository that tracks relationship creation calls for testing.
 */
class TrackingEntityRepository(
    schema: DataDictionary
) : InMemoryNamedEntityDataRepository(schema) {

    data class CreatedRelationship(
        val source: RetrievableIdentifier,
        val target: RetrievableIdentifier,
        val relationship: RelationshipData,
    )

    val createdRelationships = mutableListOf<CreatedRelationship>()

    override fun createRelationship(
        a: RetrievableIdentifier,
        b: RetrievableIdentifier,
        relationship: RelationshipData
    ) {
        createdRelationships.add(CreatedRelationship(a, b, relationship))
    }

    fun relationshipsOfType(type: String): List<CreatedRelationship> =
        createdRelationships.filter { it.relationship.name == type }
}

/**
 * Tests for [PropositionPipeline], with emphasis on cross-chunk entity resolution.
 *
 * The pipeline wraps the context's EntityResolver with an InMemoryEntityResolver so entities
 * discovered in earlier chunks are recognized in later ones within the same run, without any
 * external persistence.
 */
class PropositionPipelineTest {

    private val schema = DataDictionary.fromClasses("test", Person::class.java, Animal::class.java)

    /**
     * Simple mock extractor that creates propositions with entity mentions based on chunk content.
     * Uses a simple pattern: chunk text contains "mentions:Entity1,Entity2" to specify entities.
     */
    private class MockPropositionExtractor : PropositionExtractor {

        override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions {
            // Sentinel: a "fail:"-prefixed chunk simulates an extraction failure so the
            // execution strategy's runCatching yields a null slot -> the resolution stage produces
            // ChunkPropositionResult.Failed.
            if (chunk.text.startsWith("fail:")) {
                throw IllegalStateException("sentinel failure for ${chunk.id}")
            }
            // Parse entities from chunk text: "mentions:Alice,Bob" -> [Alice, Bob]
            val text = chunk.text
            if (!text.startsWith("mentions:")) {
                return SuggestedPropositions(chunk.id, emptyList())
            }

            // Split by comma to support multi-word mentions like "this company"
            val entityNames = text.substringAfter("mentions:").split(",").map { it.trim() }

            val mentions = entityNames.mapIndexed { index, name ->
                SuggestedMention(
                    span = name,
                    type = "Person",
                    role = if (index == 0) "SUBJECT" else "OBJECT",
                )
            }

            val propositions = if (mentions.isNotEmpty()) {
                listOf(
                    SuggestedProposition(
                        text = "Proposition about ${entityNames.joinToString(" and ")}",
                        mentions = mentions,
                        confidence = 0.9,
                    )
                )
            } else {
                emptyList()
            }

            return SuggestedPropositions(
                chunkId = chunk.id,
                propositions = propositions,
            )
        }

        override fun toSuggestedEntities(
            suggestedPropositions: SuggestedPropositions,
            context: SourceAnalysisContext,
            sourceText: String?,
            mentionFilter: MentionFilter?
        ): SuggestedEntities {
            val seen = mutableSetOf<MentionKey>()
            val entities = mutableListOf<SuggestedEntity>()

            for (prop in suggestedPropositions.propositions) {
                for (mention in prop.mentions) {

                    // Apply filter if provided
                    if (mentionFilter != null && !mentionFilter.isValid(mention, prop.text)) {
                        continue  // Skip invalid mention
                    }

                    val key = MentionKey.from(mention)
                    if (key !in seen) {
                        seen.add(key)
                        entities.add(
                            SuggestedEntity(
                                labels = listOf(mention.type),
                                name = mention.span,
                                summary = "Entity: ${mention.span}",
                                chunkId = suggestedPropositions.chunkId,
                            )
                        )
                    }
                }
            }

            return SuggestedEntities(suggestedEntities = entities, sourceText = sourceText)
        }

        override fun resolvePropositions(
            suggestedPropositions: SuggestedPropositions,
            resolutions: Resolutions<SuggestedEntityResolution>,
            context: SourceAnalysisContext,
        ): List<Proposition> {
            // Build lookup from entity name to resolved ID
            val nameToId = mutableMapOf<String, String>()
            for (resolution in resolutions.resolutions) {
                val entity = resolution.recommended ?: continue
                nameToId[resolution.suggested.name.lowercase()] = entity.id
            }

            return suggestedPropositions.propositions.map { suggestedProp ->
                val resolvedMentions = suggestedProp.mentions.map { mention ->
                    EntityMention(
                        span = mention.span,
                        type = mention.type,
                        role = MentionRole.valueOf(mention.role),
                        resolvedId = nameToId[mention.span.lowercase()],
                    )
                }

                Proposition(
                    contextId = context.contextId,
                    text = suggestedProp.text,
                    mentions = resolvedMentions,
                    confidence = suggestedProp.confidence,
                    grounding = listOf(suggestedPropositions.chunkId),
                )
            }
        }
    }

    @Nested
    inner class ProvenanceStampingTests {

        private val pipeline = PropositionPipeline.withExtractor(MockPropositionExtractor())

        private fun context(locator: SourceLocator? = null) = SourceAnalysisContext(
            schema = schema,
            entityResolver = AlwaysCreateEntityResolver,
            contextId = testContextId,
            sourceLocator = locator,
        )

        @Test
        fun `processChunk stamps a content-addressed provenance entry carrying the chunk id`() {
            val chunk = Chunk(id = "chunk-prov-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")

            val result = pipeline.processChunk(chunk, context()) as ChunkPropositionResult.Success

            val entry = result.propositions.single().provenanceEntries.single()
            assertEquals("chunk-prov-1", entry.chunkId)
            assertNotNull(entry.contentHash, "a content hash is recorded")
            assertTrue(entry.locator is ContentAddressedLocator, "no source locator -> content-addressed fallback")
            assertEquals((entry.locator as ContentAddressedLocator).contentHash, entry.contentHash)
        }

        @Test
        fun `a context source locator grounds the proposition in that source`() {
            val locator = UriLocator("https://example.com/doc-1")
            val chunk = Chunk(id = "chunk-prov-2", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")

            val result = pipeline.processChunk(chunk, context(locator)) as ChunkPropositionResult.Success

            val entry = result.propositions.single().provenanceEntries.single()
            assertEquals(locator, entry.locator, "the supplied locator wins over the content-hash fallback")
            assertEquals("chunk-prov-2", entry.chunkId)
            assertNotNull(entry.contentHash)
        }

        @Test
        fun `processOnce records a content hash in provenance`() {
            val result = pipeline.processOnce(
                text = "mentions:Alice,Bob",
                sourceId = "doc-42",
                context = context(),
            )!! as ChunkPropositionResult.Success

            val entry = result.propositions.single().provenanceEntries.single()
            // processOnce uses the sourceId as the chunk id, so provenance traces straight to it.
            assertEquals("doc-42", entry.chunkId)
            assertNotNull(entry.contentHash)
        }
    }

    @Nested
    inner class ProvenanceRevisionIntegrationTests {

        /**
         * The whole chain together: the pipeline stamps the new proposition's provenance, then its
         * revision step folds it into an existing proposition in the store — and the merged result
         * carries provenance from BOTH. The real [LlmPropositionReviser] merges via its canonical
         * text-match fast path (identical text), so no LLM call is needed; the store is the
         * production in-memory repository.
         */
        @Test
        fun `revision unions the stamped provenance with an existing proposition's provenance`() {
            val repository = InMemoryPropositionRepository()
            val reviser = LlmPropositionReviser(llmOptions = io.mockk.mockk(), ai = io.mockk.mockk())
            val pipeline = PropositionPipeline
                .withExtractor(MockPropositionExtractor())
                .withRevision(reviser, repository)

            // Seed an existing proposition with the SAME text the extractor produces (so the
            // canonical match fires) and a prior source.
            val priorLocator = UriLocator("https://example.com/prior")
            val existing = Proposition(
                contextId = testContextId,
                text = "Proposition about Alice and Bob",
                mentions = emptyList(),
                confidence = 0.8,
            ).withProvenanceEntries(listOf(ProvenanceEntry(priorLocator, chunkId = "prior-chunk")))
            repository.save(existing)

            val newLocator = UriLocator("https://example.com/new")
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
                sourceLocator = newLocator,
            )
            val chunk = Chunk(id = "chunk-merge", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")

            val result = pipeline.processChunk(chunk, context) as ChunkPropositionResult.Success

            val merged = result.revisionResults.filterIsInstance<RevisionResult.Merged>().single()
            val locators = merged.revised.provenanceEntries.map { it.locator }
            assertTrue(priorLocator in locators, "keeps the existing source")
            assertTrue(newLocator in locators, "adds the newly stamped source")
        }
    }

    @Nested
    inner class SingleChunkTests {

        @Test
        fun `single chunk extracts entities correctly`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            assertEquals(1, result.chunkResults.size)
            assertEquals(1, result.allPropositions.size)

            // Should have 2 new entities
            val newEntities = result.newEntities()
            assertEquals(2, newEntities.size)
            assertTrue(newEntities.any { it.name == "Alice" })
            assertTrue(newEntities.any { it.name == "Bob" })

            // No updated entities (all are new)
            assertEquals(0, result.updatedEntities().size)
        }

        @Test
        fun `single chunk with no entities produces empty result`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "no entities here", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            assertEquals(0, result.allPropositions.size)
            assertEquals(0, result.newEntities().size)
        }
    }

    @Nested
    inner class CrossChunkResolutionTests {

        @Test
        fun `same entity in multiple chunks resolves to single entity`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-3", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // Should have 3 propositions (one per chunk)
            assertEquals(3, result.allPropositions.size)

            // But only 1 unique entity (Alice)
            val newEntities = result.newEntities()
            assertEquals(1, newEntities.size, "Should have only 1 unique entity. Found: ${newEntities.map { it.name }}")
            assertEquals("Alice", newEntities.first().name)
        }

        @Test
        fun `multiple different entities across chunks are all captured`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Bob", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-3", text = "mentions:Charlie", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // Should have 3 propositions
            assertEquals(3, result.allPropositions.size)

            // And 3 unique entities
            val newEntities = result.newEntities()
            assertEquals(3, newEntities.size)
            assertTrue(newEntities.any { it.name == "Alice" })
            assertTrue(newEntities.any { it.name == "Bob" })
            assertTrue(newEntities.any { it.name == "Charlie" })
        }

        @Test
        fun `entity mentioned in later chunk references earlier chunk entity`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Alice,Charlie", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // Should have 2 propositions
            assertEquals(2, result.allPropositions.size)

            // Should have 3 unique entities (Alice, Bob, Charlie)
            val newEntities = result.newEntities()
            assertEquals(3, newEntities.size, "Should have 3 unique entities. Found: ${newEntities.map { it.name }}")

            // Alice should appear only once in newEntities (from chunk-1)
            val aliceCount = newEntities.count { it.name == "Alice" }
            assertEquals(1, aliceCount, "Alice should appear exactly once")

            // All propositions mentioning Alice should reference the same entity ID
            val aliceId = newEntities.first { it.name == "Alice" }.id
            for (prop in result.allPropositions) {
                val aliceMention = prop.mentions.find { it.span == "Alice" }
                if (aliceMention != null) {
                    assertEquals(
                        aliceId, aliceMention.resolvedId,
                        "All Alice mentions should have the same resolved ID"
                    )
                }
            }
        }

        @Test
        fun `chunk results correctly track new vs existing entities`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // Chunk 1: Alice is new
            val chunk1Result = result.chunkResults[0]
            assertEquals(1, chunk1Result.newEntities().size)
            assertEquals(0, chunk1Result.updatedEntities().size)
            assertEquals("Alice", chunk1Result.newEntities().first().name)

            // Chunk 2: Alice is existing (matched from chunk 1)
            val chunk2Result = result.chunkResults[1]
            assertEquals(0, chunk2Result.newEntities().size)
            assertEquals(1, chunk2Result.updatedEntities().size)
            assertEquals("Alice", chunk2Result.updatedEntities().first().name)
        }
    }

    @Nested
    inner class PreExistingEntityTests {

        @Test
        fun `resolver finds pre-existing entity and marks as updated`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            // Pre-populate an InMemoryEntityResolver with an existing entity
            val prePopulatedResolver = InMemoryEntityResolver()
            prePopulatedResolver.resolve(
                SuggestedEntities(
                    suggestedEntities = listOf(
                        SuggestedEntity(
                            labels = listOf("Person"),
                            name = "Alice",
                            summary = "Pre-existing Alice",
                            chunkId = "pre-existing",
                        )
                    )
                ),
                schema,
            )

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = prePopulatedResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // Alice should be updated (matched to pre-existing)
            val updatedEntities = result.updatedEntities()
            assertEquals(1, updatedEntities.size)
            assertEquals("Alice", updatedEntities.first().name)

            // Bob should be new
            val newEntities = result.newEntities()
            assertEquals(1, newEntities.size)
            assertEquals("Bob", newEntities.first().name)
        }

        @Test
        fun `mix of pre-existing and new entities across multiple chunks`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            // Pre-populate with Alice
            val prePopulatedResolver = InMemoryEntityResolver()
            prePopulatedResolver.resolve(
                SuggestedEntities(
                    suggestedEntities = listOf(
                        SuggestedEntity(
                            labels = listOf("Person"),
                            name = "Alice",
                            summary = "Pre-existing Alice",
                            chunkId = "pre-existing",
                        )
                    )
                ),
                schema,
            )

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Bob,Charlie", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-3", text = "mentions:Alice,Charlie", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = prePopulatedResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // New entities: Bob (from chunk-1), Charlie (from chunk-2)
            val newEntities = result.newEntities()
            assertEquals(2, newEntities.size, "Should have 2 new entities. Found: ${newEntities.map { it.name }}")
            assertTrue(newEntities.any { it.name == "Bob" })
            assertTrue(newEntities.any { it.name == "Charlie" })

            // Updated entities: includes all entities that had ExistingEntity resolutions
            // - Alice: pre-existing, matched in chunk-1 and chunk-3
            // - Bob: new in chunk-1, but matched as existing in chunk-2
            // - Charlie: new in chunk-2, but matched as existing in chunk-3
            val updatedEntities = result.updatedEntities()
            assertEquals(
                3,
                updatedEntities.size,
                "Should have 3 updated entities. Found: ${updatedEntities.map { it.name }}"
            )
            assertTrue(updatedEntities.any { it.name == "Alice" })
            assertTrue(updatedEntities.any { it.name == "Bob" })
            assertTrue(updatedEntities.any { it.name == "Charlie" })
        }
    }

    @Nested
    inner class EntityDeduplicationTests {

        @Test
        fun `newEntities deduplicates entities by ID`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            // Entity mentioned multiple times across chunks
            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-3", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-4", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // Should have 4 propositions
            assertEquals(4, result.allPropositions.size)

            // But only 1 unique entity
            val newEntities = result.newEntities()
            assertEquals(1, newEntities.size, "Alice should appear exactly once in newEntities")
            assertEquals("Alice", newEntities.first().name)
        }

        @Test
        fun `entitiesToPersist includes both new and updated`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            // Pre-populate with Alice
            val prePopulatedResolver = InMemoryEntityResolver()
            prePopulatedResolver.resolve(
                SuggestedEntities(
                    suggestedEntities = listOf(
                        SuggestedEntity(
                            labels = listOf("Person"),
                            name = "Alice",
                            summary = "Pre-existing",
                            chunkId = "pre",
                        )
                    )
                ),
                schema,
            )

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = prePopulatedResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val toPersist = result.entitiesToPersist()
            assertEquals(2, toPersist.size, "Should have 2 entities to persist (Alice updated, Bob new)")
            assertTrue(toPersist.any { it.name == "Alice" })
            assertTrue(toPersist.any { it.name == "Bob" })
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `empty chunks list produces empty result`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(emptyList(), context)

            assertEquals(0, result.chunkResults.size)
            assertEquals(0, result.allPropositions.size)
            assertEquals(0, result.newEntities().size)
            assertEquals(0, result.updatedEntities().size)
        }

        @Test
        fun `processChunk does not benefit from cross-chunk resolution`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            // Process individual chunks separately (not through process())
            val chunk1Result = pipeline.processChunk(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                context,
            )
            val chunk2Result = pipeline.processChunk(
                Chunk(id = "chunk-2", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                context,
            )

            // Both chunks should create new entities (no cross-chunk resolution)
            assertEquals(1, chunk1Result.newEntities().size)
            assertEquals(1, chunk2Result.newEntities().size)

            // The entity IDs will be different because there's no wrapping
            val id1 = chunk1Result.newEntities().first().id
            val id2 = chunk2Result.newEntities().first().id
            assertNotEquals(id1, id2, "Without process(), each chunk creates a separate Alice")
        }

        @Test
        fun `process provides cross-chunk resolution that processChunk alone does not`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // With process(), both chunks reference the same Alice
            assertEquals(1, result.newEntities().size, "process() should deduplicate Alice")

            // All Alice mentions should have the same resolved ID
            val aliceId = result.newEntities().first().id
            for (prop in result.allPropositions) {
                val aliceMention = prop.mentions.find { it.span == "Alice" }
                assertNotNull(aliceMention)
                assertEquals(aliceId, aliceMention!!.resolvedId)
            }
        }
    }

    /** In-memory proposition store used by tests that need a real repository. */
    private class InMemoryPropositionRepository : PropositionRepository {

        private val propositions = mutableMapOf<String, Proposition>()

        override val luceneSyntaxNotes: String = "In-memory test store"

        override fun save(proposition: Proposition): Proposition {
            propositions[proposition.id] = proposition
            return proposition
        }

        override fun findById(id: String): Proposition? = propositions[id]

        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> {
            return propositions.values.filter { prop ->
                prop.mentions.any { it.resolvedId == entityIdentifier.id }
            }
        }

        override fun findSimilarWithScores(
            textSimilaritySearchRequest: com.embabel.common.core.types.TextSimilaritySearchRequest
        ): List<com.embabel.common.core.types.SimilarityResult<Proposition>> {
            return emptyList()
        }

        override fun findByStatus(status: PropositionStatus): List<Proposition> {
            return propositions.values.filter { it.status == status }
        }

        override fun findByGrounding(chunkId: String): List<Proposition> {
            return propositions.values.filter { chunkId in it.grounding }
        }

        override fun findByMinLevel(minLevel: Int): List<Proposition> {
            return propositions.values.filter { it.level >= minLevel }
        }

        override fun findByContextId(contextId: com.embabel.agent.core.ContextId): List<Proposition> {
            return propositions.values.filter { it.contextId == contextId }
        }

        override fun findAll(): List<Proposition> = propositions.values.toList()

        override fun delete(id: String): Boolean = propositions.remove(id) != null

        override fun count(): Int = propositions.size
    }


    @Nested
    inner class PersistTests {

        @Test
        fun `persist saves new entities to repository`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val entityRepo = InMemoryNamedEntityDataRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            result.persist(propositionRepo, entityRepo)

            // Should have saved 2 entities - verify via findById
            val newEntities = result.newEntities()
            assertEquals(2, newEntities.size)
            for (entity in newEntities) {
                assertNotNull(entityRepo.findById(entity.id), "Entity ${entity.name} should be saved")
            }
            assertTrue(newEntities.any { it.name == "Alice" })
            assertTrue(newEntities.any { it.name == "Bob" })
        }

        @Test
        fun `persist saves propositions to repository`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Charlie", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val entityRepo = InMemoryNamedEntityDataRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            result.persist(propositionRepo, entityRepo)

            // Should have saved 2 propositions (one per chunk)
            assertEquals(2, propositionRepo.count())
        }

        @Test
        fun `persist with no new entities does not fail`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            // Pre-populate resolver with all entities
            val prePopulatedResolver = InMemoryEntityResolver()
            prePopulatedResolver.resolve(
                SuggestedEntities(
                    suggestedEntities = listOf(
                        SuggestedEntity(
                            labels = listOf("Person"),
                            name = "Alice",
                            summary = "Pre-existing",
                            chunkId = "pre",
                        )
                    )
                ),
                schema,
            )

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = prePopulatedResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val entityRepo = InMemoryNamedEntityDataRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            // No new entities
            assertEquals(0, result.newEntities().size)
            // But Alice is an updated entity (pre-existing)
            assertEquals(1, result.updatedEntities().size)

            // Pre-populate the entity repo with the existing entity (simulating it was already saved)
            val alice = result.updatedEntities().first()
            entityRepo.save(alice)

            // persist should still work
            result.persist(propositionRepo, entityRepo)

            // Updated entities are also persisted - verify via findById
            assertEquals("Alice", alice.name)
            assertNotNull(entityRepo.findById(alice.id), "Alice should be saved")

            // Proposition should be saved
            assertEquals(1, propositionRepo.count())
        }

        @Test
        fun `persist with empty result does nothing`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(emptyList(), context)

            val entityRepo = InMemoryNamedEntityDataRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            result.persist(propositionRepo, entityRepo)

            // No entities to persist
            assertEquals(0, result.entitiesToPersist().size)
            assertEquals(0, propositionRepo.count())
        }

        @Test
        fun `persist deduplicates entities across chunks`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-3", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val entityRepo = InMemoryNamedEntityDataRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            result.persist(propositionRepo, entityRepo)

            // Should only save Alice once - verify via findById
            val newEntities = result.newEntities()
            assertEquals(1, newEntities.size)
            val alice = newEntities.first()
            assertEquals("Alice", alice.name)
            assertNotNull(entityRepo.findById(alice.id), "Alice should be saved")
        }

        @Test
        fun `ChunkPropositionResult persist works correctly`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val chunk = Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val chunkResult = pipeline.processChunk(chunk, context)

            val entityRepo = InMemoryNamedEntityDataRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            chunkResult.persist(propositionRepo, entityRepo)

            // Verify entities were saved via findById
            val newEntities = chunkResult.newEntities()
            assertEquals(2, newEntities.size)
            for (entity in newEntities) {
                assertNotNull(entityRepo.findById(entity.id), "Entity ${entity.name} should be saved")
            }
            assertEquals(1, propositionRepo.count())
        }
    }

    /** Verifies that `persist` creates the expected structural relationships (HAS_PROPOSITION, MENTIONS, HAS_ENTITY). */
    @Nested
    inner class StructuralRelationshipTests {

        private fun createTrackingRepo(): TrackingEntityRepository =
            TrackingEntityRepository(schema)

        @Test
        fun `persist creates HAS_PROPOSITION relationships`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val entityRepo = TrackingEntityRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            result.persist(propositionRepo, entityRepo)

            // Should have 1 HAS_PROPOSITION relationship (chunk-1 -> proposition)
            val hasPropositionRels = entityRepo.relationshipsOfType(RelationshipTypes.HAS_PROPOSITION)
            assertEquals(1, hasPropositionRels.size)

            val rel = hasPropositionRels.first()
            assertEquals("chunk-1", rel.source.id)
            assertEquals("Chunk", rel.source.type)
            assertEquals(PersistablePropositions.PROPOSITION_LABEL, rel.target.type)
        }

        @Test
        fun `persist creates MENTIONS relationships with roles`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val entityRepo = TrackingEntityRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            result.persist(propositionRepo, entityRepo)

            // Should have 2 MENTIONS relationships (proposition -> Alice, proposition -> Bob)
            val mentionsRels = entityRepo.relationshipsOfType(RelationshipTypes.MENTIONS)
            assertEquals(2, mentionsRels.size)

            // Check roles
            val subjectRel = mentionsRels.find {
                it.relationship.properties[RelationshipTypes.ROLE_PROPERTY] == MentionRole.SUBJECT.name
            }
            val objectRel = mentionsRels.find {
                it.relationship.properties[RelationshipTypes.ROLE_PROPERTY] == MentionRole.OBJECT.name
            }

            assertNotNull(subjectRel, "Should have a SUBJECT mention")
            assertNotNull(objectRel, "Should have an OBJECT mention")

            // Source should be proposition, target should be entity
            assertEquals(PersistablePropositions.PROPOSITION_LABEL, subjectRel!!.source.type)
            assertEquals(PersistablePropositions.PROPOSITION_LABEL, objectRel!!.source.type)
        }

        @Test
        fun `persist creates HAS_ENTITY relationships`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val entityRepo = TrackingEntityRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            result.persist(propositionRepo, entityRepo)

            // Should have 2 HAS_ENTITY relationships (chunk-1 -> Alice, chunk-1 -> Bob)
            val hasEntityRels = entityRepo.relationshipsOfType(NamedEntityData.HAS_ENTITY)
            assertEquals(2, hasEntityRels.size)

            // All should be from chunk-1
            assertTrue(hasEntityRels.all { it.source.id == "chunk-1" })
            assertTrue(hasEntityRels.all { it.source.type == "Chunk" })
        }

        @Test
        fun `persist deduplicates HAS_ENTITY relationships across propositions`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            // Same entity mentioned in multiple chunks
            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val entityRepo = TrackingEntityRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            result.persist(propositionRepo, entityRepo)

            // Should have 2 HAS_ENTITY relationships (chunk-1 -> Alice, chunk-2 -> Alice)
            // But NOT duplicates if the same chunk-entity pair appears multiple times
            val hasEntityRels = entityRepo.relationshipsOfType(NamedEntityData.HAS_ENTITY)
            assertEquals(2, hasEntityRels.size)

            // One from each chunk
            assertTrue(hasEntityRels.any { it.source.id == "chunk-1" })
            assertTrue(hasEntityRels.any { it.source.id == "chunk-2" })
        }

        @Test
        fun `persist creates all relationship types together`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Charlie", metadata = emptyMap(), parentId = ""),
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            val entityRepo = TrackingEntityRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            result.persist(propositionRepo, entityRepo)

            // HAS_PROPOSITION: 2 (one per chunk/proposition)
            assertEquals(2, entityRepo.relationshipsOfType(RelationshipTypes.HAS_PROPOSITION).size)

            // MENTIONS: 3 (Alice as subject, Bob as object from chunk-1, Charlie as subject from chunk-2)
            assertEquals(3, entityRepo.relationshipsOfType(RelationshipTypes.MENTIONS).size)

            // HAS_ENTITY: 3 (chunk-1 -> Alice, chunk-1 -> Bob, chunk-2 -> Charlie)
            assertEquals(3, entityRepo.relationshipsOfType(NamedEntityData.HAS_ENTITY).size)
        }

        @Test
        fun `persist with no grounding creates no chunk relationships`() {
            // Create a result with propositions that have empty grounding
            val proposition = Proposition(
                contextId = testContextId,
                text = "Test proposition",
                mentions = listOf(
                    EntityMention(
                        span = "Alice",
                        type = "Person",
                        resolvedId = "alice-id",
                        role = MentionRole.SUBJECT,
                    )
                ),
                confidence = 0.9,
                grounding = emptyList(), // No grounding
            )

            val entityRepo = TrackingEntityRepository(schema)
            val propositionRepo = InMemoryPropositionRepository()

            // Directly call createStructuralRelationships
            PersistablePropositions.createStructuralRelationships(
                listOf(proposition),
                entityRepo
            )

            // Should have 0 HAS_PROPOSITION (no grounding)
            assertEquals(0, entityRepo.relationshipsOfType(RelationshipTypes.HAS_PROPOSITION).size)

            // Should have 0 HAS_ENTITY (no grounding)
            assertEquals(0, entityRepo.relationshipsOfType(NamedEntityData.HAS_ENTITY).size)

            // Should still have 1 MENTIONS relationship
            assertEquals(1, entityRepo.relationshipsOfType(RelationshipTypes.MENTIONS).size)
        }

        @Test
        fun `persist with unresolved mentions skips MENTIONS relationship`() {
            val proposition = Proposition(
                contextId = testContextId,
                text = "Test proposition",
                mentions = listOf(
                    EntityMention(
                        span = "Alice",
                        type = "Person",
                        resolvedId = null, // Unresolved
                        role = MentionRole.SUBJECT,
                    )
                ),
                confidence = 0.9,
                grounding = listOf("chunk-1"),
            )

            val entityRepo = TrackingEntityRepository(schema)

            PersistablePropositions.createStructuralRelationships(
                listOf(proposition),
                entityRepo
            )

            // Should have 1 HAS_PROPOSITION
            assertEquals(1, entityRepo.relationshipsOfType(RelationshipTypes.HAS_PROPOSITION).size)

            // Should have 0 MENTIONS (unresolved)
            assertEquals(0, entityRepo.relationshipsOfType(RelationshipTypes.MENTIONS).size)

            // Should have 0 HAS_ENTITY (unresolved)
            assertEquals(0, entityRepo.relationshipsOfType(NamedEntityData.HAS_ENTITY).size)
        }
    }

    @Nested
    inner class ProcessOnceTests {

        @Test
        fun `processes text and returns result`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.processOnce(
                text = "mentions:Alice,Bob",
                sourceId = "doc-1",
                context = context,
            )

            assertNotNull(result)
            assertEquals(1, result!!.propositions.size)
            assertEquals(2, result.newEntities().size)
        }

        @Test
        fun `returns null for already processed content`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)
            val historyStore = InMemoryChunkHistoryStore()

            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val first = pipeline.processOnce(
                text = "mentions:Alice",
                sourceId = "doc-1",
                context = context,
                historyStore = historyStore,
            )
            assertNotNull(first)

            val second = pipeline.processOnce(
                text = "mentions:Alice",
                sourceId = "doc-1",
                context = context,
                historyStore = historyStore,
            )
            assertNull(second, "Should return null for already processed content")
        }

        @Test
        fun `works without history store`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)

            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            // Without history store, same text can be processed multiple times
            val first = pipeline.processOnce(
                text = "mentions:Alice",
                sourceId = "doc-1",
                context = context,
            )
            val second = pipeline.processOnce(
                text = "mentions:Alice",
                sourceId = "doc-1",
                context = context,
            )

            assertNotNull(first)
            assertNotNull(second)
        }

        @Test
        fun `records processing in history store`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)
            val historyStore = InMemoryChunkHistoryStore()

            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            pipeline.processOnce(
                text = "mentions:Alice",
                sourceId = "doc-1",
                context = context,
                historyStore = historyStore,
            )

            // Verify the bookmark was recorded
            val bookmark = historyStore.getLastBookmark(BookmarkKey(testContextId, "doc-1"))
            assertNotNull(bookmark)
            assertEquals("doc-1", bookmark!!.sourceId)
            assertEquals(1, bookmark.endIndex)
        }

        @Test
        fun `deduplication does not leak across contexts`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline.withExtractor(extractor)
            val historyStore = InMemoryChunkHistoryStore()

            val contextA = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = ContextId("context-a"),
            )
            val contextB = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = ContextId("context-b"),
            )

            assertNotNull(
                pipeline.processOnce(
                    text = "mentions:Alice",
                    sourceId = "doc-1",
                    context = contextA,
                    historyStore = historyStore,
                )
            )
            assertNull(
                pipeline.processOnce(
                    text = "mentions:Alice",
                    sourceId = "doc-1",
                    context = contextA,
                    historyStore = historyStore,
                ),
                "Same context should dedupe",
            )
            assertNotNull(
                pipeline.processOnce(
                    text = "mentions:Alice",
                    sourceId = "doc-1",
                    context = contextB,
                    historyStore = historyStore,
                ),
                "Different context should not dedupe",
            )
        }
    }

    @Nested
    inner class MentionFilterTests {

        // Schema with validation rules for testing
        private val companyTypeWithValidation = DynamicType(
            name = "Company",
            ownProperties = listOf(
                ValidatedPropertyDefinition(
                    name = "name",
                    validationRules = listOf(
                        NoVagueReferences(),
                        LengthConstraint(maxLength = 15)
                    )
                )
            )
        )

        private val schemaWithValidation = DataDictionary.fromDomainTypes(
            name = "TestSchemaWithValidation",
            domainTypes = listOf(companyTypeWithValidation)
        )

        @Test
        fun `pipeline with mention filter filters low-quality mentions`() {
            val extractor = MockPropositionExtractor()
            val filter = SchemaValidatedMentionFilter(schemaWithValidation)
            val pipeline = PropositionPipeline
                .withExtractor(extractor)
                .withMentionFilter(filter)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:this company,OpenAI", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // Only "OpenAI" should survive filtering (not "this company")
            val newEntities = result.newEntities()
            assertEquals(1, newEntities.size, "Should have only 1 entity after filtering")
            assertEquals("OpenAI", newEntities.first().name)
        }

        @Test
        fun `pipeline without filter works as before (backward compatibility)`() {
            val extractor = MockPropositionExtractor()
            val pipeline = PropositionPipeline
                .withExtractor(extractor)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:this company,OpenAI", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // Both mentions should create entities (no filtering)
            val newEntities = result.newEntities()
            assertEquals(2, newEntities.size, "Should have 2 entities without filter")
            assertTrue(newEntities.any { it.name == "this company" })
            assertTrue(newEntities.any { it.name == "OpenAI" })
        }

        @Test
        fun `schema filter applies multiple validation rules`() {
            val extractor = MockPropositionExtractor()
            val filter = SchemaValidatedMentionFilter(schemaWithValidation)
            val pipeline = PropositionPipeline
                .withExtractor(extractor)
                .withMentionFilter(filter)

            val chunks = listOf(
                Chunk(
                    id = "chunk-1",
                    text = "mentions:this company,OpenAI,VeryLongCompanyNameThatExceedsLimit",
                    metadata = emptyMap(),
                    parentId = ""
                )
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // Only "OpenAI" should survive (not "this company" or the long name)
            val newEntities = result.newEntities()
            assertEquals(1, newEntities.size, "Should have only 1 entity after schema filtering")
            assertEquals("OpenAI", newEntities.first().name)
        }

        @Test
        fun `filtered mentions don't create entities but proposition is still stored`() {
            val extractor = MockPropositionExtractor()
            val filter = SchemaValidatedMentionFilter(schemaWithValidation)
            val pipeline = PropositionPipeline
                .withExtractor(extractor)
                .withMentionFilter(filter)

            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:this investment", metadata = emptyMap(), parentId = "")
            )
            val context = SourceAnalysisContext(
                schema = schema,
                entityResolver = AlwaysCreateEntityResolver,
                contextId = testContextId,
            )

            val result = pipeline.process(chunks, context)

            // No entities created (mention was filtered)
            assertEquals(0, result.newEntities().size)

            // But proposition is still stored
            assertEquals(1, result.allPropositions.size)
        }
    }

    /**
     * Concurrency contract: ordering, per-chunk error isolation, and cross-chunk identity under
     * the [ParallelExtractionStrategy] and [BatchedExtractionStrategy]. The test owns the
     * [ExecutorService] and shuts it down; the library never does.
     */
    @Nested
    inner class ExecutionStrategyTests {

        private val executor: ExecutorService = Executors.newFixedThreadPool(4)

        @org.junit.jupiter.api.AfterEach
        fun tearDown() {
            executor.shutdownNow()
        }

        private fun newPipeline(strategy: ExtractionExecutionStrategy) =
            PropositionPipeline.withExtractor(MockPropositionExtractor()).withExecutionStrategy(strategy)

        private fun context() = SourceAnalysisContext(
            schema = schema,
            entityResolver = AlwaysCreateEntityResolver,
            contextId = testContextId,
        )

        private fun strategies(): List<ExtractionExecutionStrategy> = listOf(
            ParallelExtractionStrategy(executor),
            BatchedExtractionStrategy(batchSize = 2, executor = executor),
        )

        @Test
        fun `failed chunk is isolated as Failed while good chunks succeed`() {
            for (strategy in strategies()) {
                val chunks = listOf(
                    Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                    Chunk(id = "chunk-2", text = "fail:boom", metadata = emptyMap(), parentId = ""),
                    Chunk(id = "chunk-3", text = "mentions:Bob", metadata = emptyMap(), parentId = ""),
                )

                val result = newPipeline(strategy).process(chunks, context())

                // Invariant: one result per input chunk, in order.
                assertEquals(chunks.size, result.chunkResults.size)

                // chunk-2 is Failed; the others are Success.
                assertTrue(result.chunkResults[0] is ChunkPropositionResult.Success)
                assertTrue(result.chunkResults[1] is ChunkPropositionResult.Failed)
                assertTrue(result.chunkResults[2] is ChunkPropositionResult.Success)

                // failedChunkIds carries exactly the failed chunk id.
                assertEquals(listOf("chunk-2"), result.failedChunkIds)

                // Good chunks still produced their entities.
                val names = result.newEntities().map { it.name }.toSet()
                assertTrue(names.contains("Alice"))
                assertTrue(names.contains("Bob"))
            }
        }

        @Test
        fun `a chunk that fails during resolution is isolated as Failed`() {
            // extraction succeeds for every chunk; the resolver throws for chunk-2
            // during resolution. The run must still produce one result per chunk with
            // chunk-2 surfaced as Failed — not abort and discard the good chunks' results.
            val resolverThatThrowsOnBoom = object : EntityResolver {
                override fun resolve(
                    suggestedEntities: SuggestedEntities,
                    schema: DataDictionary,
                ): Resolutions<SuggestedEntityResolution> {
                    if (suggestedEntities.suggestedEntities.any { it.name == "Boom" }) {
                        throw IllegalStateException("resolution failed for Boom")
                    }
                    return AlwaysCreateEntityResolver.resolve(suggestedEntities, schema)
                }
            }
            val failingResolveContext = SourceAnalysisContext(
                schema = schema,
                entityResolver = resolverThatThrowsOnBoom,
                contextId = testContextId,
            )

            for (strategy in strategies()) {
                val chunks = listOf(
                    Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                    Chunk(id = "chunk-2", text = "mentions:Boom", metadata = emptyMap(), parentId = ""),
                    Chunk(id = "chunk-3", text = "mentions:Bob", metadata = emptyMap(), parentId = ""),
                )

                val result = newPipeline(strategy).process(chunks, failingResolveContext)

                // Invariant: one result per input chunk, in order; the run did not abort.
                assertEquals(chunks.size, result.chunkResults.size)
                assertTrue(result.chunkResults[0] is ChunkPropositionResult.Success)
                assertTrue(result.chunkResults[1] is ChunkPropositionResult.Failed)
                assertTrue(result.chunkResults[2] is ChunkPropositionResult.Success)
                assertEquals(listOf("chunk-2"), result.failedChunkIds)

                // Good chunks still produced their entities despite the resolution failure.
                val names = result.newEntities().map { it.name }.toSet()
                assertTrue(names.contains("Alice"))
                assertTrue(names.contains("Bob"))
            }
        }

        @Test
        fun `results preserve input order`() {
            for (strategy in strategies()) {
                val chunks = (0 until 10).map {
                    Chunk(id = "chunk-$it", text = "mentions:E$it", metadata = emptyMap(), parentId = "")
                }

                val result = newPipeline(strategy).process(chunks, context())

                assertEquals(chunks.size, result.chunkResults.size)
                chunks.forEachIndexed { i, chunk ->
                    assertEquals(chunk.id, result.chunkResults[i].chunkId, "Order mismatch at index $i")
                }
            }
        }

        @Test
        fun `cross-chunk identity preserved under parallel and batched`() {
            for (strategy in strategies()) {
                val chunks = listOf(
                    Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = ""),
                    Chunk(id = "chunk-2", text = "mentions:Alice,Charlie", metadata = emptyMap(), parentId = ""),
                )

                val result = newPipeline(strategy).process(chunks, context())

                // 3 unique entities; Alice appears once in newEntities (resolved serially in the resolution stage).
                val newEntities = result.newEntities()
                assertEquals(3, newEntities.size, "Found: ${newEntities.map { it.name }}")
                assertEquals(1, newEntities.count { it.name == "Alice" })

                // Every Alice mention resolves to the same id across chunks.
                val aliceId = newEntities.first { it.name == "Alice" }.id
                for (prop in result.allPropositions) {
                    prop.mentions.find { it.span == "Alice" }?.let {
                        assertEquals(aliceId, it.resolvedId, "All Alice mentions must share one id under $strategy")
                    }
                }
            }
        }

        @Test
        fun `default pipeline (no withExecutionStrategy) matches serial behavior`() {
            // Oracle: the existing default-path tests assert serial behavior. Here we assert
            // an explicit SerialExtractionStrategy yields identical results to the default.
            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
            )

            val defaultResult = PropositionPipeline.withExtractor(MockPropositionExtractor())
                .process(chunks, context())
            val serialResult = newPipeline(SerialExtractionStrategy).process(chunks, context())

            assertEquals(defaultResult.chunkResults.size, serialResult.chunkResults.size)
            assertEquals(defaultResult.newEntities().size, serialResult.newEntities().size)
            assertEquals(1, serialResult.newEntities().size)
        }

        @Test
        fun `single-chunk processChunk still throws on a failing chunk (A3)`() {
            val pipeline = PropositionPipeline.withExtractor(MockPropositionExtractor())
            val failingChunk = Chunk(id = "chunk-x", text = "fail:boom", metadata = emptyMap(), parentId = "")

            // processChunk does NOT isolate failure — it propagates (unlike process()).
            assertThrows<IllegalStateException> {
                pipeline.processChunk(failingChunk, context())
            }
        }

        @Test
        fun `default strategy and batched-size-one yield equivalent results under partial failure`() {
            // Safe-path equivalence at the PIPELINE level: the default pipeline
            // (Serial) and an explicit BatchedExtractionStrategy(1) must produce the same number
            // of chunk results, the same Failed/Success classification per index, and the same
            // failedChunkIds — proving Batched(1) is a true drop-in for the serial default.
            val chunks = listOf(
                Chunk(id = "chunk-1", text = "mentions:Alice", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-2", text = "fail:boom", metadata = emptyMap(), parentId = ""),
                Chunk(id = "chunk-3", text = "mentions:Bob", metadata = emptyMap(), parentId = ""),
            )

            val defaultResult = PropositionPipeline.withExtractor(MockPropositionExtractor())
                .process(chunks, context())
            val batched1Result = newPipeline(
                BatchedExtractionStrategy(batchSize = 1, executor = executor)
            ).process(chunks, context())

            assertEquals(defaultResult.chunkResults.size, batched1Result.chunkResults.size)
            assertEquals(defaultResult.failedChunkIds, batched1Result.failedChunkIds)
            assertEquals(listOf("chunk-2"), batched1Result.failedChunkIds)

            defaultResult.chunkResults.forEachIndexed { i, expected ->
                val actual = batched1Result.chunkResults[i]
                assertEquals(expected::class, actual::class, "classification mismatch at index $i")
                assertEquals(expected.chunkId, actual.chunkId, "chunkId mismatch at index $i")
            }
            assertEquals(
                defaultResult.newEntities().map { it.name }.toSet(),
                batched1Result.newEntities().map { it.name }.toSet(),
            )
        }
    }
}
