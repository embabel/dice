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
     * Checks loaded claims against the ids they were loaded for. Every loaded claim must cite an id in [ids],
     * and no two loaded claims may cite the same id. A subset of [ids], or no loaded claim at all, is accepted.
     * The ids carry no context, so the check compares claim ids alone.
     *
     * @param loadedName the name of the loaded list used in the error message, such as `derivedFrom`
     * @param loaded the loaded claims
     * @param idsName the name of the id list used in the error message, such as `derivedFromIds`
     * @param ids the ids the claims were loaded for
     * @throws IllegalArgumentException if a loaded claim cites an id outside [ids], or two loaded claims cite
     *   the same id
     */
    fun requireLoadedClaimsNamed(loadedName: String, loaded: List<EvidenceItem>, idsName: String, ids: List<String>) {
        val firstIndexById = HashMap<String, Int>()
        loaded.forEachIndexed { index, claim ->
            val id = claim.citation.propositionId
            require(id in ids) { "$loadedName[$index] cites claim '$id', which is not in $idsName" }
            val earlier = firstIndexById.putIfAbsent(id, index)
            require(earlier == null) { "$loadedName[$earlier] and $loadedName[$index] cite the same claim id '$id'" }
        }
    }

    /**
     * Picks the ids for an item that holds [loaded] claims. Ids already set are kept. When there are none,
     * the ids come from the loaded claims, in order.
     *
     * @param ids the ids already set, or an empty list
     * @param loaded the loaded claims
     * @return [ids] when it is not empty, otherwise the claim id of each loaded claim
     */
    fun idsForLoaded(ids: List<String>, loaded: List<EvidenceItem>): List<String> =
        ids.ifEmpty { loaded.map { it.citation.propositionId } }

    /**
     * Rejects two items that cite the same claim, by [Citation.sameClaimAs]. One pass remembers the first
     * label seen for each claim id and context, and the first item that repeats one is reported.
     *
     * @param items each item with the label used for it in the error message, such as `items[0]`
     * @throws IllegalArgumentException if two items cite the same claim, naming the earlier label, the later
     *   label, the claim id and the context
     */
    fun requireDistinctClaims(items: List<Pair<String, EvidenceItem>>) {
        val firstLabelByClaim = HashMap<Pair<String, ContextId>, String>()
        items.forEach { (label, item) ->
            val citation = item.citation
            val earlier = firstLabelByClaim.putIfAbsent(citation.propositionId to citation.contextId, label)
            require(earlier == null) {
                "$earlier and $label cite the same claim '${citation.propositionId}' " +
                    "in context '${citation.contextIdValue}'"
            }
        }
    }
}
