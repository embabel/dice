package com.embabel.dice.projection.memory

import com.embabel.dice.proposition.Proposition
import java.time.Instant

/**
 * Projects propositions into memory structures for agent consumption.
 * Memory is a view over propositions, not a separate storage system.
 */
interface MemoryProjector {

    /**
     * Project user persona snapshot from semantic propositions.
     * Aggregates known facts about the user into a consumable format.
     *
     * @param userId The user to build persona for
     * @param scope Memory scope for filtering
     * @return User persona snapshot with facts and confidence
     */
    fun projectUserPersonaSnapshot(
        userId: String,
        scope: MemoryScope = MemoryScope.global(userId),
    ): UserPersonaSnapshot

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

