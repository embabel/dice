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
package com.embabel.dice.common.filter

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValidatedPropertyDefinition
import com.embabel.dice.common.validation.LengthConstraint
import com.embabel.dice.common.validation.MinWordCount
import com.embabel.dice.common.validation.NoVagueReferences
import com.embabel.dice.common.validation.NotBlank
import com.embabel.dice.proposition.SuggestedMention
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SchemaValidatedMentionFilterTest {

    @Test
    fun `filter validates mentions using type-safe schema validation rules`() {
        val companyType = DynamicType(
            name = "Company",
            description = "A business organization",
            ownProperties = listOf(
                ValidatedPropertyDefinition(
                    name = "name",
                    validationRules = listOf(
                        NotBlank,
                        NoVagueReferences(),
                        LengthConstraint(minLength = 2, maxLength = 100)
                    )
                )
            ),
            parents = emptyList(),
            creationPermitted = true
        )

        val schema = DataDictionary.fromDomainTypes("test", listOf(companyType))
        val filter = SchemaValidatedMentionFilter(schema)

        // Valid mention
        val validMention = SuggestedMention("OpenAI", "Company")
        assertTrue(filter.isValid(validMention, ""))
        assertNull(filter.rejectionReason(validMention))

        // Invalid: vague reference
        val vagueMention = SuggestedMention("this company", "Company")
        assertFalse(filter.isValid(vagueMention, ""))
        assertNotNull(filter.rejectionReason(vagueMention))

        // Invalid: too long
        val longMention = SuggestedMention("A".repeat(150), "Company")
        assertFalse(filter.isValid(longMention, ""))
        assertNotNull(filter.rejectionReason(longMention))

        // Invalid: blank
        val blankMention = SuggestedMention("   ", "Company")
        assertFalse(filter.isValid(blankMention, ""))
    }

    @Test
    fun `filter allows mentions when no schema is found`() {
        val schema = DataDictionary.fromDomainTypes("test", emptyList())
        val filter = SchemaValidatedMentionFilter(schema)

        val mention = SuggestedMention("anything", "UnknownType")
        assertTrue(filter.isValid(mention, ""))
    }

    @Test
    fun `filter allows mentions when property is not ValidatedPropertyDefinition`() {
        // Regular ValuePropertyDefinition without validation rules
        val companyType = DynamicType(
            name = "Company",
            description = "A business organization",
            ownProperties = listOf(
                com.embabel.agent.core.ValuePropertyDefinition(
                    name = "name"
                    // No validation rules - just a regular property
                )
            ),
            parents = emptyList(),
            creationPermitted = true
        )

        val schema = DataDictionary.fromDomainTypes("test", listOf(companyType))
        val filter = SchemaValidatedMentionFilter(schema)

        val mention = SuggestedMention("anything", "Company")
        assertTrue(filter.isValid(mention, ""))
    }

    @Test
    fun `filter supports multiple type-safe validation rules`() {
        val personType = DynamicType(
            name = "Person",
            description = "A person",
            ownProperties = listOf(
                ValidatedPropertyDefinition(
                    name = "name",
                    validationRules = listOf(
                        NotBlank,
                        MinWordCount(2),
                        LengthConstraint(minLength = 2, maxLength = 80)
                    )
                )
            ),
            parents = emptyList(),
            creationPermitted = true
        )

        val schema = DataDictionary.fromDomainTypes("test", listOf(personType))
        val filter = SchemaValidatedMentionFilter(schema)

        // Valid: two words, appropriate length
        assertTrue(filter.isValid(SuggestedMention("John Doe", "Person"), ""))

        // Invalid: only one word
        assertFalse(filter.isValid(SuggestedMention("John", "Person"), ""))

        // Invalid: too long
        assertFalse(filter.isValid(SuggestedMention("A".repeat(100), "Person"), ""))
    }

    @Test
    fun `filter matches entity types case-insensitively`() {
        val companyType = DynamicType(
            name = "Company",
            description = "A business organization",
            ownProperties = listOf(
                ValidatedPropertyDefinition(
                    name = "name",
                    validationRules = listOf(NotBlank)
                )
            ),
            parents = emptyList(),
            creationPermitted = true
        )

        val schema = DataDictionary.fromDomainTypes("test", listOf(companyType))
        val filter = SchemaValidatedMentionFilter(schema)

        // Should match case-insensitively
        assertFalse(filter.isValid(SuggestedMention("  ", "company"), ""))
        assertFalse(filter.isValid(SuggestedMention("  ", "COMPANY"), ""))
    }
}
