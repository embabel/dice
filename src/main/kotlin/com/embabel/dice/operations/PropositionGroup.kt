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
package com.embabel.dice.operations

import com.embabel.dice.proposition.Proposition

/**
 * A labeled group of propositions.
 *
 * Used for operations that need to identify or compare groups, such as:
 * - Contrast: compare "Alice" group vs "Bob" group
 * - Abstraction: abstract propositions about "Engineering Team"
 * - Temporal: compare "Q1 2025" vs "Q2 2025"
 *
 * @property label Human-readable identifier for the group (e.g., "Alice", "Q1 2025", "Engineering Team")
 * @property propositions The propositions in this group
 */
data class PropositionGroup(
    val label: String,
    val propositions: List<Proposition>,
) {
    /** Number of propositions in this group */
    val size: Int get() = propositions.size

    /** Whether this group has any propositions */
    fun isEmpty(): Boolean = propositions.isEmpty()

    /** Whether this group has propositions */
    fun isNotEmpty(): Boolean = propositions.isNotEmpty()

    companion object {
        /**
         * Create a group from propositions with an auto-generated label.
         */
        fun of(label: String, vararg propositions: Proposition): PropositionGroup =
            PropositionGroup(label, propositions.toList())

        /**
         * Create a group from a list with the given label.
         */
        fun of(label: String, propositions: List<Proposition>): PropositionGroup =
            PropositionGroup(label, propositions)
    }
}
