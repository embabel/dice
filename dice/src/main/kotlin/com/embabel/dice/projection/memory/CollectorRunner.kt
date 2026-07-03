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
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.spi.MergingSweepPolicy
import com.embabel.dice.spi.StatusTransitionSweepPolicy
import com.embabel.dice.spi.SweepPolicy

/**
 * Runs the mark-and-sweep collector for a context: selects ACTIVE propositions, hands them to
 * one or more [CollectorStrategy]s for marking, then asks the [SweepPolicy] what to do with
 * each marked one.
 *
 * Two entry points with different write behavior:
 * - [collect] is mark-only — purely in-memory, never writes a thing.
 * - [run] is mark + sweep — `dryRun = true` previews the outcome and saves an audit record;
 *   `dryRun = false` applies the decisions for real.
 *
 * Build one via the fluent [withRepository] entry point.
 */
interface CollectorRunner {

    /**
     * Run the mark phase only, leaving everything untouched.
     *
     * No repository writes, no run record. Good for inspecting what a sweep would do before
     * committing. Because nothing is persisted, the returned [CollectorRunResult.runId] is
     * blank — don't look it up in a record store.
     *
     * @param contextId The context whose ACTIVE propositions are evaluated.
     * @return A result with marks populated but applied/skipped partitions empty, and a blank runId.
     */
    fun collect(contextId: ContextId): CollectorRunResult

    /**
     * Run the full mark + sweep.
     *
     * Either way, an auditable run record is saved. With `dryRun = true`, no proposition status
     * changes and no lifecycle event is emitted. With `dryRun = false`, each decision is applied
     * and a [com.embabel.dice.common.PropositionStatusChanged] event is emitted per transition.
     *
     * @param contextId The context whose ACTIVE propositions are evaluated.
     * @param dryRun When true, preview only — no proposition changes, no events.
     * @return A result partitioned into marks/applied/skipped/hardDeleted.
     */
    fun run(contextId: ContextId, dryRun: Boolean = false): CollectorRunResult

    companion object {

        /**
         * Start building a [CollectorRunner] backed by [repository].
         *
         * @param repository The proposition store to read candidates from and write transitions to.
         * @return A [Builder] with defaults: no strategies, [MergingSweepPolicy], no record store,
         *   and a no-op event listener.
         */
        @JvmStatic
        fun withRepository(repository: PropositionRepository): Builder = Builder(repository)
    }

    /**
     * Fluent builder for a [DefaultCollectorRunner].
     *
     * Defaults: empty strategy list, [MergingSweepPolicy], no record store (audit off),
     * and [DiceEventListener.DEV_NULL] (events off).
     *
     * The default policy merges rather than plain-flips: a duplicate mark that names a survivor
     * has the loser's grounding and provenance folded onto that survivor before the loser is
     * retired. For every other mark it behaves exactly like [StatusTransitionSweepPolicy], which
     * remains available via [withPolicy] for deployments that want pure status flips even on
     * duplicates.
     */
    class Builder internal constructor(private val repository: PropositionRepository) {

        private val strategies = mutableListOf<CollectorStrategy>()
        private var policy: SweepPolicy = MergingSweepPolicy()
        private var recordStore: CollectorRecordStore? = null
        private var listener: DiceEventListener = DiceEventListener.DEV_NULL

        /**
         * Add a strategy whose marks contribute to the sweep.
         *
         * @param strategy The strategy to run during the mark phase.
         * @return this builder, for chaining.
         */
        fun withStrategy(strategy: CollectorStrategy): Builder = apply { strategies.add(strategy) }

        /**
         * Configure the near-duplicate dedup sweep by adding a [DuplicateCollectorStrategy].
         *
         * Merge-on-collapse comes from the builder's default [MergingSweepPolicy], so a duplicate
         * loser has its grounding and provenance folded onto the survivor before it is retired —
         * whether the strategy was added here or via plain [withStrategy]. If a mark in the same
         * run retires the survivor, the merged evidence still wins: the runner applies merges
         * first and later transitions read the merged state.
         *
         * @param strategy The duplicate-detection strategy (defaults to a [DuplicateCollectorStrategy]
         *   with the repository-matching thresholds).
         * @return this builder, for chaining.
         */
        fun withDuplicateDetection(
            strategy: DuplicateCollectorStrategy = DuplicateCollectorStrategy(),
        ): Builder = apply { strategies.add(strategy) }

        /**
         * Set the policy that decides each marked proposition's fate.
         *
         * @param policy The sweep policy (defaults to [MergingSweepPolicy]; pass
         *   [StatusTransitionSweepPolicy] for pure status flips with no evidence merge).
         * @return this builder, for chaining.
         */
        fun withPolicy(policy: SweepPolicy): Builder = apply { this.policy = policy }

        /**
         * Attach an audit store so run records are persisted. Without this, no trail is written.
         *
         * @param recordStore The store collector records are appended to.
         * @return this builder, for chaining.
         */
        fun withRecordStore(recordStore: CollectorRecordStore): Builder = apply { this.recordStore = recordStore }

        /**
         * Attach a listener to receive events after each applied transition.
         *
         * @param listener The event listener (defaults to [DiceEventListener.DEV_NULL]).
         * @return this builder, for chaining.
         */
        fun withEventListener(listener: DiceEventListener): Builder = apply { this.listener = listener }

        /**
         * @return a [DefaultCollectorRunner] from this builder's configuration.
         */
        fun build(): CollectorRunner =
            DefaultCollectorRunner(repository, strategies.toList(), policy, recordStore, listener)
    }
}
