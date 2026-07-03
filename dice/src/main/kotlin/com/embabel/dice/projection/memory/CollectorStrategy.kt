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
import com.embabel.dice.projection.memory.collector.CollectorRunContext
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.spi.PropositionMark
import com.embabel.dice.spi.SweepPolicy

/**
 * The "mark" half of the mark-and-sweep collector: looks at candidate propositions and
 * reports which ones should be collected, and why.
 *
 * Implementations should be stateless and read-only — the same inputs should always produce
 * the same marks, and the repository should never be mutated here. Marking is a
 * read-and-report step; the [SweepPolicy] decides what actually happens to each mark.
 *
 * The repository is provided so a strategy can look up extra context it needs (e.g. finding
 * a duplicate's survivor) without having to own its own storage.
 */
fun interface CollectorStrategy {

    /**
     * Inspect [candidates] and report which should be collected.
     *
     * @param candidates The propositions under consideration (runner-selected).
     * @param repository Read access to stored propositions; do not write to it.
     * @param contextId The context the candidates belong to.
     * @return Marks for the propositions this strategy wants collected; empty if none qualify.
     */
    fun mark(
        candidates: List<Proposition>,
        repository: PropositionRepository,
        contextId: ContextId,
    ): List<PropositionMark>
}

/**
 * A [CollectorStrategy] that also wants the full [CollectorRunContext] (run id, dry-run flag,
 * clock), not just the bare context id — so it can tag whatever it writes (e.g. trace rows) with
 * the run that produced them.
 *
 * Extending [CollectorStrategy] rather than replacing it means a runner that only knows about
 * plain `CollectorStrategy`s still works: the bridge below implements the legacy SAM method by
 * building an ephemeral, blank-runId context. That path is for callers who drive the strategy
 * without ever building a real run (e.g. calling it directly, or through code that hasn't been
 * updated to pass a run context yet) — anything it writes under a blank runId isn't queryable
 * anywhere, so implementations should treat a blank runId as "don't persist a trace, just compute
 * the marks."
 */
interface RunAwareCollectorStrategy : CollectorStrategy {

    /**
     * Same contract as [CollectorStrategy.mark], but with the full run context available.
     */
    fun mark(
        candidates: List<Proposition>,
        repository: PropositionRepository,
        ctx: CollectorRunContext,
    ): List<PropositionMark>

    /** Legacy SAM bridge: driven without a run context => ephemeral (blank) runId, no trace persistence. */
    override fun mark(
        candidates: List<Proposition>,
        repository: PropositionRepository,
        contextId: ContextId,
    ): List<PropositionMark> = mark(candidates, repository, CollectorRunContext(runId = "", contextId = contextId))
}
