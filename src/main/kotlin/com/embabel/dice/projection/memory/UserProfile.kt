package com.embabel.dice.projection.memory

import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.dice.proposition.Projected

/**
 * User profile projected from semantic propositions.
 * Aggregates facts about a user into a consumable format.
 *
 * @property facts List of fact statements about the user
 * @property confidence Average confidence across source propositions
 * @property decay Decay rate (defaults to 0.0 for profiles)
 * @property sourcePropositionIds Provenance tracking
 */
data class UserProfile(
    val facts: List<String>,
    override val confidence: Double,
    override val decay: Double = 0.0,
    override val sourcePropositionIds: List<String>,
) : Projected, PromptContributor {

    /** Format profile for LLM context injection */
    override fun contribution(): String = buildString {
        appendLine("User Profile:")
        facts.forEach { appendLine("- $it") }
    }
}