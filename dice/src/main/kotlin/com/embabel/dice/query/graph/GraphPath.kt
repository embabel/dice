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
package com.embabel.dice.query.graph

import com.embabel.dice.proposition.Proposition

/**
 * An ordered path between two entities, derived from proposition edges.
 *
 * The path is the entity sequence `[a, ..., b]` together with the propositions connecting each
 * consecutive pair. An empty [entityIds] models "no path" — callers that need to distinguish an
 * absent path from a present one read [found].
 *
 * @property entityIds the ordered sequence of entity identifiers from start to end; empty for no path
 * @property edges the propositions connecting consecutive entities along the path (size = entityIds.size - 1
 *   for a non-empty path)
 */
data class GraphPath(
    val entityIds: List<String>,
    val edges: List<Proposition>,
) {
    /** Whether this path is empty (the no-path sentinel). */
    val isEmpty: Boolean get() = entityIds.isEmpty()

    /** Whether this path actually connects two entities. */
    val found: Boolean get() = entityIds.isNotEmpty()

    companion object {
        /** The no-path sentinel: an empty entity sequence with no edges. */
        @JvmField
        val EMPTY = GraphPath(emptyList(), emptyList())
    }
}
