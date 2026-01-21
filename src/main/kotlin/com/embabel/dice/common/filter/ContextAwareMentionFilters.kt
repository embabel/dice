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

/**
 * Filters that require proposition context beyond just the mention span.
 *
 * These filters implement [MentionFilter] and use both the mention and
 * the proposition text for validation decisions.
 *
 * For span-only validation, use [com.embabel.dice.common.validation.MentionValidationRule]
 * implementations with [SchemaValidatedMentionFilter] instead.
 */

/**
 * Filter that detects when LLM returned proposition text as mention span.
 *
 * Catches LLM field mapping errors where the model confused
 * the proposition text field with the mention span field.
 *
 * @property minPropositionLength Only applies check when proposition exceeds this length.
 *           Short propositions (titles, tickers) may legitimately equal the mention.
 *           Default: 50 chars (avoids false positives for book titles, etc.)
 */
class PropositionDuplicateFilter(
    private val minPropositionLength: Int = 50
) : MentionFilter {

    override fun isValid(mention: SuggestedMention, propositionText: String): Boolean {
        // Short propositions might legitimately BE the entity (titles, trademarks, etc.)
        if (propositionText.trim().length < minPropositionLength) {
            return true
        }
        return mention.span.trim() != propositionText.trim()
    }

    /**
     * Cannot determine rejection reason without proposition context.
     * @return Always null - use [isValid] with propositionText for accurate validation.
     */
    override fun rejectionReason(mention: SuggestedMention): String? = null
}

/**
 * Combines multiple filters using AND logic.
 *
 * A mention is valid only if ALL child filters accept it.
 *
 * @property filters List of filters to apply
 */
class CompositeMentionFilter(
    private val filters: List<MentionFilter>
) : MentionFilter {

    override fun isValid(mention: SuggestedMention, propositionText: String): Boolean {
        return filters.all { it.isValid(mention, propositionText) }
    }

    override fun rejectionReason(mention: SuggestedMention): String? {
        return filters.firstNotNullOfOrNull { filter ->
            filter.rejectionReason(mention)
        }
    }
}
