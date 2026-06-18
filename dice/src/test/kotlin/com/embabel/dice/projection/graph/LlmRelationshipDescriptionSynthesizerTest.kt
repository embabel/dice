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
package com.embabel.dice.projection.graph

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LlmRelationshipDescriptionSynthesizerTest {

    private val contextId = ContextId("test")

    private fun proposition(
        id: String = "prop-1",
        text: String = "Rod responds to emails from Tom",
        confidence: Double = 0.8,
    ): Proposition = Proposition(
        id = id,
        contextId = contextId,
        text = text,
        mentions = listOf(
            EntityMention(
                span = "Rod",
                type = "BlackmawUser",
                resolvedId = "user-rod",
                role = MentionRole.SUBJECT,
            ),
            EntityMention(
                span = "Tom",
                type = "Contact",
                resolvedId = "contact-tom",
                role = MentionRole.OBJECT,
            ),
        ),
        confidence = confidence,
    )

    private fun mockAi(response: SynthesisResponse): Ai {
        val mockAi = mockk<Ai>()
        val mockPromptRunner = mockk<PromptRunner>()
        val mockCreating = mockk<PromptRunner.Creating<SynthesisResponse>>()

        every { mockAi.withLlm(any<LlmOptions>()) } returns mockPromptRunner
        every { mockPromptRunner.withId(any()) } returns mockPromptRunner
        every { mockPromptRunner.creating(SynthesisResponse::class.java) } returns mockCreating
        every { mockCreating.fromTemplate(any(), any()) } returns response

        return mockAi
    }

    private fun mockAiCapturing(
        response: SynthesisResponse,
        modelSlot: CapturingSlot<Map<String, Any>>,
    ): Ai {
        val mockAi = mockk<Ai>()
        val mockPromptRunner = mockk<PromptRunner>()
        val mockCreating = mockk<PromptRunner.Creating<SynthesisResponse>>()

        every { mockAi.withLlm(any<LlmOptions>()) } returns mockPromptRunner
        every { mockPromptRunner.withId(any()) } returns mockPromptRunner
        every { mockPromptRunner.creating(SynthesisResponse::class.java) } returns mockCreating
        every { mockCreating.fromTemplate(any(), capture(modelSlot)) } returns response

        return mockAi
    }

    @Nested
    inner class BuilderTests {

        @Test
        fun `builder creates synthesizer`() {
            val llmOptions = LlmOptions()
            val ai = mockAi(SynthesisResponse("school friend", 0.9, listOf(0)))
            val synthesizer = LlmRelationshipDescriptionSynthesizer.withLlm(llmOptions).withAi(ai)
            assertNotNull(synthesizer)
        }
    }

    @Nested
    inner class SynthesizeTests {

        @Test
        fun `returns existing description when no propositions`() {
            val ai = mockAi(SynthesisResponse("unused", 0.5, emptyList()))
            val synthesizer = LlmRelationshipDescriptionSynthesizer(LlmOptions(), ai)

            val result = synthesizer.synthesize(
                SynthesisRequest(
                    sourceEntityId = "user-rod",
                    sourceEntityName = "Rod",
                    targetEntityId = "contact-tom",
                    targetEntityName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = emptyList(),
                    existingDescription = "old friend",
                )
            )

            assertEquals("old friend", result.description)
            assertEquals(0.0, result.confidence)
            assertTrue(result.sourcePropositionIds.isEmpty())
        }

        @Test
        fun `returns empty string when no propositions and no existing description`() {
            val ai = mockAi(SynthesisResponse("unused", 0.5, emptyList()))
            val synthesizer = LlmRelationshipDescriptionSynthesizer(LlmOptions(), ai)

            val result = synthesizer.synthesize(
                SynthesisRequest(
                    sourceEntityId = "user-rod",
                    sourceEntityName = "Rod",
                    targetEntityId = "contact-tom",
                    targetEntityName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = emptyList(),
                )
            )

            assertEquals("", result.description)
            assertEquals(0.0, result.confidence)
        }

        @Test
        fun `calls LLM and returns synthesized description`() {
            val ai = mockAi(SynthesisResponse("school friend", 0.85, listOf(0)))
            val synthesizer = LlmRelationshipDescriptionSynthesizer(LlmOptions(), ai)

            val result = synthesizer.synthesize(
                SynthesisRequest(
                    sourceEntityId = "user-rod",
                    sourceEntityName = "Rod",
                    targetEntityId = "contact-tom",
                    targetEntityName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                )
            )

            assertEquals("school friend", result.description)
            assertEquals(0.85, result.confidence)
            assertEquals(listOf("prop-1"), result.sourcePropositionIds)
        }

        @Test
        fun `maps source indices to proposition IDs`() {
            val props = listOf(
                proposition(id = "p-a", text = "Rod emails Tom regularly"),
                proposition(id = "p-b", text = "Rod and Tom went to school together"),
                proposition(id = "p-c", text = "Tom invited Rod to a party"),
            )
            val ai = mockAi(SynthesisResponse("school friend who emails regularly", 0.9, listOf(0, 1)))
            val synthesizer = LlmRelationshipDescriptionSynthesizer(LlmOptions(), ai)

            val result = synthesizer.synthesize(
                SynthesisRequest(
                    sourceEntityId = "user-rod",
                    sourceEntityName = "Rod",
                    targetEntityId = "contact-tom",
                    targetEntityName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = props,
                )
            )

            assertEquals(listOf("p-a", "p-b"), result.sourcePropositionIds)
        }

        @Test
        fun `filters out-of-range source indices`() {
            val ai = mockAi(SynthesisResponse("colleague", 0.8, listOf(0, 5, 99)))
            val synthesizer = LlmRelationshipDescriptionSynthesizer(LlmOptions(), ai)

            val result = synthesizer.synthesize(
                SynthesisRequest(
                    sourceEntityId = "user-rod",
                    sourceEntityName = "Rod",
                    targetEntityId = "contact-tom",
                    targetEntityName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                )
            )

            assertEquals(listOf("prop-1"), result.sourcePropositionIds)
        }

        @Test
        fun `clamps confidence to valid range`() {
            val ai = mockAi(SynthesisResponse("colleague", 1.5, listOf(0)))
            val synthesizer = LlmRelationshipDescriptionSynthesizer(LlmOptions(), ai)

            val result = synthesizer.synthesize(
                SynthesisRequest(
                    sourceEntityId = "user-rod",
                    sourceEntityName = "Rod",
                    targetEntityId = "contact-tom",
                    targetEntityName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                )
            )

            assertEquals(1.0, result.confidence)
        }

        @Test
        fun `passes template parameters correctly`() {
            val modelSlot = slot<Map<String, Any>>()
            val ai = mockAiCapturing(
                SynthesisResponse("friend", 0.8, listOf(0)),
                modelSlot,
            )
            val synthesizer = LlmRelationshipDescriptionSynthesizer(LlmOptions(), ai)

            synthesizer.synthesize(
                SynthesisRequest(
                    sourceEntityId = "user-rod",
                    sourceEntityName = "Rod",
                    targetEntityId = "contact-tom",
                    targetEntityName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                    existingDescription = "old pal",
                )
            )

            val params = modelSlot.captured
            assertEquals("Rod", params["sourceEntityName"])
            assertEquals("Tom", params["targetEntityName"])
            assertEquals("KNOWS", params["relationshipType"])
            assertEquals("old pal", params["existingDescription"])

            @Suppress("UNCHECKED_CAST")
            val propData = params["propositions"] as List<Map<String, Any>>
            assertEquals(1, propData.size)
            assertEquals(0, propData[0]["index"])
            assertEquals("Rod responds to emails from Tom", propData[0]["text"])
        }

        @Test
        fun `omits existingDescription from template when null`() {
            val modelSlot = slot<Map<String, Any>>()
            val ai = mockAiCapturing(
                SynthesisResponse("friend", 0.8, listOf(0)),
                modelSlot,
            )
            val synthesizer = LlmRelationshipDescriptionSynthesizer(LlmOptions(), ai)

            synthesizer.synthesize(
                SynthesisRequest(
                    sourceEntityId = "user-rod",
                    sourceEntityName = "Rod",
                    targetEntityId = "contact-tom",
                    targetEntityName = "Tom",
                    relationshipType = "KNOWS",
                    propositions = listOf(proposition()),
                    existingDescription = null,
                )
            )

            assertFalse(modelSlot.captured.containsKey("existingDescription"))
        }
    }
}
