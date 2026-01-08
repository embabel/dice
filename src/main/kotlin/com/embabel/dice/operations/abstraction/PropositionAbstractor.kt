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
