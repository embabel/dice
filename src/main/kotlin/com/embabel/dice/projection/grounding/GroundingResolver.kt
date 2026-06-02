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
package com.embabel.dice.projection.grounding

import com.embabel.agent.rag.model.NamedEntityData

/**
 * Resolves a [com.embabel.dice.proposition.Proposition] grounding id to
 * the source entity node(s) that back it. The single, pluggable
 * definition of the resolution convention — shared by the edge writer
 * ([GroundingWiringService]) and the source-text readers — so the rule
 * lives in exactly one place instead of being re-implemented
 * (inconsistently) at each call site.
 *
 * A grounding id is either the full node id (`email:<user>:<hash>`) or a
 * namespace-stripped form (`email:<hash>`) that some extractors stamp.
 * Implementations decide how tolerant resolution is; see
 * [DefaultGroundingResolver] for the standard exact-then-namespace-suffix
 * behaviour.
 *
 * Callers that write an edge MUST use the resolved node's real
 * [NamedEntityData.id], not the grounding string, or a stripped id would
 * MERGE-create a phantom bare `{id}` node.
 */
interface GroundingResolver {

    /**
     * Every entity [groundingId] resolves to. Empty when nothing
     * matches. A proposition grounded in several sources legitimately
     * resolves to several nodes, so this returns all of them.
     */
    fun resolveAll(groundingId: String): List<NamedEntityData>

    /**
     * Single best resolution: the exact match, else the unique match.
     * Returns null when nothing matches OR the result is ambiguous
     * (more than one node) — an ambiguous match is never silently
     * collapsed to an arbitrary node.
     */
    fun resolve(groundingId: String): NamedEntityData?
}
