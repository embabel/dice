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
package com.embabel.dice.operations.abstraction

import com.embabel.dice.operations.PropositionGroup
import com.embabel.dice.proposition.Proposition

/**
 * Generates higher-level abstract propositions from a group of related propositions.
 *
 * This is an "active abstraction" operation - given N propositions about an entity,
 * topic, or context, it synthesizes higher-level insights that capture the essence
 * of the group.
 *
 * Example:
 * ```
 * Input propositions about Bob:
 *   - "Bob prefers morning meetings"
 *   - "Bob likes detailed documentation"
 *   - "Bob reviews PRs thoroughly"
 *
 * Output abstraction:
 *   - "Bob values thoroughness and clarity in work processes"
 * ```
 *
 * Abstractions are stored as regular propositions with:
 * - `level` = max(source levels) + 1
 * - `sourceIds` = IDs of source propositions
 * - `decay` = average decay of sources
 * - `confidence` = LLM-assessed confidence in the abstraction
 */
interface PropositionAbstractor {

    /**
     * Generate higher-level propositions from a labeled group.
     *
     * @param group Labeled group of propositions to abstract
     * @param targetCount Desired number of abstract propositions to generate
     * @return Abstracted propositions with level > 0 and sourceIds populated
     */
    fun abstract(
        group: PropositionGroup,
        targetCount: Int = 3,
    ): List<Proposition>

    /**
     * Generate higher-level propositions from a list.
     * Convenience method that wraps propositions in an unlabeled group.
     *
     * @param propositions Source propositions to abstract
     * @param targetCount Desired number of abstract propositions to generate
     * @return Abstracted propositions with level > 0 and sourceIds populated
     */
    fun abstract(
        propositions: List<Proposition>,
        targetCount: Int = 3,
    ): List<Proposition> = abstract(PropositionGroup("", propositions), targetCount)
}
