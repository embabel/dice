package com.embabel.dice.projection.memory

import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.proposition.Projection
import com.embabel.dice.proposition.Proposition
import java.time.Instant

/**
 * Typealias for backward compatibility.
 * Use [KnowledgeType] directly for new code.
 */
@Deprecated(
    message = "Use KnowledgeType from common package",
    replaceWith = ReplaceWith("KnowledgeType", "com.embabel.dice.common.KnowledgeType")
)
typealias MemoryType = KnowledgeType

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
 * Strategy interface for classifying propositions into knowledge types.
 * Implementations can use different heuristics based on domain needs.
 */
fun interface KnowledgeTypeClassifier {

    /**
     * Classify a proposition into a knowledge type.
     * @param proposition The proposition to classify
     * @return The inferred knowledge type
     */
    fun classify(proposition: Proposition): KnowledgeType
}


