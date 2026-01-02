package com.embabel.dice.projection.memory

import com.embabel.dice.common.EntityRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import java.time.Instant

/**
 * Projects propositions into memory structures for agent consumption.
 * Memory is a view over propositions, not a separate storage system.
 */
interface MemoryProjection {

    /**
     * Project user profile from semantic propositions.
     * Aggregates facts about the user into a consumable format.
     *
     * @param userId The user to build profile for
     * @param scope Memory scope for filtering
     * @return User profile with facts and confidence
     */
    fun projectUserProfile(
        userId: String,
        scope: MemoryScope = MemoryScope.global(userId),
    ): UserProfile

    /**
     * Project recent events from episodic propositions.
     *
     * @param userId The user whose events to retrieve
     * @param since Only include events after this time
     * @param limit Maximum number of events
     * @return List of events ordered by time (most recent first)
     */
    fun projectRecentEvents(
        userId: String,
        since: Instant = Instant.now().minusSeconds(86400), // last 24 hours
        limit: Int = 20,
    ): List<Event>

    /**
     * Project behavioral rules from procedural propositions.
     * These can feed into Prolog rules or agent instructions.
     *
     * @param userId The user whose rules to retrieve
     * @return List of behavioral rules ordered by confidence
     */
    fun projectBehavioralRules(
        userId: String,
    ): List<BehavioralRule>

    /**
     * Project working memory for current session.
     * Combines: user profile + recent events + behavioral rules + session propositions.
     *
     * @param scope Memory scope defining the context
     * @param sessionPropositions Propositions from the current session
     * @param budget Maximum total items to include
     * @return Working memory ready for LLM context injection
     */
    fun projectWorkingMemory(
        scope: MemoryScope,
        sessionPropositions: List<Proposition> = emptyList(),
        budget: Int = 50,
    ): WorkingMemory
}

/**
 * Default implementation of MemoryProjection backed by a PropositionStore.
 *
 * @param store The proposition store to query
 * @param confidenceThreshold Minimum confidence for including propositions
 * @param memoryTypeClassifier Strategy for classifying propositions into memory types
 */
data class DefaultMemoryProjection(
    private val store: PropositionRepository,
    private val confidenceThreshold: Double = 0.6,
    private val memoryTypeClassifier: MemoryTypeClassifier = KeywordMatchingMemoryTypeClassifier,
) : MemoryProjection {

    companion object {

        /** Default instance with standard settings */
        @JvmStatic
        fun against(store: PropositionRepository) =
            DefaultMemoryProjection(store)
    }

    fun withConfidenceThreshold(threshold: Double) =
        copy(confidenceThreshold = threshold)

    fun withMemoryTypeClassifier(classifier: MemoryTypeClassifier) =
        copy(memoryTypeClassifier = classifier)

    override fun projectUserProfile(
        userId: String,
        scope: MemoryScope,
    ): UserProfile {
        val propositions = store.findByEntity(EntityRequest.forUser(userId))
            .filter { it.confidence >= confidenceThreshold }
            .filter { memoryTypeClassifier.classify(it) == MemoryType.SEMANTIC }
            .sortedByDescending { it.confidence }

        return UserProfile(
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
        val propositions = store.findByEntity(EntityRequest.forUser(userId))
            .filter { it.created.isAfter(since) }
            .filter { memoryTypeClassifier.classify(it) == MemoryType.EPISODIC }
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
        val propositions = store.findByEntity(EntityRequest.forUser(userId))
            .filter { it.confidence >= confidenceThreshold }
            .filter { memoryTypeClassifier.classify(it) == MemoryType.PROCEDURAL }
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
        val profile = projectUserProfile(scope.userId, scope)
        val events = projectRecentEvents(scope.userId, limit = budget / 4)
        val rules = projectBehavioralRules(scope.userId)

        // Calculate how many session propositions we can include
        val usedBudget = profile.facts.size + events.size + rules.size
        val sessionBudget = (budget - usedBudget).coerceAtLeast(0)

        return WorkingMemory(
            userProfile = profile,
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
