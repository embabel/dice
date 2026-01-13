package com.embabel.dice.web.rest

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * Tests for PropositionPipelineController.
 */
class PropositionPipelineControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var propositionRepository: TestPropositionRepository
    private lateinit var propositionPipeline: PropositionPipeline
    private lateinit var entityResolver: EntityResolver
    private lateinit var schema: DataDictionary
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
        schema = DataDictionary.fromClasses(Composer::class.java, Work::class.java)

        objectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())

        val controller = PropositionPipelineController(
            propositionPipeline = propositionPipeline,
            propositionRepository = propositionRepository,
            entityResolver = entityResolver,
            schema = schema,
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

        val mockResult = ChunkPropositionResult(
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
                    NewEntity(SuggestedEntity(
                        labels = listOf("Composer"),
                        name = "Brahms",
                        summary = "A composer",
                        chunkId = "chunk-123",
                    ))
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

        val mockResult = ChunkPropositionResult(
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
}
