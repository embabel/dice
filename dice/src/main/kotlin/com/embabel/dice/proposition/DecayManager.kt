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

/**
 * Storage-agnostic [DecaySweeper] over a [PropositionRepository], plus an optional hook to
 * **materialise** decayed confidence where a backend can cache it.
 *
 * Two responsibilities, kept as separate callable operations (no forced `run()`):
 * - **Lifecycle** ([sweep] / [sweepAll]) — applies [com.embabel.dice.spi.StatusTransitionPolicy]
 *   status transitions (ACTIVE→STALE, STALE→ACTIVE, optional pruning) through the repository.
 *   Works for any backend; pinned propositions are policy-exempt.
 * - **Materialisation** ([materialize] / [materializeAll]) — refreshes a cached `effectiveConfidence`
 *   so confidence-ranked reads push into the store. The *only* storage-specific operation, so it's
 *   the abstract seam: a no-op in [com.embabel.dice.proposition.store.InMemoryDecayManager] (nothing
 *   to cache), a batch write-back in the graph impl.
 *
 * [tick] is the convenience runner a scheduler calls (materialise, then lifecycle); the granular
 * operations remain independently callable for different cadences.
 *
 * dice ships no scheduler — wiring `@Scheduled`/cron is the consuming application's job.
 */
abstract class DecayManager(
    protected val repository: PropositionRepository,
) : DecaySweeper {

    /** Refresh cached decayed confidence for one context (no-op for non-caching backends). */
    abstract fun materialize(contextId: ContextId)

    /** Refresh cached decayed confidence across all contexts (no-op for non-caching backends). */
    abstract fun materializeAll()

    /** Runner: refresh cached confidence, then apply lifecycle transitions across all contexts. */
    fun tick(config: DecaySweepConfig = DecaySweepConfig()): DecaySweepResult {
        materializeAll()
        return sweepAll(config)
    }

    final override fun sweep(contextId: ContextId, config: DecaySweepConfig): DecaySweepResult =
        applyTransitions(config, "no propositions in context ${contextId.value}") {
            repository.query(PropositionQuery(contextId = contextId, statuses = config.targetStatuses))
        }

    final override fun sweepAll(config: DecaySweepConfig): DecaySweepResult =
        applyTransitions(config, "no propositions") {
            repository.query(PropositionQuery(statuses = config.targetStatuses))
        }

    private fun applyTransitions(
        config: DecaySweepConfig,
        emptyReason: String,
        candidates: () -> List<Proposition>,
    ): DecaySweepResult {
        // With no target statuses nothing can ever transition, so short-circuit before touching the
        // store — a misconfigured sweep shouldn't load everything just to hand back a NoOp. (An empty
        // set would otherwise mean "no status filter" to the query and pull the whole store back.)
        if (config.targetStatuses.isEmpty()) return DecaySweepResult.NoOp(emptyReason)
        return try {
            // The query pushes the status filter into the store; keep this in-JVM guard as the final
            // word on what counts as a target (with an empty set nothing is, exactly as before).
            val targets = candidates().filter { it.status in config.targetStatuses }
            if (targets.isEmpty()) {
                DecaySweepResult.NoOp(emptyReason)
            } else {
                val decisions = targets.map { it to config.policy.evaluate(it) }

                val revived = decisions
                    .filter { it.second == PropositionStatus.ACTIVE }
                    .map { repository.save(it.first.withStatus(PropositionStatus.ACTIVE)) }

                val toStale = decisions
                    .filter { it.second == PropositionStatus.STALE }
                    .map { it.first }

                val transitioned: List<Proposition>
                val pruned: List<Proposition>
                if (config.pruneStale) {
                    val alreadyStale = decisions
                        .filter { it.first.status == PropositionStatus.STALE && it.second != PropositionStatus.ACTIVE }
                        .map { it.first }
                    pruned = (toStale + alreadyStale).distinctBy { it.id }
                    pruned.forEach { repository.delete(it.id) }
                    transitioned = emptyList()
                } else {
                    transitioned = toStale.map { repository.save(it.withStatus(PropositionStatus.STALE)) }
                    pruned = emptyList()
                }

                val skipped = targets.size - revived.size - transitioned.size - pruned.size
                DecaySweepResult.Swept(transitioned, revived, pruned, skipped)
            }
        } catch (e: Exception) {
            DecaySweepResult.Failed(e)
        }
    }
}
