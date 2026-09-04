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

import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory

/**
 * Listens for proposition status changes and marks the corresponding projection records stale.
 *
 * When a proposition moves to a terminal status (SUPERSEDED, CONTRADICTED, STALE, or QUARANTINED),
 * every [ProjectionRecord] derived from it is flipped to [ProjectionLifecycle.STALE] in the
 * [recordStore]. Non-terminal transitions (ACTIVE, PROMOTED) are ignored, as is any event
 * type other than [PropositionStatusChanged].
 *
 * QUARANTINED belongs in that list for the same reason the other three do: the proposition has left
 * ordinary use, so anything projected from it is no longer backed by a live belief. A release moves
 * it back to a non-terminal status and fires its own event, which this cascade ignores; re-deriving
 * the projection is the projector's job either way.
 *
 * Wire this up alongside your collector — either directly or as part of a composite listener.
 * Wrapping it in a safe listener is a good idea so a fault here can't abort the sweep that
 * fired the event.
 *
 * @property recordStore The lineage store whose records are transitioned to STALE.
 */
class ProjectionLineageStaleCascade(
    private val recordStore: ProjectionRecordStore,
) : DiceEventListener {

    private val logger = LoggerFactory.getLogger(ProjectionLineageStaleCascade::class.java)

    override fun onEvent(event: DiceEvent) {
        if (event is PropositionStatusChanged && event.newStatus in TERMINAL_STATUSES) {
            val updated = recordStore.markStaleByProposition(event.proposition.id)
            logger.debug(
                "Stale cascade for proposition {} ({}→{}): {} record(s) marked stale",
                event.proposition.id.take(8), event.previousStatus, event.newStatus, updated,
            )
        }
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(
            PropositionStatus.SUPERSEDED,
            PropositionStatus.CONTRADICTED,
            PropositionStatus.STALE,
            PropositionStatus.QUARANTINED,
        )
    }
}
