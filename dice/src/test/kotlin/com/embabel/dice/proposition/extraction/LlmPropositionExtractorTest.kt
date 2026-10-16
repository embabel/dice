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
package com.embabel.dice.proposition.extraction

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.CreationExample
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValidatedPropertyDefinition
import com.embabel.dice.common.filter.CompositeMentionFilter
import com.embabel.dice.common.filter.PropositionDuplicateFilter
import com.embabel.dice.common.filter.SchemaValidatedMentionFilter
import com.embabel.dice.common.validation.LengthConstraint
import com.embabel.dice.common.validation.NoVagueReferences
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for extraction span consistency and optional mention filters.
 */
class LlmPropositionExtractorTest {

    private fun extract(
        response: PropositionsResult,
        sourceText: String = "SourceOnly appears in the original chunk",
    ): SuggestedPropositions {
        val ai = mockk<Ai>()
        val runner = mockk<PromptRunner>()
        val creating = mockk<PromptRunner.Creating<PropositionsResult>>()
        val chunk = mockk<Chunk>()
        val context = mockk<SourceAnalysisContext>()
        every { chunk.id } returns "mention-chunk"
        every { chunk.text } returns sourceText
        every { context.perspective } returns null
        every { context.promptVariables } returns emptyMap()
        every { ai.withLlm(any<LlmOptions>()) } returns runner
        every { runner.withId("extract-propositions") } returns runner
        every { runner.creating(PropositionsResult::class.java) } returns creating
        every { creating.withExamples(any<List<CreationExample<PropositionsResult>>>()) } returns creating
        every { creating.fromTemplate(any(), any()) } returns response

        return LlmPropositionExtractor(LlmOptions(), ai).extract(chunk, context)
    }

    @Test
    fun `extract drops blank and absent spans while preserving fields metadata order and duplicates`() {
        val alice = SuggestedMention("Alice", "Person", "person-alice", "SUBJECT")
        val company = SuggestedMention("Acme", "Company", "company-acme", "OBJECT")
        val originalMentions = listOf(
            SuggestedMention("", "Person"),
            alice,
            SuggestedMention("   ", "Person"),
            SuggestedMention("Absent", "Person"),
            company,
            alice,
        )
        val proposition = SuggestedProposition(
            text = "Alice works at Acme",
            mentions = originalMentions,
            confidence = 0.83,
            decay = 0.17,
            importance = 0.72,
            reasoning = "Employment statement",
            grounding = listOf("evidence-1", "evidence-2"),
            source = "custom-source",
        )
        val response = PropositionsResult(listOf(proposition))

        val result = extract(response)

        assertEquals("mention-chunk", result.chunkId)
        assertEquals(listOf(proposition.copy(mentions = listOf(alice, company, alice))), result.propositions)
        assertSame(alice, result.propositions.single().mentions.first())
        assertSame(company, result.propositions.single().mentions[1])
        assertSame(alice, result.propositions.single().mentions.last())
        assertEquals(listOf(proposition), response.propositions)
        assertSame(originalMentions, response.propositions.single().mentions)
        assertEquals(6, originalMentions.size)
    }

    @Test
    fun `extract matches literal case insensitive Unicode and partial word spans without trimming`() {
        val mentions = listOf(
            SuggestedMention("Alice", "Person"),
            SuggestedMention("aLiCe", "Person"),
            SuggestedMention("ÉLODIE", "Person"),
            SuggestedMention("[x].*", "Token"),
            SuggestedMention("art", "Token"),
            SuggestedMention("a.b", "Token"),
            SuggestedMention(" Alice ", "Person"),
        )
        val proposition = SuggestedProposition(
            text = "Alice meets Élodie beside [x].* in a cart with axb",
            mentions = mentions,
            confidence = 0.9,
        )

        val result = extract(PropositionsResult(listOf(proposition)))

        assertEquals(listOf(proposition.copy(mentions = mentions.take(5))), result.propositions)
    }

    @Test
    fun `extract checks each proposition rather than chunk text or other propositions`() {
        val propositionOnly = SuggestedMention("PropositionOnly", "Person")
        val sourceOnly = SuggestedMention("SourceOnly", "Person")
        val first = SuggestedProposition(
            text = "PropositionOnly works here",
            mentions = listOf(sourceOnly, propositionOnly),
            confidence = 0.8,
        )
        val second = SuggestedProposition(
            text = "An unrelated statement",
            mentions = listOf(propositionOnly, sourceOnly),
            confidence = 0.7,
        )

        val result = extract(PropositionsResult(listOf(first, second)))

        assertEquals(listOf(first.copy(mentions = listOf(propositionOnly)), second.copy(mentions = emptyList())), result.propositions)
    }

    @Test
    fun `extract retains all invalid no mention empty and whitespace propositions`() {
        val invalidMentions = listOf(
            SuggestedMention("", "Person"),
            SuggestedMention(" \t\n", "Person"),
            SuggestedMention("Absent", "Person"),
        )
        val propositions = listOf(
            SuggestedProposition("An entity-free fact", invalidMentions, 0.8),
            SuggestedProposition("No mentions supplied", confidence = 0.7),
            SuggestedProposition("", invalidMentions, 0.6),
            SuggestedProposition(" \t\n", invalidMentions, 0.5),
        )
        val response = PropositionsResult(propositions)

        val result = extract(response)

        assertEquals(propositions.map { it.copy(mentions = emptyList()) }, result.propositions)
        assertEquals(propositions, response.propositions)
        assertEquals(invalidMentions, response.propositions.first().mentions)
    }

    @Test
    fun `extract retains empty model output and chunk id`() {
        val result = extract(PropositionsResult(emptyList()))

        assertEquals("mention-chunk", result.chunkId)
        assertTrue(result.propositions.isEmpty())
    }

    // Test schema with validation rules
    private val companyType = DynamicType(
        name = "Company",
        ownProperties = listOf(
            ValidatedPropertyDefinition(
                name = "name",
                validationRules = listOf(
                    NoVagueReferences(),
                    LengthConstraint(maxLength = 50)
                )
            )
        )
    )

    private val dataDictionary = DataDictionary.fromDomainTypes(
        name = "TestSchema",
        domainTypes = listOf(companyType)
    )

    @Test
    fun `schema validation filters vague mentions when filter provided`() {
        val suggestedPropositions = SuggestedPropositions(
            chunkId = "test-chunk",
            propositions = listOf(
                SuggestedProposition(
                    text = "This company announced a new product",
                    mentions = listOf(
                        SuggestedMention("this company", "Company"),
                        SuggestedMention("OpenAI", "Company")
                    ),
                    confidence = 0.9
                )
            )
        )

        val filter = SchemaValidatedMentionFilter(dataDictionary)

        val validMentions = suggestedPropositions.propositions
            .flatMap { it.mentions }
            .filter { mention -> filter.isValid(mention, suggestedPropositions.propositions.first().text) }

        // Only "OpenAI" should pass the filter (NoVagueReferences rejects "this company")
        assertEquals(1, validMentions.size)
        assertEquals("OpenAI", validMentions.first().span)
    }

    @Test
    fun `schema validation filters long mentions when filter provided`() {
        val suggestedPropositions = SuggestedPropositions(
            chunkId = "test-chunk",
            propositions = listOf(
                SuggestedProposition(
                    text = "The company made an announcement",
                    mentions = listOf(
                        SuggestedMention("OpenAI", "Company"),
                        SuggestedMention("This is a very long mention that exceeds the maximum allowed length for entity mentions", "Company")
                    ),
                    confidence = 0.9
                )
            )
        )

        val filter = SchemaValidatedMentionFilter(dataDictionary)

        val validMentions = suggestedPropositions.propositions
            .flatMap { it.mentions }
            .filter { mention -> filter.isValid(mention, suggestedPropositions.propositions.first().text) }

        // Only "OpenAI" should pass the filter (LengthConstraint rejects long mention)
        assertEquals(1, validMentions.size)
        assertEquals("OpenAI", validMentions.first().span)
    }

    @Test
    fun `composite filter applies schema validation and context-aware filters`() {
        val suggestedPropositions = SuggestedPropositions(
            chunkId = "test-chunk",
            propositions = listOf(
                SuggestedProposition(
                    text = "Multiple companies were mentioned in this detailed analysis",
                    mentions = listOf(
                        SuggestedMention("this company", "Company"),  // Fails vague reference
                        SuggestedMention("OpenAI", "Company"),  // Passes both
                        SuggestedMention("A very long company name that exceeds the maximum allowed length", "Company")  // Fails length
                    ),
                    confidence = 0.9
                )
            )
        )

        val filter = CompositeMentionFilter(listOf(
            SchemaValidatedMentionFilter(dataDictionary),
            PropositionDuplicateFilter(minPropositionLength = 30)
        ))

        val validMentions = suggestedPropositions.propositions
            .flatMap { it.mentions }
            .filter { mention -> filter.isValid(mention, suggestedPropositions.propositions.first().text) }

        // Only "OpenAI" should pass both filters
        assertEquals(1, validMentions.size)
        assertEquals("OpenAI", validMentions.first().span)
    }

    @Test
    fun `deduplication preserves unique mentions with same span and type`() {
        val suggestedPropositions = SuggestedPropositions(
            chunkId = "test-chunk",
            propositions = listOf(
                SuggestedProposition(
                    text = "OpenAI announced a new model",
                    mentions = listOf(
                        SuggestedMention("OpenAI", "Company"),
                        SuggestedMention("OpenAI", "Company")  // Duplicate
                    ),
                    confidence = 0.9
                ),
                SuggestedProposition(
                    text = "OpenAI is based in San Francisco",
                    mentions = listOf(
                        SuggestedMention("OpenAI", "Company"),  // Duplicate across propositions
                        SuggestedMention("San Francisco", "Location")
                    ),
                    confidence = 0.9
                )
            )
        )

        // Without filter, deduplication should still work
        val allMentions = suggestedPropositions.propositions.flatMap { it.mentions }
        val uniqueSpanTypePairs = allMentions.map { it.span to it.type }.toSet()

        // Should have 2 unique mentions: (OpenAI, Company) and (San Francisco, Location)
        assertEquals(2, uniqueSpanTypePairs.size)
        assertTrue(uniqueSpanTypePairs.contains("OpenAI" to "Company"))
        assertTrue(uniqueSpanTypePairs.contains("San Francisco" to "Location"))
    }

    @Test
    fun `schema filter allows mentions for unknown types`() {
        val suggestedPropositions = SuggestedPropositions(
            chunkId = "test-chunk",
            propositions = listOf(
                SuggestedProposition(
                    text = "John works at OpenAI",
                    mentions = listOf(
                        SuggestedMention("John", "Person"),  // Person type not in schema
                        SuggestedMention("OpenAI", "Company")
                    ),
                    confidence = 0.9
                )
            )
        )

        val filter = SchemaValidatedMentionFilter(dataDictionary)

        val validMentions = suggestedPropositions.propositions
            .flatMap { it.mentions }
            .filter { mention -> filter.isValid(mention, suggestedPropositions.propositions.first().text) }

        // Both should pass - Person type has no schema, so it's allowed by default
        assertEquals(2, validMentions.size)
    }

    @Test
    fun `PropositionDuplicateFilter detects LLM field mapping errors`() {
        val propositionText = "Apple Inc announced Q4 earnings exceeded all expectations"
        val suggestedPropositions = SuggestedPropositions(
            chunkId = "test-chunk",
            propositions = listOf(
                SuggestedProposition(
                    text = propositionText,
                    mentions = listOf(
                        SuggestedMention(propositionText, "Company"),  // LLM error: copied entire proposition
                        SuggestedMention("Apple Inc", "Company")  // Correct extraction
                    ),
                    confidence = 0.9
                )
            )
        )

        val filter = PropositionDuplicateFilter(minPropositionLength = 30)

        val validMentions = suggestedPropositions.propositions
            .flatMap { it.mentions }
            .filter { mention -> filter.isValid(mention, propositionText) }

        // Only "Apple Inc" should pass
        assertEquals(1, validMentions.size)
        assertEquals("Apple Inc", validMentions.first().span)
    }
}
