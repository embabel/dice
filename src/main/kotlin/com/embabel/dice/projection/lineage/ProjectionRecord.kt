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
package com.embabel.dice.projection.lineage

import java.time.Instant

/**
 * A record of a single proposition projected to a single target.
 *
 * Collectively these records form an inverse index of which propositions
 * projected to which targets, allowing a projected artifact to be traced back to
 * the propositions that produced it and its current [ProjectionLifecycle] to be
 * inspected.
 *
 * @property propositionId ID of the proposition that was projected
 * @property target The projection target (e.g. "graph", "prolog", "report")
 * @property targetRef Optional reference to the produced/adopted artifact in the target
 *   (e.g. a node ID), or null when none applies
 * @property lifecycle Lifecycle state of this projection
 * @property runId ID of the projection run that produced this record
 * @property at When this record was created
 * @property reason Optional explanation (e.g. skip/failure reason)
 */
data class ProjectionRecord @JvmOverloads constructor(
    val propositionId: String,
    val target: String,
    val targetRef: String? = null,
    val lifecycle: ProjectionLifecycle,
    val runId: String,
    val at: Instant = Instant.now(),
    val reason: String? = null,
) {

    init {
        require(propositionId.isNotBlank()) { "propositionId must not be blank" }
        require(target.isNotBlank()) { "target must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
    }

    companion object {

        /**
         * Java-friendly factory method to create a [ProjectionRecord].
         *
         * @param propositionId ID of the projected proposition
         * @param target The projection target
         * @param lifecycle Lifecycle state of this projection
         * @param runId ID of the projection run
         * @param targetRef Optional reference to the produced/adopted artifact
         * @param at When this record was created
         * @param reason Optional explanation
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            propositionId: String,
            target: String,
            lifecycle: ProjectionLifecycle,
            runId: String,
            targetRef: String? = null,
            at: Instant = Instant.now(),
            reason: String? = null,
        ): ProjectionRecord = ProjectionRecord(
            propositionId = propositionId,
            target = target,
            targetRef = targetRef,
            lifecycle = lifecycle,
            runId = runId,
            at = at,
            reason = reason,
        )
    }
}
