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
package com.embabel.dice.store

import org.jetbrains.annotations.ApiStatus
import java.util.Collections

/**
 * Thrown when the scope or the budget of a scoped read refuses something.
 * [ReadScopeViolationException] is thrown when a spec is narrowed to a context it does not allow,
 * and [ReadBudgetExceededException] when a read needs more than its budget. Nothing is returned.
 *
 * ```java
 * try {
 *     recent = reads.query(spec, query, budget);
 * } catch (ReadRefusedException refusal) {
 *     log.warn("The read was refused: {}", refusal.getMessage());
 * }
 * ```
 *
 * It is a `RuntimeException` and is not an `IllegalArgumentException`, so a handler for invalid
 * arguments does not catch a refusal.
 *
 * Subtypes are added over time. A Kotlin `when` and a Java `switch` over the subtype keep an
 * `else` or `default` branch.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @param message What was refused.
 */
@ApiStatus.Experimental
sealed class ReadRefusedException(message: String) : RuntimeException(message) {

    private companion object {
        /** Fixed, so a serialized instance stays readable when the class changes. */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Thrown by [ScopedReadSpec.narrowToContexts] when a spec is narrowed to a context it does not
 * allow.
 *
 * ```java
 * try {
 *     onlyAlice = spec.narrowToContexts(requested);
 * } catch (ReadScopeViolationException refusal) {
 *     log.warn("Refused {}. Allowed: {}", refusal.getContextIdValue(), refusal.getAllowedContextIdValues());
 * }
 * ```
 *
 * Only a spec throws it, so a store never builds one.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @param allowedContextIdValues The contexts the spec allows. A narrowed spec reports only the
 *   contexts it was narrowed to.
 * @property contextIdValue The context that was asked for and is not one of them.
 */
@ApiStatus.Experimental
class ReadScopeViolationException internal constructor(
    allowedContextIdValues: Collection<String>,
    val contextIdValue: String,
) : ReadRefusedException("Context '$contextIdValue' is not one the spec allows") {

    /** The contexts the spec allows. Read-only. */
    val allowedContextIdValues: Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(allowedContextIdValues))

    private companion object {
        /** Fixed, so a serialized instance stays readable when the class changes. */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Thrown when a read needs more than its [ReadBudget] allows. The read returns nothing.
 *
 * ```java
 * try {
 *     nearest = search.findNearest(spec, request, budget);
 * } catch (ReadBudgetExceededException refusal) {
 *     log.warn("{}: {}", refusal.getResource(), refusal.getMessage());
 * }
 * ```
 *
 * The checks of [ReadBudget] throw it, so a store calls those checks and never builds one. The
 * message says what was refused. [ReadBudgetResource] says what the caller can do for each
 * resource.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property resource What the budget did not allow.
 * @property allowed The cap of [resource]. Zero for [ReadBudgetResource.VECTOR_SEARCH].
 * @property requested What the read needed, as the store counted it. When a store stops counting
 *   one past the cap, this is one more than [allowed]. Zero for
 *   [ReadBudgetResource.VECTOR_SEARCH], which has no count.
 */
@ApiStatus.Experimental
class ReadBudgetExceededException internal constructor(
    val resource: ReadBudgetResource,
    val allowed: Int,
    val requested: Long,
) : ReadRefusedException(
    when (resource) {
        ReadBudgetResource.ROWS -> "The read needs $requested rows and its budget allows $allowed"
        ReadBudgetResource.VECTOR_CANDIDATES ->
            "The similarity search has $requested vector candidates and its budget allows $allowed"
        ReadBudgetResource.VECTOR_SEARCH ->
            "Similarity search is not enabled on this budget. " +
                "Set a vector candidate cap with withMaxVectorCandidates to enable it."
    },
) {

    private companion object {
        /** Fixed, so a serialized instance stays readable when the class changes. */
        private const val serialVersionUID: Long = 1L
    }
}
