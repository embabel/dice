package com.embabel.dice.pipeline

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.RetrievableEntity
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.dice.common.*
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.proposition.*
import com.embabel.dice.text2graph.builder.Animal
import com.embabel.dice.text2graph.builder.Person
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
 * Tests for [PropositionPipeline] focusing on cross-chunk entity resolution.
 *
 * The pipeline wraps the context's EntityResolver with MultiEntityResolver + InMemoryEntityResolver
 * to enable entities discovered in earlier chunks to be recognized in later chunks.
 */
class PropositionPipelineTest {

    private val schema = DataDictionary.fromClasses(Person::class.java, Animal::class.java)

    /**
     * Simple mock extractor that creates propositions with entity mentions based on chunk content.
     * Uses a simple pattern: chunk text contains "mentions:Entity1,Entity2" to specify entities.
     */
    private class MockPropositionExtractor : PropositionExtractor {

        override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions {
            // Parse entities from chunk text: "mentions:Alice,Bob" -> [Alice, Bob]
            val mentionPattern = Regex("mentions:([\\w,]+)")
            val match = mentionPattern.find(chunk.text)
            val entityNames = match?.groupValues?.get(1)?.split(",") ?: emptyList()

            val mentions = entityNames.mapIndexed { index, name ->
                SuggestedMention(
                    span = name.trim(),
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
            context: SourceAnalysisContext
        ): SuggestedEntities {
            val seen = mutableSetOf<MentionKey>()
            val entities = mutableListOf<SuggestedEntity>()

            for (prop in suggestedPropositions.propositions) {
                for (mention in prop.mentions) {
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

            return SuggestedEntities(suggestedEntities = entities)
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

    /**
     * Simple in-memory proposition repository for testing.
     */
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

    /**
     * Tests for structural relationship creation during persist.
     */
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
            val hasEntityRels = entityRepo.relationshipsOfType(RetrievableEntity.HAS_ENTITY)
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
            val hasEntityRels = entityRepo.relationshipsOfType(RetrievableEntity.HAS_ENTITY)
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
            assertEquals(3, entityRepo.relationshipsOfType(RetrievableEntity.HAS_ENTITY).size)
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
            assertEquals(0, entityRepo.relationshipsOfType(RetrievableEntity.HAS_ENTITY).size)

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
            assertEquals(0, entityRepo.relationshipsOfType(RetrievableEntity.HAS_ENTITY).size)
        }
    }
}
