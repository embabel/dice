/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository

/**
 * Result of a memory maintenance run.
 *
 * @property consolidation Result from the consolidation stage, null if no session propositions were provided
 * @property abstractions New abstract propositions generated from entity groups
 * @property superseded Source propositions that were marked SUPERSEDED after abstraction
 * @property retired Propositions that were deleted because their effective confidence fell below the threshold
 * @property collectorResult Result of the optional collector stage, or null when no collector was configured
 */
data class MaintenanceResult(
    val consolidation: ConsolidationResult?,
    val abstractions: List<Proposition>,
    val superseded: List<Proposition>,
    val retired: List<Proposition>,
    val collectorResult: CollectorRunResult? = null,
) {
    /** Total number of propositions persisted (promoted + reinforced + merged + abstractions + superseded status changes) */
    val totalPersisted: Int
        get() = (consolidation?.storedCount ?: 0) + abstractions.size + superseded.size

    /** Total number of propositions removed (retired) */
    val totalRemoved: Int
        get() = retired.size
}

/**
 * Orchestrates memory maintenance — consolidating, abstracting, retiring, and persisting —
 * as a single entry point suitable for end-of-session or scheduled batch runs.
 *
 * Three-stage pipeline:
 * 1. **Consolidate** — Promote/reinforce/merge/discard session propositions against existing long-term memories.
 * 2. **Abstract** — Synthesize higher-level insights from groups of related propositions (grouped by entity).
 * 3. **Retire expired** — Delete ACTIVE propositions whose effective confidence has fallen below a threshold.
 *
 * Usage:
 * ```kotlin
 * // End-of-session: consolidate + abstract
 * val orchestrator = MemoryMaintenanceOrchestrator
 *     .withRepository(propositionRepository)
 *     .withConsolidator(DefaultMemoryConsolidator())
 *     .withAbstractor(LlmPropositionAbstractor.withLlm(llm).withAi(ai))
 *     .withRetireBelow(0.1)
 *
 * val result = orchestrator.maintain(contextId, sessionPropositions)
 *
 * // Scheduled maintenance: just abstract + retire (no session props)
 * val result = orchestrator.maintain(contextId)
 * ```
 */
interface MemoryMaintenanceOrchestrator {

    /**
     * Run all maintenance stages for the given context.
     *
     * @param contextId The context to maintain
     * @param sessionPropositions Propositions from the current session to consolidate; empty for scheduled maintenance
     * @return Result summarizing all changes made
     */
    fun maintain(
        contextId: ContextId,
        sessionPropositions: List<Proposition> = emptyList(),
    ): MaintenanceResult

    /**
     * Run all maintenance stages for a context given as a plain string, with no session propositions.
     *
     * Same rules as [maintain]. Handy for Java callers and anyone who already holds the id as text.
     *
     * @param contextIdValue The id of the context to maintain. Must not be blank.
     * @return Result summarizing all changes made
     * @throws IllegalArgumentException if contextIdValue is blank.
     */
    fun maintainValue(contextIdValue: String): MaintenanceResult = maintain(ContextId(contextIdValue))

    /**
     * Run all maintenance stages for a context given as a plain string, with session propositions.
     *
     * Same rules as [maintain]. Handy for Java callers and anyone who already holds the id as text.
     *
     * @param contextIdValue The id of the context to maintain. Must not be blank.
     * @param sessionPropositions Propositions from the current session to consolidate
     * @return Result summarizing all changes made
     * @throws IllegalArgumentException if contextIdValue is blank.
     */
    fun maintainValue(contextIdValue: String, sessionPropositions: List<Proposition>): MaintenanceResult =
        maintain(ContextId(contextIdValue), sessionPropositions)

    /**
     * Intermediate builder step — call [withConsolidator] to get a usable orchestrator.
     */
    class NeedsConsolidator internal constructor(
        private val repository: PropositionRepository,
    ) {
        fun withConsolidator(consolidator: MemoryConsolidator): DefaultMemoryMaintenanceOrchestrator =
            DefaultMemoryMaintenanceOrchestrator(
                repository = repository,
                consolidator = consolidator,
            )
    }

    companion object {
        /**
         * Start building a [MemoryMaintenanceOrchestrator] backed by the given repository.
         */
        @JvmStatic
        fun withRepository(repository: PropositionRepository): NeedsConsolidator =
            NeedsConsolidator(repository)
    }
}
