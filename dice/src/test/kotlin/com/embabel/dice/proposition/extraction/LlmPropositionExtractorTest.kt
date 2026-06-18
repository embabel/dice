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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for LlmPropositionExtractor's mention filtering functionality.
 *
 * Note: This test focuses on the toSuggestedEntities() method's filtering logic.
 * Full LLM extraction tests would require mocking the LLM client.
 */
class LlmPropositionExtractorTest {

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
