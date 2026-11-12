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

import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.jetbrains.annotations.ApiStatus

/**
 * Stores one [PersistUnit] and reports how it went as a [PersistOutcome].
 *
 * A writer returns [PersistOutcome.Failed] for a failed write and does not throw an exception for
 * it. The outcome names the step that failed and the steps whose writes may still be in the store.
 *
 * The sequential writer from [sequential] has no transaction of its own. A failure can leave the
 * earlier steps written. To store a unit all or nothing, call it inside a transaction and roll that
 * transaction back when the outcome is [PersistOutcome.Failed].
 *
 * From Java:
 *
 * ```java
 * PersistUnitWriter writer = PersistUnitWriter.sequential(propositionRepository, entityRepository, runLinkStore);
 * PersistOutcome outcome = writer.write(PersistUnit.of(result).withRun(runKey));
 * if (outcome instanceof PersistOutcome.Failed failed) {
 *     logger.warn("Storing failed at {}, left in store: {}", failed.getStep(), failed.getLeftInStore(), failed.getCause());
 * }
 * ```
 *
 * From Kotlin:
 *
 * ```kotlin
 * val writer = PersistUnitWriter.sequential(propositionRepository, entityRepository, runLinkStore)
 * when (val outcome = writer.write(PersistUnit.of(result).withRun(runKey))) {
 *     is PersistOutcome.Stored -> project(outcome.persisted.distinctCanonicalPropositions)
 *     is PersistOutcome.Failed -> logger.warn("Storing failed at {}", outcome.step, outcome.cause)
 * }
 * ```
 *
 * EXPERIMENTAL. The API may change before 1.0.
 */
@ApiStatus.Experimental
fun interface PersistUnitWriter {

    /**
     * Stores [unit].
     *
     * @param unit The propositions, entities and run to store.
     * @return [PersistOutcome.Stored] when every step ran, [PersistOutcome.Failed] when a step threw.
     */
    fun write(unit: PersistUnit): PersistOutcome

    companion object {

        /**
         * A writer that makes the repository calls one after another, with no run link store.
         *
         * A unit that carries a run fails at [PersistStep.RUN_LINKS] under
         * [LineageFailurePolicy.STRICT], because nothing can record the link.
         *
         * @param propositionRepository Where propositions go.
         * @param entityRepository Where entities and the structural relationships go.
         * @return A sequential writer.
         */
        @JvmStatic
        fun sequential(
            propositionRepository: PropositionRepository,
            entityRepository: NamedEntityDataRepository,
        ): PersistUnitWriter = sequential(propositionRepository, entityRepository, null)

        /**
         * A writer that makes the repository calls one after another.
         *
         * @param propositionRepository Where propositions go.
         * @param entityRepository Where entities and the structural relationships go.
         * @param runLinkStore Where a unit's propositions are linked to its run, or null for none.
         * @return A sequential writer.
         */
        @JvmStatic
        fun sequential(
            propositionRepository: PropositionRepository,
            entityRepository: NamedEntityDataRepository,
            runLinkStore: PropositionRunLinkStore?,
        ): PersistUnitWriter = SequentialPersistUnitWriter(propositionRepository, entityRepository, runLinkStore)

        /**
         * A writer over the in-memory stores that puts them back when a step fails, with no run link store.
         *
         * A unit that carries a run fails at [PersistStep.RUN_LINKS] under
         * [LineageFailurePolicy.STRICT], and the stores are put back.
         * A failed unit restores claims, their embeddings and entities to what they held before the call.
         * Units through one writer run one at a time, so use one writer per set of stores.
         * Another thread can read a unit part way through.
         * A write another caller makes to the same ids during a unit is overwritten when the unit is undone.
         * Restoring an entity goes through `save`, so an entity repository with an embedder embeds it again.
         * Relationships written before the failure stay, because the entity repository cannot remove them.
         * [PersistOutcome.Failed.leftInStore] then holds [PersistStep.STRUCTURAL_RELATIONSHIPS].
         *
         * @param propositionRepository Where propositions go.
         * @param entityRepository Where entities and the structural relationships go.
         * @return A writer that undoes a failed unit.
         */
        @JvmStatic
        fun inMemory(
            propositionRepository: InMemoryPropositionRepository,
            entityRepository: NamedEntityDataRepository,
        ): PersistUnitWriter = inMemory(propositionRepository, entityRepository, null)

        /**
         * A writer over the in-memory stores that puts them back when a step fails.
         *
         * A failed unit restores claims, their embeddings, entities and run links to what they held
         * before the call.
         * Units through one writer run one at a time, so use one writer per set of stores.
         * Another thread can read a unit part way through.
         * A write another caller makes to the same ids during a unit is overwritten when the unit is undone.
         * Restoring an entity goes through `save`, so an entity repository with an embedder embeds it again.
         * Relationships written before the failure stay, because the entity repository cannot remove them.
         * [PersistOutcome.Failed.leftInStore] then holds [PersistStep.STRUCTURAL_RELATIONSHIPS].
         * Run links the link store evicted for old finished runs during the unit are not brought back.
         * An undo that throws adds its step to `leftInStore` and its exception to the cause's suppressed exceptions.
         *
         * @param propositionRepository Where propositions go.
         * @param entityRepository Where entities and the structural relationships go.
         * @param runLinkStore Where a unit's propositions are linked to its run, or null for none.
         * @return A writer that undoes a failed unit.
         */
        @JvmStatic
        fun inMemory(
            propositionRepository: InMemoryPropositionRepository,
            entityRepository: NamedEntityDataRepository,
            runLinkStore: InMemoryPropositionRunLinkStore?,
        ): PersistUnitWriter = InMemoryPersistUnitWriter(propositionRepository, entityRepository, runLinkStore)
    }
}
