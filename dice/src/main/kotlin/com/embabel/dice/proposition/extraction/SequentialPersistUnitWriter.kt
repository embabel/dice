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
import com.embabel.dice.pipeline.PersistablePropositions
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.PropositionRepository

/**
 * Stores a unit with one repository call after another and no transaction of its own.
 *
 * The steps run in [PersistStep] order: entities, propositions, the run link, then the structural
 * relationships. The first step that throws an [Exception] ends the write and becomes a
 * [PersistOutcome.Failed]. An [Error] propagates.
 *
 * @param propositionRepository Where propositions go.
 * @param entityRepository Where entities and the structural relationships go.
 * @param runLinkStore Where propositions are linked to their run, or null when none is bound.
 * @param afterRunLink Called once for a unit that carries a run, after the run link step and before
 * the structural relationships, with the run, what the link store returned and the lineage failure
 * if there was one. The count is passed as the store returned it, negative or not. It is not called
 * when the link call throws something other than a `RuntimeException`. It must not throw.
 */
internal class SequentialPersistUnitWriter(
    private val propositionRepository: PropositionRepository,
    private val entityRepository: NamedEntityDataRepository,
    private val runLinkStore: PropositionRunLinkStore?,
    private val afterRunLink: (key: ExtractionRunKey, runLinks: Int?, lineageFailure: LineageNotRecordedException?) -> Unit =
        { _, _, _ -> },
) : PersistUnitWriter {

    override fun write(unit: PersistUnit): PersistOutcome {
        val afterEntities =
            if (unit.newEntities.isEmpty() && unit.updatedEntities.isEmpty()) emptySet() else setOf(PersistStep.ENTITIES)
        try {
            unit.newEntities.forEach { entityRepository.save(it) }
            unit.updatedEntities.forEach { entityRepository.update(it) }
        } catch (e: Exception) {
            return PersistOutcome.failed(PersistStep.ENTITIES, e, afterEntities)
        }

        val afterPropositions = afterEntities + PersistStep.PROPOSITIONS
        val persisted = try {
            propositionRepository.saveAllReturningCanonical(unit.propositions)
        } catch (e: Exception) {
            return PersistOutcome.failed(PersistStep.PROPOSITIONS, e, afterPropositions)
        }

        val run = unit.run
        val link = try {
            if (run == null) RunLink.NOT_ASKED else linkRun(run, persisted)
        } catch (e: Exception) {
            // Only the link call can throw here, so the store was called.
            return PersistOutcome.failed(PersistStep.RUN_LINKS, e, afterPropositions + PersistStep.RUN_LINKS)
        }
        val afterRunLinks = if (link.linkCalled) afterPropositions + PersistStep.RUN_LINKS else afterPropositions
        val lineageFailure = link.lineageFailure
        if (run != null) afterRunLink(run, link.runLinks, lineageFailure)
        // An outcome rejects a negative count, so a store that returns one is reported with no count.
        val runLinks = link.runLinks?.takeIf { it >= 0 }
        if (lineageFailure != null && unit.lineagePolicy == LineageFailurePolicy.STRICT) {
            return PersistOutcome.failed(PersistStep.RUN_LINKS, lineageFailure, afterRunLinks, runLinks, lineageFailure)
        }

        try {
            PersistablePropositions.createStructuralRelationships(persisted.distinctCanonicalPropositions, entityRepository)
        } catch (e: Exception) {
            return PersistOutcome.failed(
                PersistStep.STRUCTURAL_RELATIONSHIPS,
                e,
                afterRunLinks + PersistStep.STRUCTURAL_RELATIONSHIPS,
                runLinks,
                lineageFailure,
            )
        }
        return PersistOutcome.stored(persisted, runLinks, lineageFailure)
    }

    /**
     * Links the stored propositions to [key], or says why they could not be linked.
     *
     * A missing store is reported before an empty save is checked, so a wiring mistake shows up on
     * every unit that carries a run.
     *
     * @param key The run the propositions came from.
     * @param persisted What the proposition save returned.
     * @return Whether the store was called, what it returned, and the lineage failure if there was one.
     */
    private fun linkRun(key: ExtractionRunKey, persisted: PropositionPersistenceResult): RunLink {
        val store = runLinkStore
            ?: return RunLink(
                linkCalled = false,
                runLinks = null,
                lineageFailure = LineageNotRecordedException(
                    key,
                    "analysis carries extraction run ${key.runRef.runId} and no " +
                        "PropositionRunLinkStore is bound, so its propositions cannot be attributed; " +
                        "bind one with withRunLineage, or bind LineageFailurePolicy.LENIENT to accept " +
                        "the gap",
                ),
            )
        val ids = persisted.canonicalIds
        if (ids.isEmpty()) return RunLink.NOT_ASKED
        val linked = try {
            store.link(key, ids)
        } catch (e: RuntimeException) {
            // Built in the handler, outside the guarded call, so this exception is never caught and wrapped again.
            return RunLink(
                linkCalled = true,
                runLinks = null,
                lineageFailure = LineageNotRecordedException(
                    key,
                    "could not attribute ${ids.size} proposition(s) to extraction run ${key.runRef.runId}",
                    e,
                ),
            )
        }
        return RunLink(linkCalled = true, runLinks = linked, lineageFailure = null)
    }

    /**
     * What the run link step did.
     *
     * @property linkCalled True when the store's link method was called, including when it threw
     * @property runLinks What the store returned, or null when it was not called or threw
     * @property lineageFailure Set when a link was asked for and not recorded
     */
    private class RunLink(
        val linkCalled: Boolean,
        val runLinks: Int?,
        val lineageFailure: LineageNotRecordedException?,
    ) {
        companion object {
            /** No link was asked for, or there was nothing to link. */
            val NOT_ASKED = RunLink(linkCalled = false, runLinks = null, lineageFailure = null)
        }
    }
}
