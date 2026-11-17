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
import com.embabel.dice.operations.consolidation.ConsolidationPassResult
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Summary of a single dream-loop consolidation cycle.
 *
 * Holds the result from every pass that ran, plus roll-up totals for how many propositions
 * were examined, changed, or newly created.
 *
 * @property contextId The context the cycle ran for.
 * @property contextIdValue [contextId] as a plain string, for Java callers.
 * @property cycleStarted When the cycle began.
 * @property cycleCompleted When the cycle finished; defaults to construction time.
 * @property passResults Each pass's result, in registration (execution) order.
 * @property totalExamined How many propositions were in the ACTIVE snapshot.
 * @property totalTransitioned How many propositions had their status or content changed across all passes.
 * @property totalNewPropositions How many brand-new propositions were produced across all passes.
 * @property triggered Always true for a returned report; a threshold-gated skip returns `null`
 *   rather than a report with this set to false. Retained for forward compatibility.
 */
data class DreamLoopReport(
    @get:JsonProperty("contextId")
    val contextId: ContextId,
    val cycleStarted: Instant,
    val cycleCompleted: Instant = Instant.now(),
    val passResults: List<ConsolidationPassResult>,
    val totalExamined: Int,
    val totalTransitioned: Int,
    val totalNewPropositions: Int,
    val triggered: Boolean,
) {

    /** [contextId] as a plain string, for Java callers. */
    @get:JsonIgnore
    val contextIdValue: String get() = contextId.value

    companion object {

        /**
         * Builds a report from plain values. Java uses this, because it cannot call the constructor
         * with a context id object.
         *
         * @param contextIdValue The context the cycle ran for.
         * @param cycleStarted When the cycle began.
         * @param cycleCompleted When the cycle finished.
         * @param passResults Each pass's result, in execution order.
         * @param totalExamined How many propositions were examined.
         * @param totalTransitioned How many propositions changed.
         * @param totalNewPropositions How many new propositions were produced.
         * @param triggered Whether the cycle ran.
         * @return The report.
         * @throws IllegalArgumentException if [contextIdValue] is blank
         */
        @JvmStatic
        fun of(
            contextIdValue: String,
            cycleStarted: Instant,
            cycleCompleted: Instant,
            passResults: List<ConsolidationPassResult>,
            totalExamined: Int,
            totalTransitioned: Int,
            totalNewPropositions: Int,
            triggered: Boolean,
        ): DreamLoopReport = DreamLoopReport(
            contextId = ContextId(contextIdValue),
            cycleStarted = cycleStarted,
            cycleCompleted = cycleCompleted,
            passResults = passResults,
            totalExamined = totalExamined,
            totalTransitioned = totalTransitioned,
            totalNewPropositions = totalNewPropositions,
            triggered = triggered,
        )
    }
}
