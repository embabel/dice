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

import com.embabel.agent.core.ContextId
import org.jetbrains.annotations.ApiStatus
import java.util.Collections

/**
 * The contexts one read may touch. A host builds it from its own access rules. It always names at
 * least one context, and a scoped read never returns a proposition from a context it does not name.
 *
 * ```java
 * ContextReadScope scope = ContextReadScope.ofValues("alice-session", "team-handbook");
 * ```
 *
 * Ids are compared exactly, so case and whitespace inside an id count. No id stands for every
 * context.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
class ContextReadScope private constructor(contextIdValues: Collection<String>) {

    /** The context ids, in the order given, with no duplicates. Read-only. */
    val contextIdValues: Set<String> = Collections.unmodifiableSet(LinkedHashSet(contextIdValues))

    /**
     * @param contextIdValue A context id.
     * @return Whether this scope names it.
     */
    fun containsValue(contextIdValue: String): Boolean = contextIdValue in contextIdValues

    /**
     * Whether this scope names a context, for Kotlin's `in`. Java uses [containsValue].
     *
     * @param contextId A context id.
     * @return Whether this scope names it.
     */
    @JvmSynthetic
    operator fun contains(contextId: ContextId): Boolean = containsValue(contextId.value)

    override fun equals(other: Any?): Boolean = other is ContextReadScope && other.contextIdValues == contextIdValues

    override fun hashCode(): Int = contextIdValues.hashCode()

    override fun toString(): String = "ContextReadScope($contextIdValues)"

    companion object {

        /**
         * @param contextIdValues The ids of the contexts a read may touch.
         * @return The scope.
         * @throws IllegalArgumentException when [contextIdValues] is empty or holds a null or
         *   blank id.
         */
        @JvmStatic
        fun ofValues(contextIdValues: Collection<String>): ContextReadScope {
            // A Java caller can pass a collection that holds null.
            val ids: List<String?> = contextIdValues.toList()
            require(ids.isNotEmpty()) { "A read scope names at least one context" }
            require(ids.none { it.isNullOrBlank() }) { "A context id in a read scope must not be null or blank" }
            return ContextReadScope(ids.filterNotNull())
        }

        /**
         * @param first The id of a context a read may touch.
         * @param more The ids of further contexts.
         * @return The scope.
         * @throws IllegalArgumentException when an id is blank.
         */
        @JvmStatic
        fun ofValues(first: String, vararg more: String): ContextReadScope = ofValues(listOf(first, *more))

        /**
         * The scope from typed ids. Java uses [ofValues].
         *
         * @param contextIds The contexts a read may touch.
         * @return The scope.
         * @throws IllegalArgumentException when [contextIds] is empty or holds a blank id.
         */
        @JvmSynthetic
        fun of(contextIds: Collection<ContextId>): ContextReadScope = ofValues(contextIds.map { it.value })
    }
}

/**
 * Thrown when a filter or a read names a context it may not touch. Nothing is read.
 *
 * ```java
 * try {
 *     readUnder(spec);
 * } catch (ReadScopeViolationException refusal) {
 *     log.warn("Refused {}. Allowed: {}", refusal.getContextIdValue(), refusal.getAllowedContextIdValues());
 * }
 * ```
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @param allowedContextIdValues The contexts the filter or read was held to.
 * @property contextIdValue The context it named that is not one of them.
 */
@ApiStatus.Experimental
class ReadScopeViolationException(
    allowedContextIdValues: Collection<String>,
    val contextIdValue: String,
) : IllegalArgumentException("Context '$contextIdValue' is not one this read may touch") {

    /** The contexts the filter or read was held to. Read-only. */
    val allowedContextIdValues: Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(allowedContextIdValues))

    private companion object {
        /** Fixed, so a serialized instance stays readable when the class changes. */
        private const val serialVersionUID: Long = 1L
    }
}
