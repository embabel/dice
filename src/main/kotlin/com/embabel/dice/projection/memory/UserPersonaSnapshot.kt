package com.embabel.dice.projection.memory

import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.dice.proposition.Projection

/**
 * Point-in-time snapshot of a user's persona projected from semantic propositions.
 * Aggregates known facts about a user into a consumable format.
 *
 * @property facts List of fact statements about the user
 * @property confidence Average confidence across source propositions
 * @property decay Decay rate (defaults to 0.0 for snapshots)
 * @property sourcePropositionIds Provenance tracking
 */
data class UserPersonaSnapshot(
    val facts: List<String>,
    override val confidence: Double,
    override val decay: Double = 0.0,
    override val sourcePropositionIds: List<String>,
) : Projection, PromptContributor {

    /** Format persona snapshot for LLM context injection */
    override fun contribution(): String = buildString {
        appendLine("User Persona:")
        facts.forEach { appendLine("- $it") }
    }
}