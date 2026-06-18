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
package com.embabel.dice.common.validation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ValidationRuleTest {

    @Test
    fun `NotBlank rejects empty string`() {
        val result = NotBlank.validate("")
        assertFalse(result.isValid)
        assertEquals("Mention is blank or whitespace-only", result.reason)
    }

    @Test
    fun `NotBlank rejects whitespace-only string`() {
        val result = NotBlank.validate("   ")
        assertFalse(result.isValid)
    }

    @Test
    fun `NotBlank accepts non-empty string`() {
        val result = NotBlank.validate("OpenAI")
        assertTrue(result.isValid)
        assertNull(result.reason)
    }

    @Test
    fun `NoVagueReferences rejects demonstrative pronouns`() {
        val rule = NoVagueReferences()

        assertFalse(rule.validate("this investment").isValid)
        assertFalse(rule.validate("that company").isValid)
        assertFalse(rule.validate("these initiatives").isValid)
        assertFalse(rule.validate("those projects").isValid)
        assertFalse(rule.validate("the announcement").isValid)
    }

    @Test
    fun `NoVagueReferences accepts proper names`() {
        val rule = NoVagueReferences()

        assertTrue(rule.validate("OpenAI").isValid)
        assertTrue(rule.validate("Microsoft").isValid)
        assertTrue(rule.validate("Sam Altman").isValid)
    }

    @Test
    fun `NoVagueReferences custom excluded starters`() {
        val rule = NoVagueReferences(excludedStarters = listOf("my", "our"))

        assertFalse(rule.validate("my company").isValid)
        assertFalse(rule.validate("our project").isValid)
        assertTrue(rule.validate("this company").isValid) // Not in custom list
    }

    @Test
    fun `LengthConstraint enforces minimum length`() {
        val rule = LengthConstraint(minLength = 3)

        assertFalse(rule.validate("AI").isValid)
        assertTrue(rule.validate("OpenAI").isValid)
    }

    @Test
    fun `LengthConstraint enforces maximum length`() {
        val rule = LengthConstraint(maxLength = 10)

        assertTrue(rule.validate("OpenAI").isValid)
        assertFalse(rule.validate("VeryLongCompanyName").isValid)
    }

    @Test
    fun `LengthConstraint enforces both min and max`() {
        val rule = LengthConstraint(minLength = 2, maxLength = 10)

        assertFalse(rule.validate("X").isValid) // Too short
        assertTrue(rule.validate("OK").isValid)
        assertTrue(rule.validate("OpenAI").isValid)
        assertFalse(rule.validate("VeryLongName123").isValid) // Too long
    }

    @Test
    fun `LengthConstraint description reflects constraints`() {
        assertEquals(
            "Length must be at least 3 characters",
            LengthConstraint(minLength = 3).description
        )
        assertEquals(
            "Length must be at most 100 characters",
            LengthConstraint(maxLength = 100).description
        )
        assertEquals(
            "Length must be between 3 and 100 characters",
            LengthConstraint(minLength = 3, maxLength = 100).description
        )
    }

    @Test
    fun `PatternConstraint validates regex`() {
        val rule = PatternConstraint(Regex("^[A-Z].*"), "Must start with capital letter")

        assertTrue(rule.validate("OpenAI").isValid)
        assertFalse(rule.validate("openAI").isValid)
    }

    @Test
    fun `MinWordCount requires minimum words`() {
        val rule = MinWordCount(2)

        assertFalse(rule.validate("OpenAI").isValid)
        assertTrue(rule.validate("Sam Altman").isValid)
        assertTrue(rule.validate("Elon Reeve Musk").isValid)
    }

    @Test
    fun `EntityTypeGuard rejects generic patterns`() {
        val rule = EntityTypeGuard(listOf("a company", "the company", "the person"))

        assertFalse(rule.validate("a company").isValid)
        assertFalse(rule.validate("the company").isValid)
        assertFalse(rule.validate("the person").isValid)
        assertTrue(rule.validate("OpenAI").isValid)
    }

    @Test
    fun `EntityTypeGuard is case insensitive`() {
        val rule = EntityTypeGuard(listOf("the company"))

        assertFalse(rule.validate("The Company").isValid)
        assertFalse(rule.validate("THE COMPANY").isValid)
    }

    @Test
    fun `AllOf requires all rules to pass`() {
        val rule = AllOf(
            NoVagueReferences(),
            LengthConstraint(maxLength = 20)
        )

        assertTrue(rule.validate("OpenAI").isValid)
        assertFalse(rule.validate("this company").isValid) // Fails first rule
        assertFalse(rule.validate("VeryVeryLongCompanyName").isValid) // Fails second rule
    }

    @Test
    fun `AllOf returns first failure reason`() {
        val rule = AllOf(
            NotBlank,
            NoVagueReferences()
        )

        val result = rule.validate("this company")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("Vague reference"))
    }

    @Test
    fun `AnyOf requires at least one rule to pass`() {
        val rule = AnyOf(
            LengthConstraint(maxLength = 5),
            PatternConstraint(Regex("^Project.*"))
        )

        assertTrue(rule.validate("Project Stargate").isValid) // Matches second (starts with Project)
        assertTrue(rule.validate("Open").isValid) // Matches first (length <= 5)
        assertFalse(rule.validate("123invalid").isValid) // Matches neither
    }

    @Test
    fun `AnyOf returns combined failure reasons`() {
        val rule = AnyOf(
            LengthConstraint(maxLength = 5),
            PatternConstraint(Regex("^[0-9]+$"))
        )

        val result = rule.validate("OpenAI")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("None of the alternatives passed"))
    }

    @Test
    fun `Complex nested rules work correctly`() {
        val rule = AllOf(
            NotBlank,
            AnyOf(
                AllOf(
                    PatternConstraint(Regex("^Project\\s+[A-Z].*")),
                    LengthConstraint(minLength = 10)
                ),
                AllOf(
                    NoVagueReferences(),
                    PatternConstraint(Regex("^[A-Z].*")),
                    LengthConstraint(minLength = 3, maxLength = 50)
                )
            )
        )

        assertTrue(rule.validate("Project Stargate").isValid)
        assertTrue(rule.validate("OpenAI").isValid)
        assertFalse(rule.validate("this project").isValid)
        assertFalse(rule.validate("").isValid)
    }
}
