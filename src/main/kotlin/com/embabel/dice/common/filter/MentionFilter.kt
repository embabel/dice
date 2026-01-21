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
 * Filter for validating entity mentions during proposition extraction.
 *
 * Filters can reject low-quality mentions (vague references, overly long spans,
 * generic descriptions) before they create entities in the knowledge graph.
 *
 * Example usage:
 * ```kotlin
 * // Schema-driven validation (recommended)
 * val filter = SchemaValidatedMentionFilter(dataDictionary)
 *
 * // Or context-aware filtering
 * val contextFilter = CompositeMentionFilter(listOf(
 *     SchemaValidatedMentionFilter(dataDictionary),
 *     PropositionDuplicateFilter()
 * ))
 *
 * if (filter.isValid(mention, propositionText)) {
 *     // Create entity from mention
 * }
 * ```
 */
interface MentionFilter {
    /**
     * Validates whether an entity mention should be included in entity resolution.
     *
     * @param mention The mention extracted by LLM
     * @param propositionText The full proposition text for context
     * @return true if mention is valid and should be resolved, false to filter out
     */
    fun isValid(
        mention: SuggestedMention,
        propositionText: String,
    ): Boolean

    /**
     * Optional: Provides reason for rejection (useful for debugging/logging).
     *
     * @param mention The mention to check
     * @return Human-readable rejection reason, or null if valid
     */
    fun rejectionReason(mention: SuggestedMention): String? = null
}
