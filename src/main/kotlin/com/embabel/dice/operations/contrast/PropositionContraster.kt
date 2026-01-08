package com.embabel.dice.operations.contrast

import com.embabel.dice.operations.PropositionGroup
import com.embabel.dice.proposition.Proposition

/**
 * Identifies and articulates differences between groups of propositions.
 *
 * This operation compares two labeled groups and generates propositions
 * that describe their differences - useful for:
 * - Comparing entities: "Alice vs Bob's work styles"
 * - Comparing time periods: "Q1 vs Q2 performance"
 * - Comparing products: "Product A vs Product B features"
 *
 * Example:
 * ```
 * Group A (Alice):
 *   - "Alice prefers morning meetings"
 *   - "Alice likes Python"
 *   - "Alice works remotely"
 *
 * Group B (Bob):
 *   - "Bob prefers afternoon meetings"
 *   - "Bob likes Java"
 *   - "Bob works in office"
 *
 * Output contrasts:
 *   - "Alice and Bob have opposite meeting time preferences (morning vs afternoon)"
 *   - "Alice prefers Python while Bob prefers Java"
 *   - "Alice works remotely whereas Bob works in office"
 * ```
 *
 * Contrast propositions are stored with:
 * - `level` = max(source levels) + 1
 * - `sourceIds` = IDs from both groups
 * - `decay` = average decay of sources
 * - `confidence` = LLM-assessed confidence in the contrast
 */
interface PropositionContraster {

    /**
     * Identify differences between two groups of propositions.
     *
     * @param groupA First group (e.g., props about Alice, or Q1 data)
     * @param groupB Second group (e.g., props about Bob, or Q2 data)
     * @param targetCount Desired number of contrast propositions to generate
     * @return Propositions describing the differences between the groups
     */
    fun contrast(
        groupA: PropositionGroup,
        groupB: PropositionGroup,
        targetCount: Int = 3,
    ): List<Proposition>
}
