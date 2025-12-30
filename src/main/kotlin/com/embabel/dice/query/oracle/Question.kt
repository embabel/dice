package com.embabel.dice.query.oracle

/**
 * A natural language question to be answered by the Oracle.
 *
 * @property text The question in natural language
 * @property context Optional context to help answer the question
 */
data class Question(
    val text: String,
    val context: Map<String, Any> = emptyMap(),
)

/**
 * An answer from the Oracle with grounding information.
 *
 * @property text The answer in natural language
 * @property confidence Confidence in the answer (0.0-1.0)
 * @property grounding Source proposition IDs that support this answer
 * @property negative Whether this is a negative answer (e.g., "No, X does not...")
 * @property source How the answer was derived
 * @property reasoning Explanation of how the answer was derived
 */
data class Answer(
    val text: String,
    val confidence: Double,
    val grounding: List<String> = emptyList(),
    val negative: Boolean = false,
    val source: AnswerSource,
    val reasoning: String? = null,
) {
    companion object {
        fun unknown(question: Question): Answer = Answer(
            text = "I don't have enough information to answer: ${question.text}",
            confidence = 0.0,
            negative = true,
            source = AnswerSource.NONE,
            reasoning = "No relevant facts or propositions found",
        )

        fun fromProlog(
            text: String,
            confidence: Double,
            grounding: List<String>,
            reasoning: String? = null,
        ): Answer = Answer(
            text = text,
            confidence = confidence,
            grounding = grounding,
            negative = false,
            source = AnswerSource.PROLOG,
            reasoning = reasoning,
        )

        fun fromPropositions(
            text: String,
            confidence: Double,
            grounding: List<String>,
            reasoning: String? = null,
        ): Answer = Answer(
            text = text,
            confidence = confidence,
            grounding = grounding,
            negative = false,
            source = AnswerSource.PROPOSITIONS,
            reasoning = reasoning,
        )

        fun negativeFromProlog(
            text: String,
            reasoning: String? = null,
        ): Answer = Answer(
            text = text,
            confidence = 0.8,
            negative = true,
            source = AnswerSource.PROLOG,
            reasoning = reasoning,
        )
    }
}

/**
 * How the answer was derived.
 */
enum class AnswerSource {
    /** Answer derived from Prolog reasoning */
    PROLOG,
    /** Answer derived from searching propositions directly */
    PROPOSITIONS,
    /** No answer could be found */
    NONE,
}
