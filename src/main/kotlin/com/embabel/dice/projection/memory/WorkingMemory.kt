package com.embabel.dice.projection.memory

import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.dice.proposition.Proposition

/**
 * Working memory combines multiple memory types for current session context.
 * This is what gets injected into LLM prompts.
 *
 * @property userPersona User persona snapshot with semantic facts
 * @property recentEvents Recent episodic memories
 * @property behavioralRules Procedural rules to follow
 * @property sessionPropositions Raw propositions from current session
 * @property budget Maximum number of items to include in context
 */
data class WorkingMemory(
    val userPersona: UserPersonaSnapshot,
    val recentEvents: List<Event>,
    val behavioralRules: List<BehavioralRule>,
    val sessionPropositions: List<Proposition>,
    val budget: Int,
): PromptContributor {

    override fun contribution(): String = buildString {
        if (userPersona.facts.isNotEmpty()) {
            appendLine("## User Persona")
            userPersona.facts.forEach { appendLine("- $it") }
            appendLine()
        }

        if (recentEvents.isNotEmpty()) {
            appendLine("## Recent Events")
            recentEvents.forEach { appendLine("- ${it.asContext()}") }
            appendLine()
        }

        if (behavioralRules.isNotEmpty()) {
            appendLine("## Behavioral Guidelines")
            behavioralRules.forEach { appendLine("- ${it.asContext()}") }
            appendLine()
        }

        if (sessionPropositions.isNotEmpty()) {
            appendLine("## Current Session Context")
            sessionPropositions.take(budget).forEach { appendLine("- ${it.text}") }
        }
    }

    /** Total number of items in working memory */
    val totalItems: Int
        get() = userPersona.facts.size + recentEvents.size +
                behavioralRules.size + sessionPropositions.size
}