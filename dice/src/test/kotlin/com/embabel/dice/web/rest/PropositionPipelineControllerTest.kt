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
import com.embabel.dice.common.SourceAnalysisContext
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
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceIdentityBounds
import com.embabel.dice.provenance.UriLocator
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
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
            .setMessageConverters(
                StringHttpMessageConverter(),
                MappingJackson2HttpMessageConverter(objectMapper),
            )
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
    fun `POST extract carries every locator kind with an opaque revision and returns scalar revision`() {
        val contextId = "test-context"
        val sourceRevision = "rev:opaque|雪"
        val proposition = Proposition(
            contextId = ContextId(contextId),
            text = "Revision-aware fact",
            mentions = emptyList(),
            confidence = 0.9,
            provenanceEntries = listOf(
                ProvenanceEntry(
                    locator = UriLocator("https://example.com/source"),
                    sourceRevision = sourceRevision,
                )
            ),
        )
        val result = ChunkPropositionResult.Success(
            chunkId = "chunk-revision",
            suggestedPropositions = SuggestedPropositions(
                chunkId = "chunk-revision",
                propositions = emptyList(),
            ),
            entityResolutions = Resolutions(
                chunkIds = setOf("chunk-revision"),
                resolutions = emptyList(),
            ),
            propositions = listOf(proposition),
            revisionResults = emptyList(),
        )
        every {
            propositionPipeline.processChunk(any(), match { it.sourceRevision != null })
        } returns result

        val locatorCases = listOf(
            """{"kind":"uri","value":"https://example.com/source"}""" to
                    "uri:https://example.com/source",
            """{"kind":"file","value":"/vault/note.md","display":"Note"}""" to
                    "file:/vault/note.md",
            """{"kind":"content","value":"sha256:abc"}""" to
                    "content:sha256:abc",
            """{"kind":"connector","value":"message-42","connectorId":"gmail"}""" to
                    "connector:gmail:message-42",
        )

        locatorCases.forEach { (sourceLocator, expectedKey) ->
            mockMvc.perform(
                post("/api/v1/contexts/$contextId/extract")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "text": "Revision-aware fact",
                            "sourceId": "legacy-parent",
                            "sourceLocator": $sourceLocator,
                            "sourceRevision": "$sourceRevision"
                        }
                        """.trimIndent()
                    )
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.propositions[0].provenance[0].locator")
                    .value("uri:https://example.com/source"))
                .andExpect(jsonPath("$.propositions[0].provenance[0].sourceRevision")
                    .value(sourceRevision))
                .andExpect(jsonPath("$.propositions[0].provenance[0].sourceKey").doesNotExist())

            verify(exactly = 1) {
                propositionPipeline.processChunk(
                    match { it.parentId == "legacy-parent" },
                    match {
                        it.sourceLocator?.key() == expectedKey &&
                                it.sourceRevision?.sourceKey == expectedKey &&
                                it.sourceRevision?.sourceRevision == sourceRevision
                    },
                )
            }
        }
    }

    @Test
    fun `POST extract replay persists one grounded provenance row per source revision`() {
        val observedChunkIds = mutableListOf<String>()
        every {
            propositionPipeline.processChunk(any(), any())
        } answers {
            val chunk = firstArg<Chunk>()
            val context = secondArg<SourceAnalysisContext>()
            val provenanceEntry = ProvenanceEntry(
                locator = requireNotNull(context.sourceLocator),
                chunkId = chunk.id,
                sourceRevision = context.sourceRevision?.sourceRevision,
            )
            observedChunkIds += chunk.id
            ChunkPropositionResult.Success(
                chunkId = chunk.id,
                suggestedPropositions = SuggestedPropositions(
                    chunkId = chunk.id,
                    propositions = emptyList(),
                ),
                entityResolutions = Resolutions(
                    chunkIds = setOf(chunk.id),
                    resolutions = emptyList(),
                ),
                propositions = listOf(
                    Proposition(
                        id = "fact-${chunk.id}",
                        contextId = context.contextId,
                        text = "Stable fact",
                        mentions = emptyList(),
                        confidence = 0.9,
                        grounding = listOf(chunk.id),
                        provenanceEntries = listOf(provenanceEntry),
                    ),
                ),
                revisionResults = emptyList(),
            )
        }

        fun extract(revision: String) {
            mockMvc.perform(
                post("/api/v1/contexts/test-context/extract")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "text": "Stable fact",
                            "sourceId": "logical-source",
                            "sourceLocator": {"kind":"uri","value":"https://example.com/source"},
                            "sourceRevision": "$revision"
                        }
                        """.trimIndent()
                    )
            ).andExpect(status().isOk)
        }

        extract("r1")
        extract("r1")
        extract("r2")

        assertEquals(observedChunkIds[0], observedChunkIds[1])
        assertNotEquals(observedChunkIds[0], observedChunkIds[2])

        assertEquals(2, propositionRepository.count())
        val persistedR1 = requireNotNull(
            propositionRepository.findById("fact-${observedChunkIds[0]}"),
        )
        val persistedR2 = requireNotNull(
            propositionRepository.findById("fact-${observedChunkIds[2]}"),
        )
        assertEquals(1, persistedR1.grounding.size)
        assertEquals(1, persistedR1.provenanceEntries.size)
        assertEquals("r1", persistedR1.provenanceEntries.single().sourceRevision)
        assertEquals(1, persistedR2.grounding.size)
        assertEquals(1, persistedR2.provenanceEntries.size)
        assertEquals("r2", persistedR2.provenanceEntries.single().sourceRevision)
    }

    @Test
    fun `two contexts posting one revision of one source get different chunk ids`() {
        val observedChunkIds = mutableListOf<String>()
        every { propositionPipeline.processChunk(any(), any()) } answers {
            val chunk = firstArg<Chunk>()
            observedChunkIds += chunk.id
            ChunkPropositionResult.Success(
                chunkId = chunk.id,
                suggestedPropositions = SuggestedPropositions(chunkId = chunk.id, propositions = emptyList()),
                entityResolutions = Resolutions(chunkIds = setOf(chunk.id), resolutions = emptyList()),
                propositions = emptyList(),
                revisionResults = emptyList(),
            )
        }

        fun extractIn(contextId: String) {
            mockMvc.perform(
                post("/api/v1/contexts/$contextId/extract")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "text": "Identical text in both tenants",
                            "sourceId": "logical-source",
                            "sourceLocator": {"kind":"uri","value":"https://example.com/source"},
                            "sourceRevision": "r1"
                        }
                        """.trimIndent()
                    )
            ).andExpect(status().isOk)
        }

        // Same locator, same revision, same ordinal, same text — everything but the tenant.
        extractIn("tenant-one")
        extractIn("tenant-two")
        extractIn("tenant-one")

        // Chunk ids are what grounding is looked up by, and findByGrounding is not context-scoped,
        // so a shared id would let one tenant's grounding lookup reach the other's propositions.
        assertNotEquals(observedChunkIds[0], observedChunkIds[1])
        // Still deterministic within a tenant, which is the property the ids exist for.
        assertEquals(observedChunkIds[0], observedChunkIds[2])
    }

    @Test
    fun `POST extract rejects invalid locator unions and revision without locator before pipeline`() {
        val invalidRequests = listOf(
            """{"text":"fact","sourceRevision":"r1"}""",
            """{"text":"fact","sourceLocator":{"kind":"unknown","value":"x"}}""",
            """{"text":"fact","sourceLocator":{"kind":"uri","value":"https://example.com","connectorId":"gmail"}}""",
            """{"text":"fact","sourceLocator":{"kind":"connector","value":"message-42"}}""",
            """{"text":"fact","sourceLocator":{"kind":"uri","value":"   "}}""",
        )

        invalidRequests.forEach { requestBody ->
            mockMvc.perform(
                post("/api/v1/contexts/test-context/extract")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            ).andExpect(status().isBadRequest)
        }

        verify(exactly = 0) { propositionPipeline.processChunk(any(), any()) }
    }

    @Test
    fun `POST extract keeps old requests revisionless and omits response revision`() {
        val proposition = Proposition(
            contextId = ContextId("test-context"),
            text = "Revisionless fact",
            mentions = emptyList(),
            confidence = 0.9,
            provenanceEntries = listOf(ProvenanceEntry(locator = UriLocator("https://example.com/source"))),
        )
        val result = ChunkPropositionResult.Success(
            chunkId = "chunk-legacy",
            suggestedPropositions = SuggestedPropositions(
                chunkId = "chunk-legacy",
                propositions = emptyList(),
            ),
            entityResolutions = Resolutions(
                chunkIds = setOf("chunk-legacy"),
                resolutions = emptyList(),
            ),
            propositions = listOf(proposition),
            revisionResults = emptyList(),
        )
        every { propositionPipeline.processChunk(any(), any()) } returns result

        mockMvc.perform(
            post("/api/v1/contexts/test-context/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"Revisionless fact","sourceId":"legacy-parent"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.propositions[0].provenance[0].sourceRevision").doesNotExist())

        mockMvc.perform(
            post("/api/v1/contexts/test-context/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "text": "Revisionless fact",
                        "sourceId": "locator-parent",
                        "sourceLocator": {"kind":"uri","value":"https://example.com/source"}
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.propositions[0].provenance[0].sourceRevision").doesNotExist())

        verify(exactly = 1) {
            propositionPipeline.processChunk(
                match { it.parentId == "legacy-parent" },
                match { it.sourceLocator == null && it.sourceRevision == null },
            )
        }
        verify(exactly = 1) {
            propositionPipeline.processChunk(
                match { it.parentId == "locator-parent" },
                match {
                    it.sourceLocator?.key() == "uri:https://example.com/source" &&
                            it.sourceRevision == null
                },
            )
        }
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
            .setMessageConverters(
                StringHttpMessageConverter(),
                MappingJackson2HttpMessageConverter(objectMapper),
            )
            .build()

        mvc.perform(
            multipart("/api/v1/contexts/$contextId/extract/file")
                .file(MockMultipartFile("file", "doc.txt", "text/plain", "content".toByteArray()))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.chunksProcessed").value(2))
            .andExpect(jsonPath("$.entities.failed[0]").value("chunk-bad"))

        verify(exactly = 1) {
            propositionPipeline.process(
                any(),
                match { it.sourceLocator == null && it.sourceRevision == null },
            )
        }
        verify(exactly = 0) { propositionPipeline.processChunk(any(), any()) }
    }

    @Test
    fun `POST extract file rejects revision without locator before reading or pipeline`() {
        val reader = mockk<HierarchicalContentReader>()
        val chunker = mockk<ContentChunker>()
        val fileController = PropositionPipelineController(
            propositionPipeline = propositionPipeline,
            propositionRepository = propositionRepository,
            entityResolver = entityResolver,
            schemaRegistry = schemaRegistry,
            contentReader = reader,
            contentChunker = chunker,
            objectMapper = objectMapper,
        )
        val mvc = MockMvcBuilders.standaloneSetup(fileController)
            .setMessageConverters(
                StringHttpMessageConverter(),
                MappingJackson2HttpMessageConverter(objectMapper),
            )
            .build()

        mvc.perform(
            multipart("/api/v1/contexts/test-context/extract/file")
                .file(MockMultipartFile("file", "empty.txt", "text/plain", byteArrayOf()))
                .file(MockMultipartFile("sourceRevision", "", "text/plain", "r1".toByteArray()))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { reader.parseContent(any(), any()) }
        verify(exactly = 0) { propositionPipeline.process(any(), any()) }
    }

    @Test
    fun `POST extract file carries locator and opaque revision through batch path`() {
        val contextId = "test-context"
        val sourceRevision = "mailbox:v2|雪"
        val reader = mockk<HierarchicalContentReader>()
        val chunker = mockk<ContentChunker>()
        val document = mockk<NavigableDocument>(relaxed = true)
        every { reader.parseContent(any(), any()) } returns document
        every { chunker.chunk(any()) } returns listOf(
            Chunk.create(text = "mail content", parentId = "legacy-source"),
        )
        val processedChunkIds = mutableListOf<List<String>>()
        every {
            propositionPipeline.process(any(), match { it.sourceRevision != null })
        } answers {
            processedChunkIds += firstArg<List<Chunk>>().map { it.id }
            PropositionResults(chunkResults = emptyList(), allPropositions = emptyList())
        }

        val fileController = PropositionPipelineController(
            propositionPipeline = propositionPipeline,
            propositionRepository = propositionRepository,
            entityResolver = entityResolver,
            schemaRegistry = schemaRegistry,
            contentReader = reader,
            contentChunker = chunker,
            objectMapper = objectMapper,
        )
        val mvc = MockMvcBuilders.standaloneSetup(fileController)
            .setMessageConverters(
                StringHttpMessageConverter(),
                MappingJackson2HttpMessageConverter(objectMapper),
            )
            .build()

        fun revisionedRequest() =
            multipart("/api/v1/contexts/$contextId/extract/file")
                .file(MockMultipartFile("file", "mail.txt", "text/plain", "content".toByteArray()))
                .file(MockMultipartFile("sourceId", "", "text/plain", "legacy-source".toByteArray()))
                .file(
                    MockMultipartFile(
                        "sourceLocator",
                        "",
                        "application/json",
                        """{"kind":"connector","value":"message-42","connectorId":"gmail"}""".toByteArray(),
                    )
                )
                .file(
                    MockMultipartFile(
                        "sourceRevision",
                        "",
                        "text/plain;charset=UTF-8",
                        sourceRevision.toByteArray(),
                    )
                )

        repeat(2) {
            mvc.perform(revisionedRequest()).andExpect(status().isOk)
        }

        assertEquals(processedChunkIds[0], processedChunkIds[1])
        verify(exactly = 2) {
            propositionPipeline.process(
                match { chunks -> chunks.all { it.parentId == "legacy-source" } },
                match {
                    it.sourceLocator?.key() == "connector:gmail:message-42" &&
                            it.sourceRevision?.sourceKey == "connector:gmail:message-42" &&
                            it.sourceRevision?.sourceRevision == sourceRevision
                },
            )
        }
    }

    // ========================================================================
    // What the answer names must be what the store holds
    // ========================================================================

    @Test
    fun `POST extract answers with the canonical id a dedup save handed back`() {
        val contextId = "test-context"
        val dedupRepository = DedupOnTextRepository()
        val mvc = mockMvcFor(dedupRepository)

        // Each run of the pipeline mints a fresh proposition for the same sentence, which is what a
        // second POST of the same text produces.
        val mintedIds = mutableListOf<String>()
        every { propositionPipeline.processChunk(any(), any()) } answers {
            val chunk = firstArg<Chunk>()
            val context = secondArg<SourceAnalysisContext>()
            val extracted = Proposition(
                contextId = context.contextId,
                text = "Brahms wrote a requiem",
                mentions = emptyList(),
                confidence = 0.9,
                grounding = listOf(chunk.id),
            )
            mintedIds += extracted.id
            ChunkPropositionResult.Success(
                chunkId = chunk.id,
                suggestedPropositions = SuggestedPropositions(chunkId = chunk.id, propositions = emptyList()),
                entityResolutions = Resolutions(chunkIds = setOf(chunk.id), resolutions = emptyList()),
                propositions = listOf(extracted),
                revisionResults = emptyList(),
            )
        }

        fun extract(): MvcResult = mvc.perform(
            post("/api/v1/contexts/$contextId/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"Brahms wrote a requiem","sourceId":"s1"}""")
        ).andExpect(status().isOk).andReturn()

        val first = extract()
        val second = extract()

        assertNotEquals(mintedIds[0], mintedIds[1], "the pipeline must mint a second id for the retry")
        assertEquals(1, dedupRepository.count(), "the store collapses the retry onto the first row")

        assertEquals(listOf(mintedIds[0]), propositionIdsIn(first))
        // The second answer must name the row the store kept, which is the first id.
        assertEquals(listOf(mintedIds[0]), propositionIdsIn(second))
        assertNull(dedupRepository.findById(mintedIds[1]), "the second id was never stored")
        assertEveryIdResolves(second, dedupRepository)
    }

    @Test
    fun `POST extract answers with the merged row the store holds`() {
        val contextId = "test-context"
        val extracted = Proposition(
            contextId = ContextId(contextId),
            text = "Brahms wrote a requiem",
            mentions = emptyList(),
            confidence = 0.9,
        )
        val original = Proposition(
            id = "canonical-1",
            contextId = ContextId(contextId),
            text = "Brahms wrote a requiem",
            mentions = emptyList(),
            confidence = 0.8,
        )
        val merged = original.copy(confidence = 0.95)
        every { propositionPipeline.processChunk(any(), any()) } returns ChunkPropositionResult.Success(
            chunkId = "chunk-merge",
            suggestedPropositions = SuggestedPropositions(chunkId = "chunk-merge", propositions = emptyList()),
            entityResolutions = Resolutions(chunkIds = setOf("chunk-merge"), resolutions = emptyList()),
            // A merge writes `revised` and leaves `extracted` unwritten, so `extracted.id` names
            // nothing a caller could fetch.
            propositions = listOf(extracted),
            revisionResults = listOf(RevisionResult.Merged(original = original, revised = merged)),
        )

        val result = mockMvc.perform(
            post("/api/v1/contexts/$contextId/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"Brahms wrote a requiem","sourceId":"s1"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.propositions.length()").value(1))
            .andExpect(jsonPath("$.propositions[0].id").value("canonical-1"))
            .andExpect(jsonPath("$.propositions[0].action").value("MERGED"))
            .andExpect(jsonPath("$.propositions[0].confidence").value(0.95))
            .andReturn()

        assertNull(propositionRepository.findById(extracted.id), "the extracted proposition was never stored")
        assertEveryIdResolves(result, propositionRepository)
    }

    @Test
    fun `every proposition id in an extract answer resolves through findById`() {
        val contextId = "test-context"
        val dedupRepository = DedupOnTextRepository()
        val mvc = mockMvcFor(dedupRepository)

        val original = Proposition(
            id = "orig-1",
            contextId = ContextId(contextId),
            text = "Brahms was born in 1830",
            mentions = emptyList(),
            confidence = 0.3,
            status = PropositionStatus.CONTRADICTED,
        )
        val newProp = Proposition(
            contextId = ContextId(contextId),
            text = "Brahms was born in 1833",
            mentions = emptyList(),
            confidence = 0.95,
        )
        val reinforcedOriginal = Proposition(
            id = "orig-2",
            contextId = ContextId(contextId),
            text = "Brahms wrote a requiem",
            mentions = emptyList(),
            confidence = 0.8,
        )
        val reinforced = reinforcedOriginal.copy(confidence = 0.9, reinforceCount = 1)
        val extractedTwin = Proposition(
            contextId = ContextId(contextId),
            text = "Brahms wrote a requiem",
            mentions = emptyList(),
            confidence = 0.9,
        )
        every { propositionPipeline.processChunk(any(), any()) } returns ChunkPropositionResult.Success(
            chunkId = "chunk-mixed",
            suggestedPropositions = SuggestedPropositions(chunkId = "chunk-mixed", propositions = emptyList()),
            entityResolutions = Resolutions(chunkIds = setOf("chunk-mixed"), resolutions = emptyList()),
            propositions = listOf(newProp, extractedTwin),
            revisionResults = listOf(
                RevisionResult.Contradicted(original = original, new = newProp),
                RevisionResult.Reinforced(original = reinforcedOriginal, revised = reinforced),
            ),
        )

        val result = mvc.perform(
            post("/api/v1/contexts/$contextId/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"Brahms","sourceId":"s1"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.propositions.length()").value(2))
            .andReturn()

        assertEquals(listOf(newProp.id, "orig-2"), propositionIdsIn(result))
        assertEveryIdResolves(result, dedupRepository)
    }

    // ========================================================================
    // Connector ids are free text, colons included
    // ========================================================================

    @Test
    fun `POST extract accepts a connector id holding a colon and keys it the way ConnectorRef does`() {
        val contextId = "test-context"
        val capturedContexts = mutableListOf<SourceAnalysisContext>()
        every { propositionPipeline.processChunk(any(), any()) } answers {
            val chunk = firstArg<Chunk>()
            val context = secondArg<SourceAnalysisContext>()
            capturedContexts += context
            ChunkPropositionResult.Success(
                chunkId = chunk.id,
                suggestedPropositions = SuggestedPropositions(chunkId = chunk.id, propositions = emptyList()),
                entityResolutions = Resolutions(chunkIds = setOf(chunk.id), resolutions = emptyList()),
                propositions = listOf(
                    Proposition(
                        contextId = context.contextId,
                        text = "Fact from a colon-bearing connector",
                        mentions = emptyList(),
                        confidence = 0.9,
                        provenanceEntries = listOf(
                            ProvenanceEntry(
                                locator = requireNotNull(context.sourceLocator),
                                chunkId = chunk.id,
                                sourceRevision = context.sourceRevision?.sourceRevision,
                            ),
                        ),
                    ),
                ),
                revisionResults = emptyList(),
            )
        }

        fun extract(connectorId: String, externalId: String) = mockMvc.perform(
            post("/api/v1/contexts/$contextId/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "text": "Fact from a colon-bearing connector",
                        "sourceLocator": {"kind":"connector","value":"$externalId","connectorId":"$connectorId"},
                        "sourceRevision": "r1"
                    }
                    """.trimIndent()
                )
        ).andExpect(status().isOk)

        val regionQualified = ConnectorRef("gmail:eu-west", "message-42")
        val plain = ConnectorRef("gmail", "eu-west:message-42")

        // The domain type's own escaping, and the two readings of the same colons stay apart under it.
        assertEquals("""connector:gmail\:eu-west:message-42""", regionQualified.key())
        assertNotEquals(regionQualified.key(), plain.key())

        extract("gmail:eu-west", "message-42")
            .andExpect(
                jsonPath("$.propositions[0].provenance[0].locator")
                    .value("""connector:gmail\:eu-west:message-42""")
            )
        extract("gmail", "eu-west:message-42")
            .andExpect(
                jsonPath("$.propositions[0].provenance[0].locator")
                    .value("connector:gmail:eu-west:message-42")
            )

        // The connector id survives the wire intact, and the key the request produced is the one
        // ConnectorRef renders for the same pair.
        val fromWire = capturedContexts[0].sourceLocator as ConnectorRef
        assertEquals("gmail:eu-west", fromWire.connectorId)
        assertEquals("message-42", fromWire.externalId)
        assertEquals(regionQualified.key(), fromWire.key())
        assertEquals(regionQualified.key(), capturedContexts[0].sourceRevision?.sourceKey)
        assertEquals(plain.key(), capturedContexts[1].sourceLocator?.key())
        assertNotEquals(capturedContexts[0].sourceLocator?.key(), capturedContexts[1].sourceLocator?.key())

        // And the key the store was handed is the escaped one.
        val stored = propositionRepository.findAll().first { prop ->
            prop.provenanceEntries.any { it.locator == regionQualified }
        }
        assertEquals(regionQualified.key(), stored.provenanceEntries.single().locator.key())
    }

    // ========================================================================
    // Identity length ceilings, answered at the edge
    // ========================================================================

    @Test
    fun `POST extract refuses an over-limit source revision with a 400 naming the bound`() {
        val overLimit = "r".repeat(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH + 1)

        mockMvc.perform(
            post("/api/v1/contexts/test-context/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "text": "fact",
                        "sourceLocator": {"kind":"uri","value":"https://example.com/source"},
                        "sourceRevision": "$overLimit"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("MAX_SOURCE_REVISION_LENGTH")))
            .andExpect(
                jsonPath("$.error")
                    .value(containsString(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH.toString()))
            )

        verify(exactly = 0) { propositionPipeline.processChunk(any(), any()) }
    }

    @Test
    fun `POST extract refuses an over-limit source key with a 400 naming the bound`() {
        // No revision here, so nothing downstream would have checked the key until the first
        // ProvenanceEntry was built, deep inside extraction.
        val overLimitUri = "https://example.com/" + "p".repeat(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH)

        mockMvc.perform(
            post("/api/v1/contexts/test-context/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"fact","sourceLocator":{"kind":"uri","value":"$overLimitUri"}}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("MAX_SOURCE_KEY_LENGTH")))
            .andExpect(
                jsonPath("$.error")
                    .value(containsString(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH.toString()))
            )

        verify(exactly = 0) { propositionPipeline.processChunk(any(), any()) }
    }

    @Test
    fun `POST extract file refuses an over-limit source revision with a 400 naming the bound`() {
        val reader = mockk<HierarchicalContentReader>()
        val chunker = mockk<ContentChunker>()
        val fileController = PropositionPipelineController(
            propositionPipeline = propositionPipeline,
            propositionRepository = propositionRepository,
            entityResolver = entityResolver,
            schemaRegistry = schemaRegistry,
            contentReader = reader,
            contentChunker = chunker,
            objectMapper = objectMapper,
        )
        val mvc = MockMvcBuilders.standaloneSetup(fileController)
            .setMessageConverters(
                StringHttpMessageConverter(),
                MappingJackson2HttpMessageConverter(objectMapper),
            )
            .build()
        val overLimit = "r".repeat(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH + 1)

        mvc.perform(
            multipart("/api/v1/contexts/test-context/extract/file")
                .file(MockMultipartFile("file", "mail.txt", "text/plain", "content".toByteArray()))
                .file(
                    MockMultipartFile(
                        "sourceLocator",
                        "",
                        "application/json",
                        """{"kind":"uri","value":"https://example.com/source"}""".toByteArray(),
                    )
                )
                .file(MockMultipartFile("sourceRevision", "", "text/plain", overLimit.toByteArray()))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("MAX_SOURCE_REVISION_LENGTH")))

        verify(exactly = 0) { reader.parseContent(any(), any()) }
        verify(exactly = 0) { propositionPipeline.process(any(), any()) }
    }

    @Test
    fun `POST extract carries a source key and revision at the limit end to end`() {
        val contextId = "test-context"
        val atLimitRevision = "r".repeat(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH)
        val uriPrefix = "https://example.com/"
        val keyPrefix = "uri:$uriPrefix"
        val atLimitUri = uriPrefix + "p".repeat(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH - keyPrefix.length)

        val capturedContexts = mutableListOf<SourceAnalysisContext>()
        every { propositionPipeline.processChunk(any(), any()) } answers {
            val chunk = firstArg<Chunk>()
            val context = secondArg<SourceAnalysisContext>()
            capturedContexts += context
            ChunkPropositionResult.Success(
                chunkId = chunk.id,
                suggestedPropositions = SuggestedPropositions(chunkId = chunk.id, propositions = emptyList()),
                entityResolutions = Resolutions(chunkIds = setOf(chunk.id), resolutions = emptyList()),
                propositions = listOf(
                    Proposition(
                        contextId = context.contextId,
                        text = "Fact at the ceiling",
                        mentions = emptyList(),
                        confidence = 0.9,
                        // Building this entry runs the same bounds check the store side relies on.
                        provenanceEntries = listOf(
                            ProvenanceEntry(
                                locator = requireNotNull(context.sourceLocator),
                                chunkId = chunk.id,
                                sourceRevision = context.sourceRevision?.sourceRevision,
                            ),
                        ),
                    ),
                ),
                revisionResults = emptyList(),
            )
        }

        val result = mockMvc.perform(
            post("/api/v1/contexts/$contextId/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "text": "Fact at the ceiling",
                        "sourceLocator": {"kind":"uri","value":"$atLimitUri"},
                        "sourceRevision": "$atLimitRevision"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.propositions[0].provenance[0].sourceRevision").value(atLimitRevision))
            .andReturn()

        val revision = requireNotNull(capturedContexts.single().sourceRevision)
        assertEquals(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH, revision.sourceKey.length)
        assertEquals(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH, revision.sourceRevision.length)

        val stored = propositionRepository.findAll().single()
        assertEquals(atLimitRevision, stored.provenanceEntries.single().sourceRevision)
        assertEveryIdResolves(result, propositionRepository)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun mockMvcFor(repository: PropositionRepository): MockMvc =
        MockMvcBuilders.standaloneSetup(
            PropositionPipelineController(
                propositionPipeline = propositionPipeline,
                propositionRepository = repository,
                entityResolver = entityResolver,
                schemaRegistry = schemaRegistry,
                objectMapper = objectMapper,
            )
        )
            .setMessageConverters(
                StringHttpMessageConverter(),
                MappingJackson2HttpMessageConverter(objectMapper),
            )
            .build()

    private fun propositionIdsIn(result: MvcResult): List<String> =
        objectMapper.readTree(result.response.contentAsString)
            .path("propositions")
            .map { it.path("id").asText() }

    private fun assertEveryIdResolves(result: MvcResult, repository: PropositionRepository) {
        val ids = propositionIdsIn(result)
        assertTrue(ids.isNotEmpty(), "the answer must name at least one proposition")
        ids.forEach { id ->
            assertNotNull(repository.findById(id), "id $id in the answer is absent from the store")
        }
    }
}

/**
 * A store that collapses a second save of the same text in the same context onto the row already
 * there, the way the graph store's exact-text dedup does. `save` answers with the row a caller will
 * find later, under whatever id that row already carries.
 */
private class DedupOnTextRepository(
    private val delegate: TestPropositionRepository = TestPropositionRepository(),
) : PropositionRepository by delegate {

    override fun save(proposition: Proposition): Proposition {
        val existing = delegate.findAll().firstOrNull { stored ->
            stored.id != proposition.id &&
                    stored.contextId == proposition.contextId &&
                    stored.text == proposition.text
        }
        return delegate.save(existing ?: proposition)
    }
}
