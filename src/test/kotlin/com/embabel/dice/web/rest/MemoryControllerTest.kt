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
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory proposition repository for testing (no embedding required).
 */
class TestPropositionRepository : PropositionRepository {
    private val propositions = ConcurrentHashMap<String, Proposition>()

    override val luceneSyntaxNotes: String get() = "test"

    override fun save(proposition: Proposition): Proposition {
        propositions[proposition.id] = proposition
        return proposition
    }

    override fun findById(id: String): Proposition? = propositions[id]

    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> =
        propositions.values.filter { prop ->
            prop.mentions.any { it.resolvedId == entityIdentifier.id }
        }

    override fun findSimilarWithScores(request: TextSimilaritySearchRequest): List<SimilarityResult<Proposition>> =
        emptyList()

    override fun findByStatus(status: PropositionStatus): List<Proposition> =
        propositions.values.filter { it.status == status }

    override fun findByGrounding(chunkId: String): List<Proposition> =
        propositions.values.filter { chunkId in it.grounding }

    override fun findByMinLevel(minLevel: Int): List<Proposition> =
        propositions.values.filter { it.level >= minLevel }

    override fun findByContextId(contextId: ContextId): List<Proposition> =
        propositions.values.filter { it.contextId == contextId }

    override fun findAll(): List<Proposition> = propositions.values.toList()

    override fun delete(id: String): Boolean = propositions.remove(id) != null

    override fun count(): Int = propositions.size

    fun clear() = propositions.clear()
}

/**
 * Tests for MemoryController.
 */
class MemoryControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var propositionRepository: TestPropositionRepository
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        propositionRepository = TestPropositionRepository()

        objectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())

        val controller = MemoryController(
            propositionRepository = propositionRepository,
        )

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Nested
    inner class MemoryRetrievalTests {

        @Test
        fun `GET memory returns all propositions for context`() {
            val contextId = "test-context"

            val prop1 = Proposition(
                contextId = ContextId(contextId),
                text = "User loves Brahms",
                mentions = listOf(EntityMention("Brahms", "Composer", "composer-brahms", MentionRole.OBJECT)),
                confidence = 0.95,
            )
            val prop2 = Proposition(
                contextId = ContextId(contextId),
                text = "User loves Wagner",
                mentions = listOf(EntityMention("Wagner", "Composer", "composer-wagner", MentionRole.OBJECT)),
                confidence = 0.90,
            )
            propositionRepository.save(prop1)
            propositionRepository.save(prop2)

            mockMvc.perform(get("/api/v1/contexts/$contextId/memory"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.contextId").value(contextId))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.propositions").isArray)
                .andExpect(jsonPath("$.propositions.length()").value(2))
        }

        @Test
        fun `GET memory filters by status`() {
            val contextId = "test-context"

            val activeProp = Proposition(
                contextId = ContextId(contextId),
                text = "Active proposition",
                mentions = emptyList(),
                confidence = 0.9,
                status = PropositionStatus.ACTIVE,
            )
            val supersededProp = Proposition(
                contextId = ContextId(contextId),
                text = "Superseded proposition",
                mentions = emptyList(),
                confidence = 0.8,
                status = PropositionStatus.SUPERSEDED,
            )
            propositionRepository.save(activeProp)
            propositionRepository.save(supersededProp)

            mockMvc.perform(get("/api/v1/contexts/$contextId/memory?status=ACTIVE"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.propositions[0].status").value("ACTIVE"))
        }

        @Test
        fun `GET memory filters by minConfidence`() {
            val contextId = "test-context"

            val highConf = Proposition(
                contextId = ContextId(contextId),
                text = "High confidence",
                mentions = emptyList(),
                confidence = 0.95,
            )
            val lowConf = Proposition(
                contextId = ContextId(contextId),
                text = "Low confidence",
                mentions = emptyList(),
                confidence = 0.5,
            )
            propositionRepository.save(highConf)
            propositionRepository.save(lowConf)

            mockMvc.perform(get("/api/v1/contexts/$contextId/memory?minConfidence=0.8"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.propositions[0].confidence").value(0.95))
        }
    }

    @Nested
    inner class CreatePropositionTests {

        @Test
        fun `POST memory creates proposition directly`() {
            val contextId = "test-context"
            val requestBody = """
                {
                    "text": "User is an expert in Wagner",
                    "mentions": [
                        {"name": "User", "type": "User", "role": "SUBJECT"},
                        {"name": "Wagner", "type": "Composer", "role": "OBJECT"}
                    ],
                    "confidence": 0.9,
                    "reasoning": "Manually added"
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/v1/contexts/$contextId/memory")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.contextId").value(contextId))
                .andExpect(jsonPath("$.text").value("User is an expert in Wagner"))
                .andExpect(jsonPath("$.confidence").value(0.9))
                .andExpect(jsonPath("$.action").value("CREATED"))
                .andExpect(jsonPath("$.mentions.length()").value(2))
        }
    }

    @Nested
    inner class GetPropositionTests {

        @Test
        fun `GET memory by id returns proposition`() {
            val contextId = "test-context"
            val prop = Proposition(
                contextId = ContextId(contextId),
                text = "Test proposition",
                mentions = emptyList(),
                confidence = 0.9,
            )
            propositionRepository.save(prop)

            mockMvc.perform(get("/api/v1/contexts/$contextId/memory/${prop.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(prop.id))
                .andExpect(jsonPath("$.text").value("Test proposition"))
        }

        @Test
        fun `GET memory by id returns 404 for wrong context`() {
            val prop = Proposition(
                contextId = ContextId("other-context"),
                text = "Test proposition",
                mentions = emptyList(),
                confidence = 0.9,
            )
            propositionRepository.save(prop)

            mockMvc.perform(get("/api/v1/contexts/test-context/memory/${prop.id}"))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `GET memory by id returns 404 for non-existent proposition`() {
            mockMvc.perform(get("/api/v1/contexts/test-context/memory/non-existent"))
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    inner class DeletePropositionTests {

        @Test
        fun `DELETE memory removes proposition`() {
            val contextId = "test-context"
            val prop = Proposition(
                contextId = ContextId(contextId),
                text = "To be deleted",
                mentions = emptyList(),
                confidence = 0.9,
                status = PropositionStatus.ACTIVE,
            )
            propositionRepository.save(prop)

            mockMvc.perform(delete("/api/v1/contexts/$contextId/memory/${prop.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(prop.id))
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))

            // Verify deleted
            assert(propositionRepository.findById(prop.id) == null)
        }

        @Test
        fun `DELETE memory returns 404 for non-existent proposition`() {
            mockMvc.perform(delete("/api/v1/contexts/test-context/memory/non-existent"))
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    inner class EntityMemoryTests {

        @Test
        fun `GET memory by entity returns matching propositions`() {
            val contextId = "test-context"

            val prop1 = Proposition(
                contextId = ContextId(contextId),
                text = "User loves Brahms",
                mentions = listOf(EntityMention("Brahms", "Composer", "composer-brahms", MentionRole.OBJECT)),
                confidence = 0.95,
            )
            val prop2 = Proposition(
                contextId = ContextId(contextId),
                text = "User loves Wagner",
                mentions = listOf(EntityMention("Wagner", "Composer", "composer-wagner", MentionRole.OBJECT)),
                confidence = 0.90,
            )
            propositionRepository.save(prop1)
            propositionRepository.save(prop2)

            mockMvc.perform(get("/api/v1/contexts/$contextId/memory/entity/Composer/composer-brahms"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.entity.id").value("composer-brahms"))
                .andExpect(jsonPath("$.entity.type").value("Composer"))
                .andExpect(jsonPath("$.propositions.length()").value(1))
                .andExpect(jsonPath("$.propositions[0].text").value("User loves Brahms"))
        }
    }
}
