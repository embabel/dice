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
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.ProjectionBatchCompleted
import com.embabel.dice.common.RecordingDiceEventListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Contract for the projection-boundary decorator.
 *
 * Wraps a [Projector] (`by delegate`) and emits exactly one [ProjectionBatchCompleted]
 * after `projectAll`, carrying the result counts from [ProjectionResults].
 */
class EventEmittingProjectorTest {

    private val contextId = ContextId("test-context")
    private val schema = DataDictionary.fromClasses("test")

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )

    /** Test projection type. */
    private data class StubProjection(
        override val sourcePropositionIds: List<String>,
        override val confidence: Double = 1.0,
        override val decay: Double = 0.0,
    ) : Projection

    /**
     * Stub projector whose `project` outcome is driven by the proposition text:
     * "ok" -> success, "skip" -> skipped, anything else -> failed. This lets a test
     * construct a known (success, skip, failure) count distribution.
     */
    private inner class StubProjector : Projector<StubProjection> {
        override fun project(proposition: Proposition, schema: DataDictionary): ProjectionResult<StubProjection> =
            when (proposition.text) {
                "ok" -> ProjectionSuccess(proposition, StubProjection(listOf(proposition.id)))
                "skip" -> ProjectionSkipped(proposition, "skipped for test")
                else -> ProjectionFailed(proposition, "failed for test")
            }
    }

    @Test
    fun `projectAll emits exactly one ProjectionBatchCompleted carrying the counts`() {
        val recording = RecordingDiceEventListener()
        val projector = EventEmittingProjector(StubProjector(), recording)

        val propositions = listOf(
            proposition("ok"),
            proposition("ok"),
            proposition("ok"),
            proposition("skip"),
            proposition("fail"),
            proposition("fail"),
        )

        projector.projectAll(propositions, schema)

        val emitted = recording.eventsOfType<ProjectionBatchCompleted>()
        assertEquals(1, emitted.size, "exactly one ProjectionBatchCompleted after projectAll")
        val batch = emitted.first()
        assertEquals(3, batch.successCount)
        assertEquals(1, batch.skipCount)
        assertEquals(2, batch.failureCount)
        assertEquals(6, batch.totalCount)
    }
}
