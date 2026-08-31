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

import org.jetbrains.annotations.ApiStatus
import java.util.Collections

/**
 * What a batch of propositions actually landed on: which stored proposition each one you handed in
 * became.
 *
 * Usually that is the same proposition you passed, with the same id. It is not the same when a
 * backend deduplicates. `DrivinePropositionRepository` collapses a fresh insert onto an existing
 * proposition with identical `(contextId, text)`, unions the incoming evidence into it, and hands
 * back the one that is stored — a different id from the one you minted. Anything that then writes an
 * edge, a projection or a grounding link against the id it minted is pointing at a node that does
 * not exist.
 *
 * `saveAll` returns `Unit`, so that mapping used to be thrown away. This type is what carries it
 * back.
 *
 * **Two views, and they are not interchangeable.** [canonicalPropositions] is positional: one entry
 * per input, in input order, so it lines up with the list you passed and can repeat a proposition
 * when two inputs deduplicated onto one. [canonicalIds] is the distinct set, first-seen order,
 * which is what you want for a write that should happen once per stored proposition.
 *
 * Whichever view you read, every proposition in it is the store's *final* answer for that input —
 * see [of] for what that means when one batch names an id twice.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property canonicalPropositions The stored proposition each input became, one per input, in input
 *   order
 * @property canonicalIdByInputId The id you handed in, mapped to the id it landed on
 */
@ApiStatus.Experimental
class PropositionPersistenceResult private constructor(
    canonicalPropositions: List<Proposition>,
    canonicalIdByInputId: Map<String, String>,
) {

    val canonicalPropositions: List<Proposition> =
        Collections.unmodifiableList(ArrayList(canonicalPropositions))

    val canonicalIdByInputId: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(canonicalIdByInputId))

    /**
     * The distinct stored proposition ids, in the order they were first seen.
     *
     * Two inputs that deduplicated onto one proposition appear once here and twice in
     * [canonicalPropositions]. Use this for anything that should be written once per stored
     * proposition, such as a run link.
     */
    val canonicalIds: List<String> =
        Collections.unmodifiableList(ArrayList(this.canonicalPropositions.map { it.id }.distinct()))

    /**
     * The distinct stored propositions, first-seen order — [canonicalIds] with the objects attached.
     *
     * **This is what downstream work should run over.** Two inputs that deduplicated onto one
     * proposition are one stored proposition, and projecting it twice, grounding it twice or issuing
     * the same structural merge twice is duplicate work whose edges happen to be idempotent — but
     * the records written *about* that work are not. A projection recorder writes one row per
     * result, so the positional list inflates the audit with rows describing a proposition that was
     * projected once.
     *
     * [canonicalPropositions] stays positional for callers that need to line results up against the
     * propositions they passed in.
     */
    val distinctCanonicalPropositions: List<Proposition> =
        Collections.unmodifiableList(ArrayList(this.canonicalPropositions.distinctBy { it.id }))

    /** The inputs whose id is not the id they landed on — the ones a backend deduplicated. */
    val dedupedInputIds: List<String> =
        Collections.unmodifiableList(
            ArrayList(this.canonicalIdByInputId.filter { (input, canonical) -> input != canonical }.keys),
        )

    /** True when nothing deduplicated: every input landed on its own id. */
    val isIdentity: Boolean
        get() = dedupedInputIds.isEmpty()

    /** The id [inputId] landed on, or null if this batch did not carry it. */
    fun canonicalIdOf(inputId: String): String? = canonicalIdByInputId[inputId]

    override fun toString(): String =
        "PropositionPersistenceResult(saved=${canonicalPropositions.size}, " +
            "distinct=${canonicalIds.size}, deduped=${dedupedInputIds.size})"

    companion object {

        /** Nothing was persisted. */
        @JvmField
        val EMPTY: PropositionPersistenceResult = PropositionPersistenceResult(emptyList(), emptyMap())

        /**
         * Pairs the propositions handed to a store with what the store returned for each, by
         * position.
         *
         * Position is the only pairing available: an id cannot be the key, because a deduplicated
         * input comes back under a different one, which is the whole point. Both lists come from one
         * call, so they are the same length and in the same order.
         *
         * **Every position reports the store's final answer for the id that position landed on.**
         * Two ways a position goes stale, and normalizing by *canonical* id covers both. One batch
         * can name one input id twice — two revision results touching one original — and a
         * replace-by-id store overwrites the first save with the second. And two *distinct* inputs
         * can deduplicate onto one canonical, where the save that collapses the second also updates
         * that canonical: DICE's graph repository unions the incoming evidence into the winner and
         * answers with the winner as it then stands. In both cases an earlier position holds an
         * object the store has already moved past, and in both cases the ids match, so comparing
         * ids cannot catch it.
         *
         * Resolving rather than rejecting is deliberate. This runs from
         * `PersistablePropositions.persistReturningCanonical` *after* the saves have run, so
         * throwing would fail an extraction whose propositions are already written — and
         * last-write-wins is not a guess, it is what the store has.
         *
         * @param inputs What was handed to the store, in order.
         * @param canonical What the store returned, one per input, in the same order.
         * @throws IllegalArgumentException if the lists are different lengths, or if one input id
         *   appears twice landing on two *different* stored ids. The second is a backend that
         *   answered two saves of one id with two different propositions, which nothing downstream
         *   could act on sensibly and which resolution cannot honestly paper over.
         */
        @JvmStatic
        fun of(
            inputs: List<Proposition>,
            canonical: List<Proposition>,
        ): PropositionPersistenceResult {
            require(inputs.size == canonical.size) {
                "a store must answer every proposition it was given: ${inputs.size} in, " +
                    "${canonical.size} out"
            }
            if (inputs.isEmpty()) return EMPTY
            val mapping = LinkedHashMap<String, String>(inputs.size)
            // Keyed by the id landed on, not the id handed in. Those are different keys and only
            // the first is wrong: two *distinct* inputs can deduplicate onto one canonical, and the
            // save that collapses the second one also updates that canonical — DICE's own graph
            // repository unions the incoming evidence into the winner and answers with the winner
            // as it then stands. Keyed by input id, the first position would keep the canonical as
            // it looked before the second input's evidence merged in, and the wiring passes would
            // run over an object the store had already moved past.
            val lastAnswerForCanonical = HashMap<String, Proposition>(inputs.size)
            inputs.forEachIndexed { index, input ->
                val landedOn = canonical[index]
                // A store may answer with a different id; it may never answer with a different
                // tenant. Downstream this object is wired, projected and grounded as though it were
                // this tenant's, and lineage is the only pass that would refuse it — best-effort, so
                // it refuses quietly and the rest still runs. Caught here, where it enters.
                require(landedOn.contextId == input.contextId) {
                    "proposition ${input.id} was answered with ${landedOn.id} from another context"
                }
                val already = mapping.put(input.id, landedOn.id)
                require(already == null || already == landedOn.id) {
                    "proposition ${input.id} landed on two different stored ids in one batch"
                }
                // The per-position check above is not enough on its own. Two positions can each be
                // answered in their own input's context and still name one stored id between them,
                // and the resolution below is keyed by that id — so the later answer would replace
                // the earlier one and hand position zero a proposition from another tenant, with
                // every individual guard satisfied. One id is one proposition in one tenant, which
                // the graph's uniqueness constraint already says, so a batch claiming otherwise is
                // describing a store that cannot exist.
                val seenUnder = lastAnswerForCanonical.put(landedOn.id, landedOn)
                require(seenUnder == null || seenUnder.contextId == landedOn.contextId) {
                    "stored proposition ${landedOn.id} was answered under two different contexts " +
                        "in one batch"
                }
            }
            // Every position reports the store's final answer for the id that position landed on,
            // so nothing downstream can be handed a proposition a later save in the same batch
            // replaced or updated.
            val resolved = inputs.map { lastAnswerForCanonical.getValue(mapping.getValue(it.id)) }
            return PropositionPersistenceResult(resolved, mapping)
        }
    }
}
