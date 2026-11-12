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
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository

/**
 * Stores a unit in the in-memory stores and puts them back as they were when a step fails.
 *
 * It records what the unit's ids hold, runs the sequential writer, and on a failure or a thrown
 * [Throwable] undoes the run links, then the claims, then the entities. Run links go first because
 * the link store drops a link whose claim is missing when it reads it.
 *
 * @param propositionRepository Where propositions go and are restored from a snapshot.
 * @param entityRepository Where entities and the structural relationships go.
 * @param runLinkStore Where propositions are linked to their run, or null when none is bound.
 */
internal class InMemoryPersistUnitWriter(
    private val propositionRepository: InMemoryPropositionRepository,
    private val entityRepository: NamedEntityDataRepository,
    private val runLinkStore: InMemoryPropositionRunLinkStore?,
) : PersistUnitWriter {

    private val lock = Any()

    private val delegate = SequentialPersistUnitWriter(propositionRepository, entityRepository, runLinkStore)

    override fun write(unit: PersistUnit): PersistOutcome = synchronized(lock) {
        val before = try {
            record(unit)
        } catch (e: Exception) {
            return PersistOutcome.failed(PersistStep.PREPARE, e, emptySet())
        }
        val outcome = try {
            delegate.write(unit)
        } catch (t: Throwable) {
            undo(unit, before).forEach { (_, e) -> if (e !== t) t.addSuppressed(e) }
            throw t
        }
        when (outcome) {
            is PersistOutcome.Stored -> outcome
            is PersistOutcome.Failed -> {
                val undoFailures = undo(unit, before)
                undoFailures.forEach { (_, e) -> if (e !== outcome.cause) outcome.cause.addSuppressed(e) }
                val relationshipsLeft = outcome.leftInStore.filter { it == PersistStep.STRUCTURAL_RELATIONSHIPS }
                PersistOutcome.failed(
                    outcome.step,
                    outcome.cause,
                    (relationshipsLeft + undoFailures.map { it.first }).toSet(),
                    outcome.runLinks,
                    outcome.lineageFailure,
                )
            }
        }
    }

    /**
     * Records what the stores hold for the ids the unit touches.
     *
     * @param unit The unit about to be written.
     * @return The claims, entities and run links as they are now.
     */
    private fun record(unit: PersistUnit): BeforeImage {
        // The unit's ids suffice because InMemoryPropositionRepository.save stores and returns the id it was given.
        val claims = propositionRepository.snapshot(unit.propositions.map { it.id })
        val entities = (unit.newEntities + unit.updatedEntities)
            .map { it.id }
            .distinct()
            .associateWith { entityRepository.findById(it) }
        val run = unit.run
        val runLinks = if (run == null) null else runLinkStore?.linkedTo(run)
        return BeforeImage(claims, entities, runLinks)
    }

    /**
     * Puts the stores back to [before], one undo after another, and keeps going when one throws.
     *
     * @param unit The unit that was written.
     * @param before What the stores held before the write.
     * @return The step and exception of each undo that threw, in the order they ran.
     */
    private fun undo(unit: PersistUnit, before: BeforeImage): List<Pair<PersistStep, Exception>> {
        val run = unit.run
        val linkStore = runLinkStore
        val linkFailure = if (run != null && linkStore != null && before.runLinks != null) {
            attempt(PersistStep.RUN_LINKS) {
                linkStore.unlink(run, unit.propositions.map { it.id } - before.runLinks)
            }
        } else {
            null
        }
        val claimFailure = attempt(PersistStep.PROPOSITIONS) { propositionRepository.restore(before.claims) }
        val entityFailures = before.entities.mapNotNull { (id, entity) ->
            attempt(PersistStep.ENTITIES) {
                if (entity == null) entityRepository.delete(id) else entityRepository.save(entity)
            }
        }
        return listOfNotNull(linkFailure, claimFailure) + entityFailures
    }

    /**
     * Runs one undo and catches the exception it throws.
     *
     * @param step The step the undo belongs to.
     * @param action The undo.
     * @return Null when the undo ran, otherwise the step and the exception.
     */
    private inline fun attempt(step: PersistStep, action: () -> Unit): Pair<PersistStep, Exception>? =
        try {
            action()
            null
        } catch (e: Exception) {
            step to e
        }

    /**
     * What the stores held for a unit's ids before it was written.
     *
     * @property claims The claims and embeddings of the unit's proposition ids
     * @property entities Each entity id and the entity it held, or null when it was absent
     * @property runLinks The ids linked to the unit's run, or null when there is no run or no link store
     */
    private class BeforeImage(
        val claims: InMemoryPropositionRepository.Snapshot,
        val entities: Map<String, NamedEntityData?>,
        val runLinks: Set<String>?,
    )
}
