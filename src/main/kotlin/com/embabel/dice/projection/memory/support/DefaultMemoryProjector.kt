package com.embabel.dice.projection.memory.support

import com.embabel.agent.rag.service.EntityIdentifier
import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.projection.memory.*
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import java.time.Instant

/**
 * Default implementation of MemoryProjection backed by a PropositionStore.
 *
 * @param propositionRepository The proposition store to query
 * @param confidenceThreshold Minimum confidence for including propositions
 * @param knowledgeTypeClassifier Strategy for classifying propositions into knowledge types
 */
data class DefaultMemoryProjector(
    private val propositionRepository: PropositionRepository,
    private val confidenceThreshold: Double = 0.6,
    private val knowledgeTypeClassifier: KnowledgeTypeClassifier = KeywordMatchingMemoryTypeClassifier,
) : MemoryProjector {

    companion object {

        /** Default instance with standard settings, against the given PropositionRepository */
        @JvmStatic
        fun against(propositionRepository: PropositionRepository) =
            DefaultMemoryProjector(propositionRepository)
    }

    fun withConfidenceThreshold(threshold: Double) =
        copy(confidenceThreshold = threshold)

    fun withKnowledgeTypeClassifier(classifier: KnowledgeTypeClassifier) =
        copy(knowledgeTypeClassifier = classifier)

    override fun projectUserPersonaSnapshot(
        userId: String,
        scope: MemoryScope,
    ): UserPersonaSnapshot {
        val propositions = propositionRepository.findByEntity(EntityIdentifier.forUser(userId))
            .filter { it.confidence >= confidenceThreshold }
            .filter { knowledgeTypeClassifier.classify(it) == KnowledgeType.SEMANTIC }
            .sortedByDescending { it.confidence }

        return UserPersonaSnapshot(
            facts = propositions.map { it.text },
            confidence = if (propositions.isEmpty()) 0.0
            else propositions.map { it.confidence }.average(),
            sourcePropositionIds = propositions.map { it.id },
        )
    }

    override fun projectRecentEvents(
        userId: String,
        since: Instant,
        limit: Int,
    ): List<Event> {
        val propositions = propositionRepository.findByEntity(EntityIdentifier.Companion.forUser(userId))
            .filter { it.created.isAfter(since) }
            .filter { knowledgeTypeClassifier.classify(it) == KnowledgeType.EPISODIC }
            .sortedByDescending { it.created }
            .take(limit)

        return propositions.map { prop ->
            Event(
                description = prop.text,
                eventTime = prop.created,
                participants = prop.mentions.mapNotNull { it.resolvedId },
                sourcePropositionIds = listOf(prop.id),
            )
        }
    }

    override fun projectBehavioralRules(
        userId: String,
    ): List<BehavioralRule> {
        val propositions = propositionRepository.findByEntity(EntityIdentifier.Companion.forUser(userId))
            .filter { it.confidence >= confidenceThreshold }
            .filter { knowledgeTypeClassifier.classify(it) == KnowledgeType.PROCEDURAL }
            .sortedByDescending { it.confidence }

        return propositions.map { prop ->
            // Try to parse condition/action from text
            val (condition, action) = parseConditionalRule(prop.text)
            BehavioralRule(
                condition = condition,
                action = action,
                confidence = prop.confidence,
                sourcePropositionIds = listOf(prop.id),
            )
        }
    }

    override fun projectWorkingMemory(
        scope: MemoryScope,
        sessionPropositions: List<Proposition>,
        budget: Int,
    ): WorkingMemory {
        val persona = projectUserPersonaSnapshot(scope.userId, scope)
        val events = projectRecentEvents(scope.userId, limit = budget / 4)
        val rules = projectBehavioralRules(scope.userId)

        // Calculate how many session propositions we can include
        val usedBudget = persona.facts.size + events.size + rules.size
        val sessionBudget = (budget - usedBudget).coerceAtLeast(0)

        return WorkingMemory(
            userPersona = persona,
            recentEvents = events,
            behavioralRules = rules,
            sessionPropositions = sessionPropositions.take(sessionBudget),
            budget = budget,
        )
    }

    /**
     * Parse a procedural proposition into condition/action parts.
     * Handles patterns like "When X, do Y" or "Prefers X".
     */
    private fun parseConditionalRule(text: String): Pair<String?, String> {
        val lowerText = text.lowercase()

        // Pattern: "when X, Y" or "when X then Y"
        val whenMatch = Regex("when (.+?),?\\s*(?:then\\s*)?(.+)", RegexOption.IGNORE_CASE)
            .find(text)
        if (whenMatch != null) {
            return whenMatch.groupValues[1].trim() to whenMatch.groupValues[2].trim()
        }

        // Pattern: "if X, Y" or "if X then Y"
        val ifMatch = Regex("if (.+?),?\\s*(?:then\\s*)?(.+)", RegexOption.IGNORE_CASE)
            .find(text)
        if (ifMatch != null) {
            return ifMatch.groupValues[1].trim() to ifMatch.groupValues[2].trim()
        }

        // No condition found - the whole text is the action
        return null to text
    }
}