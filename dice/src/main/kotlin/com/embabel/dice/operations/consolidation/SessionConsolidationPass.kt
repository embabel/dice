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
package com.embabel.dice.operations.consolidation

import com.embabel.agent.core.ContextId
import com.embabel.dice.projection.memory.MemoryConsolidator
import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory

/**
 * Consolidation pass that folds a session's propositions into long-term memory by delegating
 * verbatim to an injected [MemoryConsolidator].
 *
 * This pass does NOT re-implement promote/reinforce/merge logic — it hands the session and the
 * snapshot (the existing long-term ACTIVE set) to the consolidator and projects the resulting
 * [com.embabel.dice.projection.memory.ConsolidationResult] into a [ConsolidationPassResult].
 *
 * The session propositions are supplied at construction (one pass instance per cycle) because the
 * [run] snapshot is the long-term set the session is consolidated against, not the session itself.
 *
 * @property consolidator The delegate that decides what to promote, reinforce, merge, or discard.
 * @property sessionPropositions The current session's propositions to consolidate into long-term memory.
 */
class SessionConsolidationPass @JvmOverloads constructor(
    private val consolidator: MemoryConsolidator,
    private val sessionPropositions: List<Proposition> = emptyList(),
) : ConsolidationPass {

    override val name: String = "session-consolidation"

    private val logger = LoggerFactory.getLogger(SessionConsolidationPass::class.java)

    override fun run(contextId: ContextId, propositions: List<Proposition>): ConsolidationPassResult {
        return try {
            val result = consolidator.consolidate(sessionPropositions, propositions)
            val toSave = result.promoted + result.reinforced + result.merged.map { it.result }
            logger.debug(
                "Session consolidation for {}: {} session vs {} long-term proposition(s) -> {} promoted, {} reinforced, {} merged, {} discarded",
                contextId, sessionPropositions.size, propositions.size,
                result.promoted.size, result.reinforced.size, result.merged.size, result.discarded.size,
            )
            if (toSave.isEmpty()) {
                ConsolidationPassResult.NoOp(name, "nothing to promote, reinforce, or merge")
            } else {
                ConsolidationPassResult.Changed(
                    passName = name,
                    propositionsToSave = toSave,
                    summary = "consolidated session: ${result.promoted.size} promoted, " +
                        "${result.reinforced.size} reinforced, ${result.merged.size} merged, " +
                        "${result.discarded.size} discarded",
                )
            }
        } catch (e: Throwable) {
            ConsolidationPassResult.Failed(name, e)
        }
    }
}
