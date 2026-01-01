package com.embabel.dice.proposition

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.text2graph.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.text2graph.builder.SourceAnalysisConfig

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
        context: SourceAnalysisConfig,
    ): SuggestedPropositions

    /**
     * Convert mentions from suggested propositions to SuggestedEntities
     * for use with the existing EntityResolver.
     *
     * Deduplicates mentions that refer to the same entity (same span + type).
     *
     * @param suggestedPropositions Propositions from extract()
     * @return SuggestedEntities suitable for EntityResolver.resolve()
     */
    fun toSuggestedEntities(
        suggestedPropositions: SuggestedPropositions
    ): SuggestedEntities

    /**
     * Apply entity resolution results to create final Propositions.
     *
     * @param suggestedPropositions The original suggested propositions
     * @param resolutions Entity resolution results from EntityResolver
     * @return Propositions with entity IDs resolved where possible
     */
    fun resolvePropositions(
        suggestedPropositions: SuggestedPropositions,
        resolutions: Resolutions<SuggestedEntityResolution>,
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
            MentionKey(span = mention.span.lowercase().trim(), type = mention.suggestedType)
    }
}

/**
 * Extension function to create SuggestedEntity from a SuggestedMention.
 */
fun SuggestedMention.toSuggestedEntity(): SuggestedEntity =
    SuggestedEntity(
        labels = listOf(suggestedType),
        name = span,
        summary = "Entity mentioned in proposition",
        id = suggestedId,
        properties = emptyMap(),
    )
