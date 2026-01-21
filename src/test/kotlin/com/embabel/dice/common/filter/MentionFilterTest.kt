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

import com.embabel.dice.proposition.SuggestedMention
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MentionFilterTest {

    @Test
    fun `PropositionDuplicateFilter detects duplicates for long propositions`() {
        val filter = PropositionDuplicateFilter(minPropositionLength = 20)
        val propositionText = "OpenAI announced a new model that will change everything"
        val mention = SuggestedMention(propositionText, "Company")

        // When mention span equals proposition text, it should be filtered
        assertFalse(filter.isValid(mention, propositionText))
    }

    @Test
    fun `PropositionDuplicateFilter allows duplicates for short propositions`() {
        val filter = PropositionDuplicateFilter(minPropositionLength = 50)
        val propositionText = "To Kill a Mockingbird" // 21 chars - could be a book title
        val mention = SuggestedMention(propositionText, "Book")

        // Short propositions may legitimately equal the entity name
        assertTrue(filter.isValid(mention, propositionText))
    }

    @Test
    fun `PropositionDuplicateFilter accepts different text`() {
        val filter = PropositionDuplicateFilter()
        val mention = SuggestedMention("OpenAI", "Company")

        assertTrue(filter.isValid(mention, "OpenAI announced a new model"))
    }

    @Test
    fun `PropositionDuplicateFilter default threshold is 50 chars`() {
        val filter = PropositionDuplicateFilter()

        // Under 50 chars - should allow duplicates
        val shortProposition = "This is a short proposition text" // 33 chars
        val shortMention = SuggestedMention(shortProposition, "Quote")
        assertTrue(filter.isValid(shortMention, shortProposition))

        // Over 50 chars - should reject duplicates
        val longProposition = "This is a very long proposition that exceeds fifty characters easily" // 70 chars
        val longMention = SuggestedMention(longProposition, "Company")
        assertFalse(filter.isValid(longMention, longProposition))
    }

    @Test
    fun `CompositeMentionFilter with empty list accepts all`() {
        val filter = CompositeMentionFilter(emptyList())
        val mention = SuggestedMention("anything", "Company")

        assertTrue(filter.isValid(mention, "Some text"))
    }
}
