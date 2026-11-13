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
package com.embabel.dice.evidence

import com.embabel.agent.core.ContextId
import java.util.Collections
import java.util.Objects

/**
 * What a lookup searched for: the contexts, the query text and the filters it applied.
 *
 * Each `with` method returns a copy. Contexts and filters keep the order they were added in.
 *
 * ```java
 * Searched searched = Searched.of("ctx-main")
 *     .withQuery("where does Alice live")
 *     .withFilter("minConfidence", "0.5");
 * ```
 */
class Searched private constructor(
    contextIds: Set<ContextId>,
    /** The question or search text. Null for a plain listing. */
    val query: String?,
    filters: Map<String, String>,
) {

    /** The contexts searched, in the order added. Empty for an unscoped search. */
    val contextIds: Set<ContextId> = Collections.unmodifiableSet(LinkedHashSet(contextIds))

    /** The values of [contextIds] as strings, in the same order, for Java callers. */
    val contextIdValues: Set<String> =
        Collections.unmodifiableSet(this.contextIds.mapTo(LinkedHashSet()) { it.value })

    /** Filter name to a readable value, such as `minConfidence` to `0.5`, in the order added. */
    val filters: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(filters))

    init {
        require(query == null || query.isNotBlank()) { "query must not be blank" }
        require(this.filters.keys.all { it.isNotBlank() }) { "filter names must not be blank" }
    }

    /**
     * Returns a copy with [contextId] added after the existing contexts. A context already present keeps its place.
     *
     * @param contextId a context that was searched
     * @return the updated search
     */
    fun withContext(contextId: ContextId): Searched = Searched(contextIds + contextId, query, filters)

    /**
     * Returns a copy with [contextId] added after the existing contexts. A context already present keeps its place.
     *
     * @param contextId the id of a context that was searched
     * @return the updated search
     * @throws IllegalArgumentException if [contextId] is blank
     */
    fun withContext(contextId: String): Searched = withContext(ContextId(contextId))

    /**
     * Returns a copy with [query] set.
     *
     * @param query the question or search text
     * @return the updated search
     * @throws IllegalArgumentException if [query] is blank
     */
    fun withQuery(query: String): Searched = Searched(contextIds, query, filters)

    /**
     * Returns a copy with the filter [name] set to [value]. A filter already present keeps its place.
     *
     * @param name the filter name
     * @param value a readable form of the filter value. May be empty.
     * @return the updated search
     * @throws IllegalArgumentException if [name] is blank
     */
    fun withFilter(name: String, value: String): Searched = Searched(contextIds, query, filters + (name to value))

    /** Two searches are equal when their contexts, query and filters are equal. Equality ignores order. */
    override fun equals(other: Any?): Boolean =
        other is Searched && contextIds == other.contextIds && query == other.query && filters == other.filters

    override fun hashCode(): Int = Objects.hash(contextIds, query, filters)

    /**
     * Returns the context ids, whether a query is set, and the filters with their values. The query text is
     * left out, so [Evidence.Missing] never prints a user's question.
     */
    override fun toString(): String =
        "Searched(contextIds=$contextIdValues, hasQuery=${query != null}, filters=$filters)"

    companion object {

        /**
         * Creates a search of one context, using the [ContextId] type.
         *
         * @param contextId the context searched
         * @return the search, with no query and no filters
         */
        fun of(contextId: ContextId): Searched = Searched(setOf(contextId), null, emptyMap())

        /**
         * Creates a search of one context, using the context id as a string.
         *
         * @param contextId the id of the context searched
         * @return the search, with no query and no filters
         * @throws IllegalArgumentException if [contextId] is blank
         */
        @JvmStatic
        fun of(contextId: String): Searched = of(ContextId(contextId))

        /**
         * A search that was not limited to a context.
         *
         * @return the search, with no contexts, no query and no filters
         */
        @JvmStatic
        fun unscoped(): Searched = Searched(emptySet(), null, emptyMap())
    }
}
