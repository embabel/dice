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

import com.embabel.dice.proposition.PropositionQuery
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.function.UnaryOperator

/**
 * Which propositions a scoped read may return: the ones in a [ContextReadScope] that match every
 * filter added with [narrow]. A host builds a spec and hands it to the code that reads.
 *
 * ```java
 * ScopedReadSpec spec = ScopedReadSpec
 *     .of(ContextReadScope.ofValues("alice-session", "team-handbook"))
 *     .narrow(filter -> filter.withStatus(PropositionStatus.ACTIVE).withMinConfidence(0.6));
 * ```
 *
 * ```kotlin
 * val spec = ScopedReadSpec.of(scope).narrow(PropositionQuery(minConfidence = 0.6))
 * ```
 *
 * No operation on a spec gives a wider spec. [narrow] returns a spec with one more filter and
 * every filter the spec already had. No operation adds a context, removes a filter or replaces
 * the scope. A spec is a plain value. Code that holds a scope can build a new spec over it.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property scope The contexts a read under this spec may touch.
 */
@ApiStatus.Experimental
class ScopedReadSpec private constructor(
    val scope: ContextReadScope,
    filters: List<PropositionQuery>,
) {

    /** The filters, in the order added. A proposition is returned only when it matches each one. Read-only. */
    val filters: List<PropositionQuery> = Collections.unmodifiableList(ArrayList(filters))

    /**
     * Adds a filter. A filter that names a context narrows the spec to that context. A filter
     * that names none applies in every context the spec allows. Each filter is applied whole,
     * with its own `decayK`.
     *
     * @param filter Criteria every returned proposition must also match.
     * @return A spec with this filter and the ones this spec has.
     * @throws ReadScopeViolationException when [filter] names a context outside the scope, or a
     *   context other than the one a filter of this spec names.
     * @throws IllegalArgumentException when [filter] carries an ordering, a limit or
     *   `effectiveConfidenceAsOf`. The read sets all three.
     */
    fun narrow(filter: PropositionQuery): ScopedReadSpec {
        val copy = requireNarrowingFilter(filter)
        require(copy.effectiveConfidenceAsOf == null) {
            "A spec filter must not carry effectiveConfidenceAsOf (${copy.effectiveConfidenceAsOf}). The read sets it."
        }
        requireAllowed(copy)
        return ScopedReadSpec(scope, filters + copy)
    }

    /**
     * Adds the filter a function builds from a query that sets no criteria. Java writes the
     * function as a lambda and never holds the empty query.
     *
     * @param narrowing Receives a query with no criteria and returns the filter to add.
     * @return A spec with that filter and the ones this spec has.
     * @throws ReadScopeViolationException for the filters the other form refuses.
     * @throws IllegalArgumentException for the filters the other form rejects.
     */
    fun narrow(narrowing: UnaryOperator<PropositionQuery>): ScopedReadSpec = narrow(narrowing.apply(PropositionQuery()))

    /**
     * Checks one query against this spec. A store calls it with the query of each read, or the
     * filter of a similarity search request, before it reads. Passing selects nothing: the store
     * still selects by the scope and by every filter.
     *
     * @param query The query of a read, or a filter about to be added.
     * @throws ReadScopeViolationException when [query] names a context outside the scope, or a
     *   context other than the one a filter of this spec names.
     */
    fun requireAllowed(query: PropositionQuery) {
        val named = query.contextId?.value ?: return
        if (!scope.containsValue(named)) throw ReadScopeViolationException(scope.contextIdValues, named)
        val narrowedTo = filters.firstNotNullOfOrNull { it.contextId?.value }
        if (narrowedTo != null && narrowedTo != named) throw ReadScopeViolationException(setOf(narrowedTo), named)
    }

    override fun equals(other: Any?): Boolean =
        other is ScopedReadSpec && other.scope == scope && other.filters == filters

    override fun hashCode(): Int = 31 * scope.hashCode() + filters.hashCode()

    override fun toString(): String = "ScopedReadSpec(scope=$scope, filters=$filters)"

    companion object {

        /**
         * @param scope The contexts a read may touch.
         * @return A spec over [scope] with no filter.
         */
        @JvmStatic
        fun of(scope: ContextReadScope): ScopedReadSpec = ScopedReadSpec(scope, emptyList())

        /**
         * A query that sets no criteria, to chain `with` calls on. Use it as the query of a
         * scoped read, or as a filter for [narrow]. It names no context. Passed to a method that
         * takes no spec, it matches every context in the store. Kotlin can write
         * `PropositionQuery()`.
         *
         * @return The query with no criteria.
         */
        @JvmStatic
        fun criteria(): PropositionQuery = PropositionQuery()
    }
}
