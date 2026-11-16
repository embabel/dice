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
import java.util.function.UnaryOperator

/**
 * Which propositions a scoped read may return: the ones in the contexts of [scope] that meet
 * every filter added with [narrow]. A host builds a spec from a [ContextReadScope] and hands it
 * to the code that reads.
 *
 * ```java
 * ScopedReadSpec spec = ScopedReadSpec
 *     .of(ContextReadScope.ofValues("alice-session", "team-handbook"))
 *     .narrow(criteria -> criteria.withStatus(PropositionStatus.ACTIVE).withMinConfidence(0.6));
 * ScopedReadSpec aliceOnly = spec.narrowToContexts("alice-session");
 * ```
 *
 * ```kotlin
 * val spec = ScopedReadSpec.of(scope).narrow(ReadCriteria { minConfidence = 0.6 })
 * ```
 *
 * What narrowing guarantees. No method of a spec returns a wider spec. Each returns a spec that
 * allows the same contexts or fewer and has every filter this one has. A narrowed spec does not
 * hold or report the contexts it was narrowed from. Its [scope], its text form and a refusal it
 * throws name only the contexts it allows now.
 *
 * What it does not guarantee. A spec is a value in the same process. Its scope and its filters
 * are readable, because a store needs them, and the factories are public. Code that holds a spec
 * can call `ScopedReadSpec.of(spec.getScope())` and get a spec over the same contexts with no
 * filter. Code that knows a context id can build a scope over it. The filters therefore bind
 * only code that passes the spec on unchanged or narrowed.
 *
 * Who is trusted. The host decides which contexts and which filters a caller gets. It builds the
 * scope, the first spec and the [ReadBudget]. Code that is handed a spec is trusted to pass that
 * spec, or one narrowed from it, to every read it makes. A store applies the spec it is given
 * and does not decide access. The host resolves `ScopedReads` and `ScopedVectorSearch` with
 * `StoreCapabilities.find` and hands over only that handle. The handle is the store object, so
 * code that casts it to `PropositionStore` can read outside any spec.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property scope The contexts a read under this spec may touch. A narrowed spec has a scope of
 *   only the contexts it was narrowed to.
 */
@ApiStatus.Experimental
class ScopedReadSpec private constructor(
    val scope: ContextReadScope,
    filters: List<ReadCriteria>,
) {

    /** The filters, in the order added. A proposition is returned only when it meets each one. Read-only. */
    val filters: List<ReadCriteria> = Collections.unmodifiableList(ArrayList(filters))

    /**
     * Adds a filter. Each filter is applied whole, with its own `decayK`.
     *
     * @param criteria Criteria every returned proposition must also meet.
     * @return A spec with this filter, the filters this spec has and the same contexts. This
     *   spec itself when [criteria] set nothing.
     * @throws IllegalArgumentException when [criteria] carry `effectiveConfidenceAsOf`. The
     *   criteria of a read set it.
     */
    fun narrow(criteria: ReadCriteria): ScopedReadSpec {
        require(criteria.effectiveConfidenceAsOf == null) {
            "A spec filter must not carry effectiveConfidenceAsOf (${criteria.effectiveConfidenceAsOf}). " +
                "The criteria of a read set it."
        }
        return if (criteria.specified.isEmpty()) this else ScopedReadSpec(scope, filters + criteria)
    }

    /**
     * Adds the filter a function builds from criteria that set nothing. Java writes the function
     * as a lambda.
     *
     * @param narrowing Receives empty criteria and returns the filter to add.
     * @return A spec with that filter, the filters this spec has and the same contexts. This
     *   spec itself when the function sets nothing.
     * @throws NullPointerException when [narrowing] returns null. The message says so.
     * @throws IllegalArgumentException for the filters the other form rejects.
     */
    fun narrow(narrowing: UnaryOperator<ReadCriteria>): ScopedReadSpec {
        val added: ReadCriteria? = narrowing.apply(ReadCriteria.empty())
        if (added == null) {
            throw NullPointerException("The narrowing function returned null. It must return the criteria to add.")
        }
        return narrow(added)
    }

    /**
     * Keeps only some of the contexts this spec allows.
     *
     * @param contextIdValues The ids of the contexts to keep.
     * @return A spec that allows only those contexts and has the filters this spec has.
     * @throws ReadScopeViolationException when an id is not one this spec allows. It carries the
     *   contexts this spec allows.
     * @throws IllegalArgumentException when [contextIdValues] is empty or holds a null or blank
     *   id.
     */
    fun narrowToContexts(contextIdValues: Collection<String>): ScopedReadSpec {
        val kept = ContextReadScope.ofValues(contextIdValues)
        val refused = kept.contextIdValues.firstOrNull { !scope.containsValue(it) }
        if (refused != null) throw ReadScopeViolationException(scope.contextIdValues, refused)
        return ScopedReadSpec(kept, filters)
    }

    /**
     * Keeps only some of the contexts this spec allows.
     *
     * @param first The id of a context to keep.
     * @param more The ids of further contexts to keep.
     * @return A spec that allows only those contexts and has the filters this spec has.
     * @throws ReadScopeViolationException when an id is not one this spec allows. It carries the
     *   contexts this spec allows.
     * @throws IllegalArgumentException when an id is blank, or an id in [more] is null.
     * @throws NullPointerException when [first] is null.
     */
    fun narrowToContexts(first: String, vararg more: String): ScopedReadSpec = narrowToContexts(listOf(first, *more))

    /**
     * Checks that a store applies every criterion that every filter sets. A store calls it first
     * in every scoped read, before it checks the request.
     *
     * ```java
     * spec.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_READS);
     * ```
     *
     * @param supportedCriteria The criteria the store applies.
     * @param capability The capability the read belongs to, carried by the exception.
     * @throws UnsupportedCapabilityException naming the first criterion of the first filter that
     *   is not in [supportedCriteria].
     */
    fun requireOnly(supportedCriteria: Set<ReadCriterion>, capability: StoreCapability) {
        filters.forEach { it.requireOnly(supportedCriteria, capability) }
    }

    override fun equals(other: Any?): Boolean =
        other is ScopedReadSpec && other.scope == scope && other.filters == filters

    override fun hashCode(): Int = 31 * scope.hashCode() + filters.hashCode()

    override fun toString(): String = "ScopedReadSpec(scope=$scope, filters=$filters)"

    companion object {

        /**
         * @param scope The contexts a read may touch.
         * @return A spec that allows the contexts of [scope] and has no filter.
         */
        @JvmStatic
        fun of(scope: ContextReadScope): ScopedReadSpec = ScopedReadSpec(scope, emptyList())
    }
}
