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
package com.embabel.dice.proposition

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.filter.MentionFilter

/**
 * Extracts propositions from text chunks.
 * This is the entry point for the proposition-based ingestion pipeline.
 *
 * Pipeline flow:
 * 1. extract() - LLM extracts SuggestedPropositions from chunk
 * 2. toSuggestedEntities() - Convert mentions to SuggestedEntities
 * 3. (caller invokes EntityResolver.resolve())
 * 4. resolvePropositions() - Apply entity resolutions to create final Propositions
 */
interface PropositionExtractor {

    /**
     * Extract propositions from a chunk using LLM.
     * @param chunk The text chunk to analyze
     * @param context Configuration including schema and optional directions
     * @return Container with suggested propositions
     */
    fun extract(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): SuggestedPropositions

    /**
     * Convert mentions from suggested propositions to SuggestedEntities
     * for use with the existing EntityResolver.
     *
     * Deduplicates mentions that refer to the same entity (same span + type).
     *
     * @param suggestedPropositions Propositions from extract()
     * @param context The source analysis context
     * @param sourceText Optional source text for context during entity resolution
     * @param mentionFilter Optional filter to validate mentions before creating entities
     * @return SuggestedEntities suitable for EntityResolver.resolve()
     */
    fun toSuggestedEntities(
        suggestedPropositions: SuggestedPropositions,
        context: SourceAnalysisContext,
        sourceText: String? = null,
        mentionFilter: MentionFilter? = null,
    ): SuggestedEntities

    /**
     * Apply entity resolution results to create final Propositions.
     *
     * @param suggestedPropositions The original suggested propositions
     * @param resolutions Entity resolution results from EntityResolver
     * @param context The source analysis context containing contextId
     * @return Propositions with entity IDs resolved where possible
     */
    fun resolvePropositions(
        suggestedPropositions: SuggestedPropositions,
        resolutions: Resolutions<SuggestedEntityResolution>,
        context: SourceAnalysisContext,
    ): List<Proposition>
}

/**
 * Utility class to track the mapping from mentions to their SuggestedEntity representations.
 * Used internally to coordinate between extraction and resolution.
 */
data class MentionKey(
    val span: String,
    val type: String,
) {
    companion object {
        fun from(mention: SuggestedMention): MentionKey =
            MentionKey(span = mention.span.lowercase().trim(), type = mention.type.lowercase().trim())
    }
}
