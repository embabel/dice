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
package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.store.ContextReadScope
import org.jetbrains.annotations.ApiStatus

/**
 * Finds the existing entities a suggested entity may refer to, among the entities of a
 * [ContextReadScope]. The scope is a required argument, so a search cannot be written without one.
 *
 * ```java
 * ScopedCandidateSearcher searcher = (suggested, schema, scope) -> SearchResult.empty();
 * SearchResult found = searcher.search(suggested, schema, ContextReadScope.ofValues("alice-session"));
 * ```
 *
 * An implementation keeps these rules:
 *
 * 1. It returns only entities that belong to a context in the scope. An entity belongs to a
 *    context only if a reader of that context may see it. The implementation's documentation
 *    says how it decides that.
 * 2. It applies the scope before it ranks entities or cuts them to a result size.
 * 3. A searcher that cannot hold a search to the scope throws [UnsupportedOperationException].
 *    One that can tell when it is built fails there. It never searches every context.
 *
 * To resolve a mention that will be written, pass a scope that names only the context being
 * written to. An entity returned from another context is linked into that context, and its
 * readers then see the entity's name and description.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
interface ScopedCandidateSearcher {

    /** Which [ResolutionLevel] a confident match from this searcher counts as. */
    val resolutionLevel: ResolutionLevel
        get() = ResolutionLevel.HEURISTIC_MATCH

    /**
     * @param suggested The entity to find matches for.
     * @param schema The data dictionary, for the type hierarchy.
     * @param scope The contexts whose entities may match.
     * @return The confident match, when there is one, and the entities found.
     * @throws UnsupportedOperationException when the searcher cannot hold the search to [scope].
     */
    fun search(suggested: SuggestedEntity, schema: DataDictionary, scope: ContextReadScope): SearchResult
}
