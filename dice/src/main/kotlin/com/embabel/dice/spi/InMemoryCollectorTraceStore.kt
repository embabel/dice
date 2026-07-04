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
package com.embabel.dice.spi

import com.embabel.agent.core.ContextId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A thread-safe, in-process [CollectorTraceStore] that keeps everything in concurrent maps keyed
 * by run id. Good for tests and single-node setups that don't need the trace to survive a
 * restart; a graph-backed store can implement the same interface for durability.
 *
 * The record calls don't carry a context id, but [deleteTracesForContext] needs one to know what
 * to erase. [recordRunContext] closes that gap — callers tell it once which context a run belongs
 * to, and deletion looks up every run for that context and clears its rows. A run never
 * registered with [recordRunContext] is unreachable from [deleteTracesForContext].
 */
class InMemoryCollectorTraceStore : CollectorTraceStore, CollectorTraceQuery {

    private val edgesByRun = ConcurrentHashMap<String, MutableList<CollectorCandidateEdge>>()
    private val componentsByRun = ConcurrentHashMap<String, MutableList<CollectorComponent>>()
    private val decisionsByRun = ConcurrentHashMap<String, MutableList<CollectorDecision>>()
    private val runContexts = ConcurrentHashMap<String, ContextId>()

    override fun recordRunContext(runId: String, contextId: ContextId) {
        runContexts[runId] = contextId
    }

    override fun recordCandidateEdges(runId: String, edges: List<CollectorCandidateEdge>) {
        edgesByRun.computeIfAbsent(runId) { Collections.synchronizedList(mutableListOf()) }
            .addAll(edges)
    }

    override fun recordComponents(runId: String, components: List<CollectorComponent>) {
        componentsByRun.computeIfAbsent(runId) { Collections.synchronizedList(mutableListOf()) }
            .addAll(components)
    }

    override fun recordDecision(runId: String, decision: CollectorDecision) {
        // File the decision under the run id we're told to, and stamp that same id onto the record
        // so a reader always sees the run it belongs to — no matter what the caller put on the field.
        decisionsByRun.computeIfAbsent(runId) { Collections.synchronizedList(mutableListOf()) }
            .add(decision.copy(runId = runId))
    }

    override fun deleteTracesForContext(contextId: ContextId) {
        val runIds = runContexts.filterValues { it == contextId }.keys
        for (runId in runIds) {
            edgesByRun.remove(runId)
            componentsByRun.remove(runId)
            decisionsByRun.remove(runId)
            runContexts.remove(runId)
        }
    }

    fun edgesFor(runId: String): List<CollectorCandidateEdge> = edgesByRun[runId]?.toList() ?: emptyList()

    fun componentsFor(runId: String): List<CollectorComponent> = componentsByRun[runId]?.toList() ?: emptyList()

    fun decisionsFor(runId: String): List<CollectorDecision> = decisionsByRun[runId]?.toList() ?: emptyList()

    // ---- CollectorTraceQuery ----

    override fun findEdgesByRun(runId: String): List<CollectorCandidateEdge> = edgesFor(runId)

    override fun findDecisionsByRun(runId: String): List<CollectorDecision> = decisionsFor(runId)

    /** Scans every recorded decision for one where [propositionId] survived or was folded away. */
    override fun findDecisionForProposition(propositionId: String): CollectorDecision? =
        decisionsByRun.values.asSequence().flatten().firstOrNull { decision ->
            decision.survivorId == propositionId || decision.retired.any { it.propositionId == propositionId }
        }
}
