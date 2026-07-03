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

import com.embabel.dice.proposition.Proposition

/**
 * Picks the one proposition in a component that survives a merge; everyone else in the
 * component gets folded into it. Pulled out as a seam so a caller can plug in a different
 * policy later — this task ships only the default tie-break.
 */
fun interface CollectorSurvivorPolicy {
    fun choose(members: List<Proposition>): Proposition
}

/**
 * Default implementation of [CollectorSurvivorPolicy]. Uses the same tie-break dice's
 * [com.embabel.dice.projection.memory.DuplicateCollectorStrategy] and Me's dedup sweep both use:
 * highest effective confidence, then reinforcement count, then stable id comparison so a full tie
 * is still deterministic.
 */
val defaultCollectorSurvivorPolicy = CollectorSurvivorPolicy { members ->
    members.maxWith(
        compareBy<Proposition>({ it.effectiveConfidence() }, { it.reinforceCount }, { it.id }),
    )
}
