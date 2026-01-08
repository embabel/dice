package com.embabel.dice.projection.memory

import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.proposition.Proposition

/**
 * Projects propositions into memory organized by knowledge type.
 *
 * This is a simple classifier - it takes propositions (queried however the caller
 * wants via [PropositionQuery]) and organizes them by knowledge type.
 *
 * Example usage:
 * ```kotlin
 * // Query propositions using PropositionQuery
 * val props = repository.query(
 *     PropositionQuery.forEntity(userId)
 *         .withMinEffectiveConfidence(0.5)
 *         .orderedByEffectiveConfidence()
 * )
 *
 * // Project into memory types
 * val memory = projector.project(props)
 *
 * // Use the classified propositions
 * memory.semantic   // facts
 * memory.procedural // preferences/rules
 * memory.episodic   // events
 * ```
 */
interface MemoryProjector {

    /**
     * Project propositions into memory organized by knowledge type.
     *
     * @param propositions The propositions to classify (caller controls query)
     * @return Memory projection with propositions grouped by knowledge type
     */
    fun project(propositions: List<Proposition>): MemoryProjection
}

/**
 * Propositions organized by knowledge type.
 *
 * Implements [PromptContributor] so it can be passed directly to LLM prompts
 * via `withPromptElements()`.
 *
 * @property semantic Factual knowledge ("Paris is in France")
 * @property episodic Event-based memories ("Met Alice yesterday")
 * @property procedural Preferences and habits ("Likes jazz music")
 * @property working Session/transient context ("Currently discussing X")
 */
data class MemoryProjection(
    val semantic: List<Proposition> = emptyList(),
    val episodic: List<Proposition> = emptyList(),
    val procedural: List<Proposition> = emptyList(),
    val working: List<Proposition> = emptyList(),
) : PromptContributor {

    /** Total number of propositions across all types */
    val size: Int get() = semantic.size + episodic.size + procedural.size + working.size

    /** Get propositions by knowledge type */
    operator fun get(type: KnowledgeType): List<Proposition> = when (type) {
        KnowledgeType.SEMANTIC -> semantic
        KnowledgeType.EPISODIC -> episodic
        KnowledgeType.PROCEDURAL -> procedural
        KnowledgeType.WORKING -> working
    }

    /** All propositions flattened */
    fun all(): List<Proposition> = semantic + episodic + procedural + working

    /**
     * Format the memory projection for LLM context injection.
     * Implements [PromptContributor.contribution].
     */
    override fun contribution(): String = buildString {
        if (semantic.isNotEmpty()) {
            appendLine("## Known Facts")
            semantic.forEach { appendLine("- ${it.text}") }
            appendLine()
        }

        if (procedural.isNotEmpty()) {
            appendLine("## Preferences & Guidelines")
            procedural.forEach { appendLine("- ${it.text}") }
            appendLine()
        }

        if (episodic.isNotEmpty()) {
            appendLine("## Recent Events")
            episodic.forEach { appendLine("- ${it.text}") }
            appendLine()
        }

        if (working.isNotEmpty()) {
            appendLine("## Current Context")
            working.forEach { appendLine("- ${it.text}") }
        }
    }.trimEnd()

    companion object {
        /** Empty projection */
        @JvmStatic
        val EMPTY = MemoryProjection()
    }
}
