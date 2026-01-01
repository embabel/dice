package com.embabel.dice.projection.memory

import com.embabel.dice.proposition.Proposition

/**
 * Simple implementation that uses text pattern matching and confidence/decay heuristics.
 * Fast. Accuracy depends on prompting.
 */
object KeywordMatchingMemoryTypeClassifier : MemoryTypeClassifier {

    private val proceduralPatterns = listOf("prefer", "when ", "always ", "should ", "never ", "like to", "tend to")
    private val episodicPatterns = listOf("yesterday", "today", "last week", "recently", "just ", "happened", "met ", "went ")

    override fun classify(proposition: Proposition): MemoryType {
        // Procedural: patterns like "prefers", "when...do", "always", "should"
        if (proceduralPatterns.any { proposition.text.lowercase().contains(it) }) {
            return MemoryType.PROCEDURAL
        }

        // Episodic: high decay or event-like language
        if (proposition.decay > 0.5 || episodicPatterns.any { proposition.text.lowercase().contains(it) }) {
            return MemoryType.EPISODIC
        }

        // Semantic: high confidence, low decay, factual
        if (proposition.confidence > 0.7 && proposition.decay < 0.3) {
            return MemoryType.SEMANTIC
        }

        // Default to working memory
        return MemoryType.WORKING
    }
}