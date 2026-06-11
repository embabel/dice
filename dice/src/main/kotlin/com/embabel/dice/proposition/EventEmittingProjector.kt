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

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.ProjectionBatchCompleted

/**
 * Decorator that emits a [ProjectionBatchCompleted] event after every [projectAll] call.
 *
 * Wraps any [Projector] via `by delegate`. Only [projectAll] is instrumented — it runs the
 * delegate first, then fires exactly one event carrying the success/skip/failure/total counts,
 * and returns the results unchanged. [project] and all other members forward untouched.
 *
 * Throw isolation is the listener's responsibility — wrap the listener in `SafeDiceEventListener`
 * if you need graceful degradation.
 *
 * Example usage:
 * ```kotlin
 * val projector = EventEmittingProjector(
 *     delegate = graphProjector,
 *     listener = SafeDiceEventListener(myListener),
 * )
 * ```
 *
 * @param T The projection type produced by the wrapped [Projector].
 * @property delegate The underlying projector. All members except [projectAll] forward here.
 * @property listener Notified after each batch. Defaults to [DiceEventListener.DEV_NULL] (no-op).
 */
class EventEmittingProjector<T : Projection>(
    private val delegate: Projector<T>,
    private val listener: DiceEventListener = DiceEventListener.DEV_NULL,
) : Projector<T> by delegate {

    /**
     * Delegates to the wrapped projector, then emits one [ProjectionBatchCompleted] carrying
     * the success/skip/failure/total counts. Returns the results unchanged.
     *
     * @param propositions The propositions to project.
     * @param schema The data dictionary defining domain types and relationships.
     * @return The delegate's results, unmodified.
     */
    override fun projectAll(
        propositions: List<Proposition>,
        schema: DataDictionary,
    ): ProjectionResults<T> {
        val results = delegate.projectAll(propositions, schema)
        listener.onEvent(
            ProjectionBatchCompleted(
                successCount = results.successCount,
                skipCount = results.skipCount,
                failureCount = results.failureCount,
                totalCount = results.totalCount,
            )
        )
        return results
    }
}
