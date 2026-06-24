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
package com.embabel.dice.web.rest

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.common.support.InMemorySchemaRegistry
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.ingestion.HierarchicalContentReader
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.pipeline.PropositionResults
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.revision.RevisionResult
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * Contract tests for the proposition extraction REST controller.
 *
 * Verifies that the `/extract` endpoint runs the pipeline, persists propositions, and returns the
 * expected JSON shape — using a mocked pipeline and an in-memory repository.
 */
class PropositionPipelineControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var propositionRepository: TestPropositionRepository
    private lateinit var propositionPipeline: PropositionPipeline
    private lateinit var entityResolver: EntityResolver
    private lateinit var schemaRegistry: InMemorySchemaRegistry
    private lateinit var objectMapper: ObjectMapper

    @JsonClassDescription("A composer of music")
    data class Composer(val id: String, val name: String)

    @JsonClassDescription("A musical work")
    data class Work(val id: String, val title: String)

    @BeforeEach
    fun setUp() {
        propositionRepository = TestPropositionRepository()
        propositionPipeline = mockk<PropositionPipeline>()
        entityResolver = AlwaysCreateEntityResolver
        val schema = DataDictionary.fromClasses("test", Composer::class.java, Work::class.java)
        schemaRegistry = InMemorySchemaRegistry(schema)

        objectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())

        val controller = PropositionPipelineController(
            propositionPipeline = propositionPipeline,
            propositionRepository = propositionRepository,
            entityResolver = entityResolver,
            schemaRegistry = schemaRegistry,
        )

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Test
    fun `POST extract returns extracted propositions`() {
        val contextId = "test-context"
        val requestBody = """
            {
                "text": "I love Brahms and Wagner",
                "sourceId": "conversation-123"
            }
        """.trimIndent()

        val mockProposition = Proposition(
            contextId = ContextId(contextId),
            text = "User loves Brahms",
            mentions = listOf(
                EntityMention("Brahms", "Composer", "composer-brahms", MentionRole.OBJECT)
            ),
            confidence = 0.95,
        )

        val mockResult = ChunkPropositionResult.Success(
            chunkId = "chunk-123",
            suggestedPropositions = SuggestedPropositions(
                chunkId = "chunk-123",
                propositions = listOf(
                    SuggestedProposition(
                        text = "User loves Brahms",
                        mentions = listOf(SuggestedMention("Brahms", "Composer", role = "OBJECT")),
                        confidence = 0.95,
                    )
                )
            ),
            entityResolutions = Resolutions(
                chunkIds = setOf("chunk-123"),
                resolutions = listOf(
                    NewEntity(
                        SuggestedEntity(
                            labels = listOf("Composer"),
                            name = "Brahms",
                            summary = "A composer",
                            chunkId = "chunk-123",
                        )
                    )
                ),
            ),
            propositions = listOf(mockProposition),
            revisionResults = emptyList(),
        )

        every { propositionPipeline.processChunk(any(), any()) } returns mockResult

        mockMvc.perform(
            post("/api/v1/contexts/$contextId/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contextId").value(contextId))
            .andExpect(jsonPath("$.propositions").isArray)
            .andExpect(jsonPath("$.propositions[0].text").value("User loves Brahms"))
            .andExpect(jsonPath("$.propositions[0].confidence").value(0.95))
            .andExpect(jsonPath("$.propositions[0].mentions[0].name").value("Brahms"))
            .andExpect(jsonPath("$.propositions[0].mentions[0].type").value("Composer"))
            .andExpect(jsonPath("$.entities.created").isArray)
    }

    @Test
    fun `POST extract saves propositions to repository`() {
        val contextId = "test-context"
        val requestBody = """
            {
                "text": "Wagner composed Tristan",
                "sourceId": "conversation-456"
            }
        """.trimIndent()

        val mockProposition = Proposition(
            contextId = ContextId(contextId),
            text = "Wagner composed Tristan",
            mentions = listOf(
                EntityMention("Wagner", "Composer", "composer-wagner", MentionRole.SUBJECT),
                EntityMention("Tristan", "Work", "work-tristan", MentionRole.OBJECT),
            ),
            confidence = 0.9,
        )

        val mockResult = ChunkPropositionResult.Success(
            chunkId = "chunk-456",
            suggestedPropositions = SuggestedPropositions(
                chunkId = "chunk-456",
                propositions = emptyList(),
            ),
            entityResolutions = Resolutions(
                chunkIds = setOf("chunk-456"),
                resolutions = emptyList(),
            ),
            propositions = listOf(mockProposition),
            revisionResults = emptyList(),
        )

        every { propositionPipeline.processChunk(any(), any()) } returns mockResult

        mockMvc.perform(
            post("/api/v1/contexts/$contextId/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        )
            .andExpect(status().isOk)

        // Verify proposition was saved
        assert(propositionRepository.count() == 1)
        val saved = propositionRepository.findAll().first()
        assert(saved.text == "Wagner composed Tristan")
    }

    @Test
    fun `POST extract persists the contradicted original, not just the new proposition`() {
        val contextId = "test-context"
        val requestBody = """{"text": "Brahms was born in 1833", "sourceId": "c1"}"""

        // A revision that contradicts an existing proposition: the original is returned with
        // reduced confidence and CONTRADICTED status, separate from the newly extracted one.
        val original = Proposition(
            id = "orig-1",
            contextId = ContextId(contextId),
            text = "Brahms was born in 1830",
            mentions = listOf(EntityMention("Brahms", "Composer", "composer-brahms", MentionRole.SUBJECT)),
            confidence = 0.3,
            status = PropositionStatus.CONTRADICTED,
        )
        val newProp = Proposition(
            contextId = ContextId(contextId),
            text = "Brahms was born in 1833",
            mentions = listOf(EntityMention("Brahms", "Composer", "composer-brahms", MentionRole.SUBJECT)),
            confidence = 0.95,
        )
        val mockResult = ChunkPropositionResult.Success(
            chunkId = "chunk-1",
            suggestedPropositions = SuggestedPropositions(chunkId = "chunk-1", propositions = emptyList()),
            entityResolutions = Resolutions(chunkIds = setOf("chunk-1"), resolutions = emptyList()),
            // `propositions` carries only the new one; the original lives in the revision result.
            propositions = listOf(newProp),
            revisionResults = listOf(RevisionResult.Contradicted(original = original, new = newProp)),
        )

        every { propositionPipeline.processChunk(any(), any()) } returns mockResult

        mockMvc.perform(
            post("/api/v1/contexts/$contextId/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        ).andExpect(status().isOk)

        // Both the new proposition AND the retired original must be persisted.
        assert(propositionRepository.count() == 2) { "expected new + contradicted original to be saved" }
        val saved = propositionRepository.findAll().associateBy { it.id }
        assert(saved.containsKey("orig-1")) { "the contradicted original must be persisted" }
        assert(saved["orig-1"]!!.status == PropositionStatus.CONTRADICTED)
    }

    @Test
    fun `POST extract rejects blank text with 400`() {
        mockMvc.perform(
            post("/api/v1/contexts/test-context/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text": "   "}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST extract file isolates a failing chunk and returns partial results`() {
        // A two-chunk upload where the second chunk fails extraction. The endpoint must route through
        // the batch process() — which isolates a failure into a typed Failed result and honors the
        // execution strategy — rather than the failure-propagating processChunk() loop that would
        // 500 the whole upload on one bad chunk.
        val contextId = "test-context"
        val reader = mockk<HierarchicalContentReader>()
        val chunker = mockk<ContentChunker>()
        val document = mockk<NavigableDocument>(relaxed = true)
        every { reader.parseContent(any(), any()) } returns document
        every { chunker.chunk(any()) } returns listOf(
            Chunk.create(text = "good chunk", parentId = "doc"),
            Chunk.create(text = "bad chunk", parentId = "doc"),
        )

        val proposition = Proposition(
            contextId = ContextId(contextId),
            text = "User loves Brahms",
            mentions = emptyList(),
            confidence = 0.9,
        )
        val success = ChunkPropositionResult.Success(
            chunkId = "chunk-ok",
            suggestedPropositions = SuggestedPropositions(chunkId = "chunk-ok", propositions = emptyList()),
            entityResolutions = Resolutions(chunkIds = setOf("chunk-ok"), resolutions = emptyList()),
            propositions = listOf(proposition),
            revisionResults = emptyList(),
        )
        val failed = ChunkPropositionResult.Failed("chunk-bad", "extraction blew up")
        every { propositionPipeline.process(any(), any()) } returns
            PropositionResults(chunkResults = listOf(success, failed), allPropositions = listOf(proposition))

        val fileController = PropositionPipelineController(
            propositionPipeline = propositionPipeline,
            propositionRepository = propositionRepository,
            entityResolver = entityResolver,
            schemaRegistry = schemaRegistry,
            contentReader = reader,
            contentChunker = chunker,
        )
        val mvc = MockMvcBuilders.standaloneSetup(fileController)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()

        mvc.perform(
            multipart("/api/v1/contexts/$contextId/extract/file")
                .file(MockMultipartFile("file", "doc.txt", "text/plain", "content".toByteArray()))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.chunksProcessed").value(2))
            .andExpect(jsonPath("$.entities.failed[0]").value("chunk-bad"))

        verify(exactly = 1) { propositionPipeline.process(any(), any()) }
        verify(exactly = 0) { propositionPipeline.processChunk(any(), any()) }
    }
}
