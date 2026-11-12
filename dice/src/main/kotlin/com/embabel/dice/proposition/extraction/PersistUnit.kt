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
package com.embabel.dice.proposition.extraction

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.pipeline.PersistablePropositions
import com.embabel.dice.pipeline.referencedEntities
import com.embabel.dice.proposition.Proposition
import org.jetbrains.annotations.ApiStatus
import java.util.Collections

/**
 * One unit of extraction output to store in a single write: the propositions, the entities they
 * refer to, and optionally the run that produced them.
 *
 * The entity lists only hold entities that some proposition in the unit refers to through a
 * resolved mention. Build a unit with [of], then attach a run with [withRun].
 *
 * EXPERIMENTAL. The API may change before 1.0.
 *
 * @property run The extraction run the propositions are attributed to, or null when there is none
 * @property lineagePolicy What happens when the run link cannot be recorded
 */
@ApiStatus.Experimental
class PersistUnit private constructor(
    propositions: List<Proposition>,
    newEntities: List<NamedEntityData>,
    updatedEntities: List<NamedEntityData>,
    val run: ExtractionRunKey?,
    val lineagePolicy: LineageFailurePolicy,
) {

    /** The propositions to store, in order. The list cannot be modified. */
    val propositions: List<Proposition> = Collections.unmodifiableList(ArrayList(propositions))

    /** Entities to create, limited to those a proposition refers to. The list cannot be modified. */
    val newEntities: List<NamedEntityData> = Collections.unmodifiableList(ArrayList(newEntities))

    /** Entities to update, limited to those a proposition refers to. The list cannot be modified. */
    val updatedEntities: List<NamedEntityData> = Collections.unmodifiableList(ArrayList(updatedEntities))

    /**
     * A copy of this unit attributed to [run], with [LineageFailurePolicy.DEFAULT].
     *
     * @param run The run that produced the propositions.
     * @return A new unit. This one is unchanged.
     */
    fun withRun(run: ExtractionRunKey): PersistUnit = withRun(run, LineageFailurePolicy.DEFAULT)

    /**
     * A copy of this unit attributed to [run], with [policy] deciding what a missing run link does.
     *
     * @param run The run that produced the propositions.
     * @param policy What happens when the run link cannot be recorded.
     * @return A new unit. This one is unchanged.
     */
    fun withRun(run: ExtractionRunKey, policy: LineageFailurePolicy): PersistUnit =
        PersistUnit(propositions, newEntities, updatedEntities, run, policy)

    /** Counts, the run and the policy. Proposition text is left out because it can carry user content. */
    override fun toString(): String =
        "PersistUnit(propositions=${propositions.size}, newEntities=${newEntities.size}, " +
            "updatedEntities=${updatedEntities.size}, run=$run, lineagePolicy=$lineagePolicy)"

    companion object {

        /**
         * The unit for an extraction result: its [PersistablePropositions.propositionsToPersist],
         * and its new and updated entities that those propositions refer to.
         *
         * @param result The extraction result to store.
         * @return A unit with no run and [LineageFailurePolicy.DEFAULT].
         */
        @JvmStatic
        fun of(result: PersistablePropositions): PersistUnit =
            of(result.propositionsToPersist(), result.newEntities(), result.updatedEntities())

        /**
         * A unit holding [propositions] and the entities from [newEntities] and [updatedEntities]
         * that those propositions refer to. Each list is copied, so changing it after the call does
         * not change the unit.
         *
         * @param propositions The propositions to store.
         * @param newEntities Entities to create. Ones no proposition refers to are dropped.
         * @param updatedEntities Entities to update. Ones no proposition refers to are dropped.
         * @return A unit with no run and [LineageFailurePolicy.DEFAULT].
         */
        @JvmStatic
        fun of(
            propositions: List<Proposition>,
            newEntities: List<NamedEntityData>,
            updatedEntities: List<NamedEntityData>,
        ): PersistUnit =
            PersistUnit(
                propositions = propositions,
                newEntities = referencedEntities(propositions, newEntities),
                updatedEntities = referencedEntities(propositions, updatedEntities),
                run = null,
                lineagePolicy = LineageFailurePolicy.DEFAULT,
            )
    }
}
