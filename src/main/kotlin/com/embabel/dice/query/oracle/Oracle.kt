package com.embabel.dice.query.oracle

/**
 * Natural language question answering interface.
 * An Oracle answers questions using available knowledge sources.
 */
interface Oracle {

    /**
     * Answer a natural language question.
     *
     * @param question The question to answer
     * @return An answer with grounding information
     */
    fun ask(question: Question): Answer

    /**
     * Answer a question given as a string.
     */
    fun ask(questionText: String): Answer = ask(Question(questionText))
}
