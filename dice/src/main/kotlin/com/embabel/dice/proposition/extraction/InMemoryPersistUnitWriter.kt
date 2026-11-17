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
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
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
    private val entityRepository: InMemoryNamedEntityDataRepository,
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
        // The unit's ids suffice for the claims: InMemoryPropositionRepository.save stores and returns the id it was given.
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
        val unitIds = unit.propositions.map { it.id }.toSet()
        val run = unit.run
        val linkStore = runLinkStore
        val linkFailure = if (run != null && linkStore != null && before.runLinks != null) {
            // Links the run gained since the before-image, limited to the unit's own ids, so a link
            // another caller added to the run is kept.
            attempt(PersistStep.RUN_LINKS) {
                linkStore.unlink(run, (linkStore.linkedTo(run) - before.runLinks).intersect(unitIds))
            }
        } else {
            null
        }
        val claimFailure = attempt(PersistStep.PROPOSITIONS) { propositionRepository.restore(before.claims) }
        val written = (unit.newEntities + unit.updatedEntities).groupBy { it.id }
        val entityFailures = before.entities.mapNotNull { (id, entity) ->
            attempt(PersistStep.ENTITIES) { restoreEntity(id, entity, written[id].orEmpty()) }
        }
        return listOfNotNull(linkFailure, claimFailure) + entityFailures
    }

    /**
     * Puts one entity back when the store still holds an instance the unit wrote.
     *
     * An entity the store holds as the same instance as before was not written by the unit. It is
     * left alone, so the undo makes no embedder call for it. An entity that is neither the
     * before-image nor one of the unit's instances was saved by another writer and is left as it
     * is. A unit can carry several instances for one id, in both entity lists or twice in one, and
     * the write can stop after any of them, so each one counts.
     *
     * @param id The entity id.
     * @param before The entity the id held before the unit, or null when it was absent.
     * @param written Every instance the unit carries for the id.
     */
    private fun restoreEntity(id: String, before: NamedEntityData?, written: List<NamedEntityData>) {
        val now = entityRepository.findById(id)
        when {
            now === before -> Unit
            written.none { it === now } -> Unit
            before == null -> entityRepository.delete(id)
            else -> entityRepository.save(before)
        }
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
