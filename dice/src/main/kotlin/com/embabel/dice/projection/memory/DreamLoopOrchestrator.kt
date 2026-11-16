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
import com.embabel.dice.operations.consolidation.ConsolidationPass

/**
 * Composes [ConsolidationPass]es into a repeatable consolidation cycle — the "dream loop".
 *
 * Each cycle fetches the ACTIVE snapshot for a context once, runs every registered pass over
 * it in registration order, aggregates what all passes want to save into a single write, and
 * returns a [DreamLoopReport]. Passes are pure; only the orchestrator writes to the repository.
 *
 * There are two ways to trigger a cycle:
 * - [consolidate] is threshold-gated and returns `null` when not enough has changed.
 * - [consolidateNow] always runs regardless of how much has changed.
 *
 * ```kotlin
 * val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
 *     .withPass(sessionConsolidationPass)
 *     .withPass(abstractionPass)
 *     .withPass(contradictionResolutionPass)
 *     .withPass(decaySweepPass)
 *
 * // Scheduled / threshold-gated: may return null if little has changed.
 * val maybeReport = orchestrator.consolidate(contextId)
 *
 * // Always runs.
 * val report = orchestrator.consolidateNow(contextId)
 * ```
 */
interface DreamLoopOrchestrator : MemoryMaintenanceOrchestrator {

    /**
     * Run a consolidation cycle only if enough has changed since the last cycle.
     *
     * Returns `null` when the change-volume threshold isn't met — use [consolidateNow] to
     * bypass the threshold entirely.
     *
     * @param contextId The context to consolidate.
     * @return The cycle report when the cycle ran, or `null` if the threshold wasn't met.
     */
    fun consolidate(contextId: ContextId): DreamLoopReport?

    /**
     * Run a consolidation cycle unconditionally, regardless of how much has changed.
     *
     * @param contextId The context to consolidate.
     * @return The cycle report; never `null`.
     */
    fun consolidateNow(contextId: ContextId): DreamLoopReport

    /**
     * Run a consolidation cycle only if enough has changed, taking the context as a plain string.
     *
     * Same rules as [consolidate]. Handy for Java callers and anyone who already holds the id as text.
     *
     * @param contextIdValue The id of the context to consolidate. Must not be blank.
     * @return The cycle report when the cycle ran, or `null` if the threshold wasn't met.
     * @throws IllegalArgumentException if contextIdValue is blank.
     */
    fun consolidate(contextIdValue: String): DreamLoopReport? = consolidate(ContextId(contextIdValue))

    /**
     * Run a consolidation cycle unconditionally, taking the context as a plain string.
     *
     * Same rules as [consolidateNow]. Handy for Java callers and anyone who already holds the id as text.
     *
     * @param contextIdValue The id of the context to consolidate. Must not be blank.
     * @return The cycle report; never `null`.
     * @throws IllegalArgumentException if contextIdValue is blank.
     */
    fun consolidateNow(contextIdValue: String): DreamLoopReport = consolidateNow(ContextId(contextIdValue))

    /**
     * Add a pass and return a new orchestrator with it appended. Registration order is
     * execution order.
     *
     * @param pass The pass to append.
     * @return A new orchestrator with the existing passes plus [pass].
     */
    fun withPass(pass: ConsolidationPass): DreamLoopOrchestrator
}
