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
package com.embabel.dice.storage.autoconfigure

import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DriftCheckResult
import com.embabel.dice.metamodel.DriftCheckRunner
import org.slf4j.LoggerFactory

/**
 * A [DriftCheckRunner] that only observes. Checks run and reports are written; a request for a live
 * run is downgraded to a dry one.
 *
 * This is what `embabel.dice.metamodel.drift.mode=observe` wires, so the observe tier is enforced by
 * the wiring. A dry-run default on the method only protects the caller who passes no argument, and a
 * scheduler, an admin endpoint, or a stray `run(dryRun = false)` in a script all reach past it.
 * Wrapping the real runner holds the guarantee whoever calls and whatever they pass, and switching
 * the tier is then a config change.
 *
 * The downgrade logs a warning and returns normally. Throwing would take down a scheduled job for
 * asking a reasonable question in the wrong environment. The report is written,
 * [DriftCheckResult.dryRun] comes back `true` and [DriftCheckResult.quarantinedCount] is `0`, so a
 * caller can tell it was downgraded without reading the log.
 *
 * @param delegate The real runner, which decides everything except whether the run is live.
 */
internal class ObserveOnlyDriftCheckRunner(
    private val delegate: DriftCheckRunner,
) : DriftCheckRunner {

    private val logger = LoggerFactory.getLogger(ObserveOnlyDriftCheckRunner::class.java)

    override fun run(dryRun: Boolean, contextId: ContextId?): DriftCheckResult {
        if (!dryRun) {
            logger.warn(
                "Live drift check requested but the drift mode is 'observe'; running dry instead. " +
                    "Set embabel.dice.metamodel.drift.mode=quarantine to allow quarantining.",
            )
        }
        return delegate.run(dryRun = true, contextId = contextId)
    }
}
