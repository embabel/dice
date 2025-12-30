package com.embabel.dice.proposition.content

import java.time.Instant

/**
 * Content that can be processed to extract propositions.
 *
 * This is the core abstraction for the proposition extraction pipeline.
 * Both sensor observations and document chunks can be adapted to this interface,
 * enabling a unified pipeline for proposition extraction and revision.
 *
 * Key properties:
 * - [timestamp]: When this content was observed/created
 * - [sourceId]: Unique identifier for grounding propositions back to source
 * - [toProposalContext]: Converts content to text for LLM analysis
 */
interface ProposableContent {

    /**
     * When this content was observed or created.
     * Used for temporal reasoning and decay calculations.
     */
    val timestamp: Instant

    /**
     * Unique identifier for this content.
     * Used for grounding propositions back to their source.
     */
    val sourceId: String

    /**
     * Convert this content to text context for proposition extraction.
     * Returns empty string if there's nothing meaningful to extract from.
     */
    fun toProposalContext(): String

    /**
     * Whether this content has meaningful text to extract propositions from.
     * Default implementation checks if [toProposalContext] returns non-blank text.
     */
    fun isProcessable(): Boolean = toProposalContext().isNotBlank()
}

/**
 * Simple implementation of [ProposableContent] with explicit values.
 * Useful for testing or wrapping arbitrary text content.
 */
data class SimpleContent(
    override val timestamp: Instant = Instant.now(),
    override val sourceId: String,
    private val context: String,
) : ProposableContent {
    override fun toProposalContext(): String = context
}
