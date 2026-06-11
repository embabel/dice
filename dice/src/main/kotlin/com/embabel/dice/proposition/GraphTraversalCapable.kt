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

/**
 * Opt-in capability for navigating the proposition abstraction hierarchy — finding source
 * propositions and the abstractions derived from them.
 *
 * The defaults resolve links in memory via [findById]/[findAll]; a graph-native store can
 * override to push traversal to the backend.
 */
interface GraphTraversalCapable {

    /**
     * Find a proposition by its ID. Used by [findSources]; satisfied automatically when
     * a type also implements [PropositionStore].
     */
    fun findById(id: String): Proposition?

    /**
     * Get all propositions. Used by [findAbstractionsOf]; satisfied automatically when
     * a type also implements [PropositionStore].
     */
    fun findAll(): List<Proposition>

    // ========================================================================
    // Source resolution - navigate the abstraction hierarchy
    // ========================================================================

    /**
     * Find the source propositions that a given proposition was abstracted from.
     * Resolves the proposition's [Proposition.sourceIds] to actual propositions.
     *
     * @param proposition The abstraction whose sources to find
     * @return Source propositions (partial list if some IDs are missing)
     */
    fun findSources(proposition: Proposition): List<Proposition> =
        proposition.sourceIds.mapNotNull { findById(it) }

    /**
     * Find propositions that were abstracted from the given proposition.
     * Searches for propositions that cite the given ID in their [Proposition.sourceIds].
     *
     * @param propositionId The ID of the source proposition
     * @return Propositions that list this ID as a source
     */
    fun findAbstractionsOf(propositionId: String): List<Proposition> =
        findAll().filter { propositionId in it.sourceIds }
}
