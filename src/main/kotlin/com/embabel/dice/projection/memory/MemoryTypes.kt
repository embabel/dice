package com.embabel.dice.projection.memory

import com.embabel.dice.proposition.Projected
import com.embabel.dice.proposition.Proposition
import java.time.Instant

/**
 * Memory type is inferred at projection time based on proposition characteristics.
 * Not stored on the proposition itself.
 */
enum class MemoryType {
    /** High confidence, low decay, about properties/relationships. Long-term facts. */
    SEMANTIC,

    /** Has eventTime, higher decay, about occurrences. What happened when. */
    EPISODIC,

    /** Text patterns like "when X, do Y" / "prefers X". Behavioral patterns. */
    PROCEDURAL,

    /** Current session, not yet consolidated. Temporary working context. */
    WORKING
}

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
) : Projected {

    /** Format profile for LLM context injection */
    fun asContext(): String = buildString {
        appendLine("User Profile:")
        facts.forEach { appendLine("- $it") }
    }
}

/**
 * An event projected from episodic propositions.
 *
 * @property description What happened
 * @property eventTime When it happened
 * @property participants Entity IDs involved in the event
 * @property confidence Confidence in this event (defaults to 1.0)
 * @property decay Decay rate (defaults to 0.0 for events)
 * @property sourcePropositionIds Provenance tracking
 */
data class Event(
    val description: String,
    val eventTime: Instant,
    val participants: List<String> = emptyList(),
    override val confidence: Double = 1.0,
    override val decay: Double = 0.0,
    override val sourcePropositionIds: List<String>,
) : Projected {

    /** Format event for display */
    fun asContext(): String = "$eventTime: $description"
}

/**
 * A behavioral rule projected from procedural propositions.
 * Can feed into Prolog rules or agent instructions.
 *
 * @property condition When this rule applies (e.g., "when asking about deployment")
 * @property action What to do (e.g., "suggest AWS first")
 * @property confidence How certain we are about this rule
 * @property decay Decay rate (defaults to 0.0 for rules)
 * @property sourcePropositionIds Provenance tracking
 */
data class BehavioralRule(
    val condition: String?,
    val action: String,
    override val confidence: Double,
    override val decay: Double = 0.0,
    override val sourcePropositionIds: List<String>,
) : Projected {

    /** Format rule for LLM context injection */
    fun asContext(): String = if (condition != null) {
        "When $condition: $action"
    } else {
        action
    }
}

/**
 * Working memory combines multiple memory types for current session context.
 * This is what gets injected into LLM prompts.
 *
 * @property userProfile Semantic facts about the user
 * @property recentEvents Recent episodic memories
 * @property behavioralRules Procedural rules to follow
 * @property sessionPropositions Raw propositions from current session
 * @property budget Maximum number of items to include in context
 */
data class WorkingMemory(
    val userProfile: UserProfile,
    val recentEvents: List<Event>,
    val behavioralRules: List<BehavioralRule>,
    val sessionPropositions: List<Proposition>,
    val budget: Int,
) {
    /** Format working memory for LLM context injection */
    fun asContext(): String = buildString {
        if (userProfile.facts.isNotEmpty()) {
            appendLine("## User Profile")
            userProfile.facts.forEach { appendLine("- $it") }
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
        get() = userProfile.facts.size + recentEvents.size +
                behavioralRules.size + sessionPropositions.size
}

/**
 * Strategy interface for classifying propositions into memory types.
 * Implementations can use different heuristics based on domain needs.
 */
fun interface MemoryTypeClassifier {

    /**
     * Classify a proposition into a memory type.
     * @param proposition The proposition to classify
     * @return The inferred memory type
     */
    fun classify(proposition: Proposition): MemoryType

}

