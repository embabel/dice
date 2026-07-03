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
package com.embabel.dice.projection.memory.collector

import com.embabel.agent.core.ContextId
import java.time.Clock

/**
 * Everything one run of the multi-signal collector needs beyond the candidate propositions
 * themselves: a run id to tag its trace rows with, which context it's sweeping, whether it
 * should hold off on anything that isn't read-only, and a clock so tests can pin "now".
 *
 * A runner builds this once per sweep; tests can build one directly to exercise the strategy
 * without a runner at all.
 *
 * @property runId Identifies this run in the trace store — every edge, component and decision
 *   recorded during the run is tagged with it.
 * @property contextId The context being swept.
 * @property dryRun When true, the strategy should still compute and record its full trace but
 *   must not have any side effect beyond that (no marks are a "commitment" either way — this
 *   flag is here for callers that want to preview a run's trace before acting on it).
 * @property clock Source of "now" for anything the run needs to timestamp. Defaults to the
 *   system clock; tests can supply a fixed one.
 */
data class CollectorRunContext(
    val runId: String,
    val contextId: ContextId,
    val dryRun: Boolean = false,
    val clock: Clock = Clock.systemUTC(),
)
