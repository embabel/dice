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
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.common.support.InMemorySchemaRegistry
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.proposition.revision.TestPropositionRepository
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The extract endpoint's revision summary counts only merges and reinforcements that changed a
 * stored proposition, the same way the pipeline's own statistics do.
 */
class ReplaySummaryControllerTest {

    @JsonClassDescription("A composer of music")
    data class Composer(val id: String, val name: String)

    @Test
    fun `a replay is not counted as a merge in the revision summary`() {
        val pipeline = mockk<PropositionPipeline>()
        val stored = Proposition(contextId = ContextId("ctx"), text = "User loves Brahms", mentions = emptyList(), confidence = 0.9)
        every { pipeline.processChunk(any(), any()) } returns ChunkPropositionResult.Success(
            chunkId = "chunk-1",
            suggestedPropositions = SuggestedPropositions("chunk-1", emptyList()),
            entityResolutions = Resolutions(setOf("chunk-1"), emptyList()),
            propositions = listOf(stored),
            revisionResults = listOf(RevisionResult.Merged(stored, stored)),
        )
        val controller = PropositionPipelineController(
            propositionPipeline = pipeline,
            propositionRepository = TestPropositionRepository(),
            entityResolver = AlwaysCreateEntityResolver,
            schemaRegistry = InMemorySchemaRegistry(DataDictionary.fromClasses("test", Composer::class.java)),
        )
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build()).registerModule(JavaTimeModule())
        val mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(StringHttpMessageConverter(), MappingJackson2HttpMessageConverter(objectMapper))
            .build()

        mockMvc.perform(
            post("/api/v1/contexts/ctx/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text": "I love Brahms", "sourceId": "conversation-1"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision.merged").value(0))
    }
}
