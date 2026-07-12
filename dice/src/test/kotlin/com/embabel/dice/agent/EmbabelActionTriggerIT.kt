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
package com.embabel.dice.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.ContextId
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.dice.common.PropositionPersisted
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * A consumer agent reacts to a DICE [PropositionPersisted] event placed on the blackboard via an
 * Embabel `@Action(trigger = PropositionPersisted::class)`. This pins the integration contract DICE
 * relies on: persisting a proposition is the signal other agents plan against.
 *
 * The flow is deterministic and needs no LLM — the agent is built straight from the annotated bean
 * with [AgentMetadataReader], the event is dropped on the blackboard as the last result, and the
 * GOAP planner runs the single triggered action in-process.
 */
class EmbabelActionTriggerIT {

    /** Observable side-effect target so a fired action is detectable. */
    class TriggerRecorder {
        @Volatile
        var fired: Boolean = false
    }

    /** Output binding marker produced by the triggered action. */
    data class ReactionMarker(val propositionId: String)

    /**
     * Tiny test agent whose single action fires when a [PropositionPersisted] event is the last
     * result on the blackboard. `@AchievesGoal` gives the GOAP planner a goal to plan toward; the
     * trigger precondition is excluded from the goal, so the goal stays achievable once the action
     * runs.
     */
    @Agent(description = "Test agent reacting to DICE PropositionPersisted events")
    class PropositionPersistedReactor(private val recorder: TriggerRecorder) {

        @Action(trigger = PropositionPersisted::class)
        @AchievesGoal(description = "React to a persisted proposition")
        fun onPropositionPersisted(event: PropositionPersisted): ReactionMarker {
            recorder.fired = true
            return ReactionMarker(event.proposition.id)
        }
    }

    private fun samplePropositionPersisted(): PropositionPersisted = PropositionPersisted(
        Proposition(
            contextId = ContextId("test-context"),
            text = "Jim is an expert in GOAP",
            mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        ),
    )

    @Test
    fun `consumer @Action(trigger = PropositionPersisted) fires when the event is on the blackboard`() {
        val recorder = TriggerRecorder()
        val agent = AgentMetadataReader().createAgentMetadata(PropositionPersistedReactor(recorder)) as CoreAgent

        val process = IntegrationTestUtils.dummyAgentProcessRunning(agent)
        // Place the event as the blackboard's last result so the trigger precondition is satisfied.
        process.addObject(samplePropositionPersisted())
        process.run()

        assertTrue(recorder.fired, "the action should fire when PropositionPersisted is on the blackboard")
    }
}
