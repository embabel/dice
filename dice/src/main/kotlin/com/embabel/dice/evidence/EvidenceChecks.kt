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

/**
 * Argument checks shared by the evidence types.
 */
internal object EvidenceChecks {

    /**
     * Copies a caller's list into an unmodifiable list and rejects a null element.
     *
     * The list is read once into a snapshot, and the check runs on that snapshot, so a list that
     * changes while it is read cannot slip a null past the check.
     *
     * @param name the list name used in the error message, such as `items`
     * @param list the caller's list
     * @return an unmodifiable copy of [list]
     * @throws IllegalArgumentException if an element is null, naming the list and the index
     */
    fun <T : Any> copyOf(name: String, list: List<T?>): List<T> {
        val snapshot = ArrayList(list)
        snapshot.forEachIndexed { index, element ->
            require(element != null) { "$name[$index] must not be null" }
        }
        @Suppress("UNCHECKED_CAST")
        return java.util.List.copyOf(snapshot as List<T>)
    }

    /**
     * Rejects two items that cite the same claim, by [Citation.sameClaimAs].
     *
     * @param items each item with the label used for it in the error message, such as `items[0]`
     * @throws IllegalArgumentException if two items cite the same claim, naming both labels, the claim id
     *   and the context
     */
    fun requireDistinctClaims(items: List<Pair<String, EvidenceItem>>) {
        items.forEachIndexed { index, (label, item) ->
            items.drop(index + 1).forEach { (otherLabel, other) ->
                require(!item.citation.sameClaimAs(other.citation)) {
                    "$label and $otherLabel cite the same claim '${item.citation.propositionId}' " +
                        "in context '${item.citation.contextIdValue}'"
                }
            }
        }
    }
}
