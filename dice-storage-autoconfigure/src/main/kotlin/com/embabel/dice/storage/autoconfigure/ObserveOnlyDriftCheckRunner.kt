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
 * A [DriftCheckRunner] that will only ever observe. Checks run, reports are written, and no
 * proposition is ever touched — asking for a live run gets you a dry one instead.
 *
 * This is what `embabel.dice.metamodel.drift.mode=observe` wires, and it exists so the observe tier
 * is a property of the wiring rather than a habit callers have to keep. A dry-run *default* only
 * protects the caller who never passes an argument; a scheduler, an admin endpoint, or a stray
 * `run(dryRun = false)` in a script all reach past it. Wrapping the real runner means the guarantee
 * holds no matter who calls or what they pass, and switching the tier is a config change rather
 * than a code change.
 *
 * The downgrade is loud but not fatal. Throwing would take down a scheduled job for asking a
 * reasonable question in the wrong environment; instead the call succeeds, the report is written,
 * and the answer says plainly what happened — [DriftCheckResult.dryRun] comes back `true` and
 * [DriftCheckResult.quarantinedCount] is `0`, so a caller that cares can tell it was downgraded
 * without reading the log.
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
