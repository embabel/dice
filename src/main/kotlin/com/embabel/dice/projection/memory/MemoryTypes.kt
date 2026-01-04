package com.embabel.dice.projection.memory

import com.embabel.dice.proposition.Projection
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
) : Projection {

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
) : Projection {

    /** Format rule for LLM context injection */
    fun asContext(): String = if (condition != null) {
        "When $condition: $action"
    } else {
        action
    }
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

