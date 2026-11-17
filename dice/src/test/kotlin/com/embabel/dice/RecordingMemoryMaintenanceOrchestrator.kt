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
package com.embabel.dice

import com.embabel.agent.core.ContextId
import com.embabel.dice.projection.memory.MaintenanceResult
import com.embabel.dice.projection.memory.MemoryMaintenanceOrchestrator
import com.embabel.dice.proposition.Proposition

/**
 * A stand-in orchestrator that writes down every call it gets instead of doing any work.
 *
 * Java cannot override the core `maintain` method, because its parameter is a [ContextId]
 * and Kotlin mangles the name. So this fake is written in Kotlin, and the Java tests call
 * the default `maintainValue` methods on it.
 *
 * @property calls The context value and session propositions of each `maintain` call, in order.
 * @property result The value every call hands back.
 */
class RecordingMemoryMaintenanceOrchestrator : MemoryMaintenanceOrchestrator {

    val calls: MutableList<Pair<String, List<Proposition>>> = mutableListOf()

    val result: MaintenanceResult = MaintenanceResult(
        consolidation = null,
        abstractions = emptyList(),
        superseded = emptyList(),
        retired = emptyList(),
    )

    override fun maintain(
        contextId: ContextId,
        sessionPropositions: List<Proposition>,
    ): MaintenanceResult {
        calls += contextId.value to sessionPropositions
        return result
    }
}
