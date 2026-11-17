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

import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition

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
     * This is the verdict with no analysis context.
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

    /**
     * Judges every mention of one proposition with the analysis context.
     *
     * The pipeline calls this once per proposition. The default asks [isValid] about each
     * mention and takes the reason from [rejectionReason].
     *
     * A filter overrides this method when the verdict depends on the context, for example the
     * known entities, or on the mentions taken together.
     *
     * The method can be called from several threads at once, so an implementation keeps no
     * state per call. The context carries the run's shared entity resolver, and a filter does
     * not call it.
     *
     * A filter that wraps another filter overrides this method and forwards it. A wrapper that
     * forwards only [isValid] hides the wrapped filter's version of this method.
     *
     * A test double handed to the pipeline has to answer this method. A mock that returns an
     * empty list for a proposition with mentions fails the chunk.
     *
     * @param proposition The proposition whose mentions are judged
     * @param context The analysis context of the run
     * @return One result per mention, in the order of `proposition.mentions`
     */
    fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> =
        proposition.mentions.map { mention ->
            if (isValid(mention, proposition.text)) ValidationResult.Valid
            else ValidationResult.Invalid(rejectionReason(mention) ?: "rejected by a mention filter")
        }
}

/**
 * Asks a filter about a proposition's mentions and makes sure it answered once per mention.
 *
 * Every caller that lines results up with mentions goes through here, so a filter that answers
 * for too few or too many mentions fails loudly instead of being cut short or padded.
 *
 * @param proposition the proposition whose mentions are judged
 * @param context the analysis context of the run
 * @return one result per mention, in mention order
 * @throws IllegalStateException when the filter returns a different number of results than mentions
 */
internal fun MentionFilter.checkedVerdicts(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> {
    val results = validateMentions(proposition, context)
    check(results.size == proposition.mentions.size) {
        "${this::class.java.name} returned ${results.size} results for ${proposition.mentions.size} mentions"
    }
    return results
}
