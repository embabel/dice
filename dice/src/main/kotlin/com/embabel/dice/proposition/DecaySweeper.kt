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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.dice.spi.DecayStatusPolicy
import com.embabel.dice.spi.StatusTransitionPolicy

/**
 * Evaluates propositions for lifecycle transitions and applies them according to a
 * [StatusTransitionPolicy].
 *
 * Scheduling the sweep (cron, `@Scheduled`, manual trigger) is the consuming
 * application's responsibility — DICE ships no scheduler of its own.
 *
 * To customize forgetting or consolidation behavior, supply a different policy via
 * [DecaySweepConfig.policy] rather than subclassing the sweeper.
 */
interface DecaySweeper {

    /**
     * Sweep the propositions in a single context for lifecycle transitions.
     *
     * @param contextId The context whose propositions should be evaluated
     * @param config The sweep configuration (policy, target statuses, pruning)
     * @return The sweep result (swept, no-op, or failed)
     */
    fun sweep(contextId: ContextId, config: DecaySweepConfig): DecaySweepResult

    /**
     * Sweep the propositions across all contexts for lifecycle transitions.
     *
     * @param config The sweep configuration (policy, target statuses, pruning)
     * @return The sweep result (swept, no-op, or failed)
     */
    fun sweepAll(config: DecaySweepConfig): DecaySweepResult
}

/**
 * Configuration for a [DecaySweeper] run.
 *
 * @property policy The transition policy applied per-proposition. Defaults to [DecayStatusPolicy].
 * @property targetStatuses Which statuses the sweep considers for evaluation. Defaults to
 *   `{ACTIVE}`, which deliberately excludes `PROMOTED` — propositions already projected to the
 *   typed graph are not swept to STALE unless a consumer explicitly widens this set.
 * @property pruneStale When true, STALE propositions may be hard-deleted by the sweep. Defaults
 *   to false so stale propositions remain queryable for audit and potential revival.
 */
data class DecaySweepConfig @JvmOverloads constructor(
    val policy: StatusTransitionPolicy = DecayStatusPolicy(),
    val targetStatuses: Set<PropositionStatus> = setOf(PropositionStatus.ACTIVE),
    val pruneStale: Boolean = false,
)

/**
 * Result of a [DecaySweeper] run.
 */
sealed class DecaySweepResult {

    /**
     * The sweep ran and (potentially) transitioned propositions.
     *
     * @property transitioned Propositions moved to STALE.
     * @property revived Propositions moved back to ACTIVE.
     * @property pruned Propositions hard-pruned (only when `pruneStale` is enabled).
     * @property skipped Count of propositions evaluated but left unchanged.
     */
    data class Swept(
        val transitioned: List<Proposition>,
        val revived: List<Proposition>,
        val pruned: List<Proposition>,
        val skipped: Int,
    ) : DecaySweepResult()

    /**
     * The sweep had nothing to do.
     *
     * @property reason Human-readable explanation.
     */
    data class NoOp(val reason: String) : DecaySweepResult()

    /**
     * The sweep failed.
     *
     * @property cause The failure cause.
     */
    data class Failed(val cause: Throwable) : DecaySweepResult()
}
