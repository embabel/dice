package com.embabel.dice.common

/**
 * Classification of the epistemological nature of knowledge.
 * Describes what kind of fact or relationship is being expressed.
 */
enum class KnowledgeType {
    /**
     * Factual knowledge about properties and relationships.
     * High confidence, low decay, long-term facts.
     * Example: "Alice works at Acme", "Paris is in France"
     */
    SEMANTIC,

    /**
     * Event-based knowledge about occurrences.
     * Has temporal context, higher decay.
     * Example: "Alice met Bob yesterday", "The meeting happened at 3pm"
     */
    EPISODIC,

    /**
     * Behavioral patterns, preferences, and habits.
     * Rules about how things are typically done.
     * Example: "Alice prefers tea", "When deploying, use AWS"
     */
    PROCEDURAL,

    /**
     * Transient, session-scoped knowledge.
     * Current context, not yet consolidated into long-term memory.
     * Example: "The user just asked about X"
     */
    WORKING
}
