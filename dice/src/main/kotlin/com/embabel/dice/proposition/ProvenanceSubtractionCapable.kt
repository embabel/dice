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

/**
 * Opt-in capability for taking named evidence off a proposition in one atomic step.
 *
 * Collector undo is what needs it. Reversing a fold means deleting exactly the entries the fold
 * added and leaving everything else alone. [PropositionStore.setProvenance] cannot express that
 * safely, because it names what should remain: the caller has to read the entries first, and any
 * evidence another extraction adds between that read and the write is replaced away with nothing
 * left to recover it from. Naming what goes has no such window.
 *
 * The capability sits outside [PropositionStore] deliberately. Closing that window is a promise
 * about how a backend writes, and no shared default body can make it — a read-modify-write default
 * would compile everywhere while quietly carrying the very data loss it claims to prevent. So a
 * store that cannot subtract atomically is absent from this type, and a caller probes with an `as?`
 * test and handles the absence as its own case:
 *
 * ```kotlin
 * val subtraction = store as? ProvenanceSubtractionCapable
 *     ?: error("this backend cannot subtract provenance atomically")
 * ```
 *
 * ## What implementing this promises
 *
 * - **Atomicity.** Reading the current entries and writing what survives happen as one step —
 *   a compare-and-set retry loop, a lock the store already holds over the row, or a single delete
 *   statement the backend runs itself. Evidence another writer adds while a subtraction is in
 *   flight is still there when the subtraction finishes.
 * - **Exactness.** Refs follow the shared evidence-key contract: a ref minted by
 *   [com.embabel.dice.provenance.ProvenanceEvidenceKey] names one entry, and a bare locator key
 *   from before revisions existed reaches revisionless entries for that source only. Every entry
 *   the refs do not name survives untouched.
 * - **No resurrection.** Subtracting the last entry from a proposition another writer has already
 *   deleted answers null and writes nothing. The proposition stays deleted.
 *
 * A decorator wrapping a capable store carries this type and forwards to its delegate;
 * [EventEmittingPropositionRepository] is the worked example.
 */
@ApiStatus.Experimental
interface ProvenanceSubtractionCapable {

    /**
     * Whether this particular instance can really subtract. Implementing the interface is a
     * type-level promise; this is the runtime truth, for a decorator that forwards to a backend it
     * only discovers when it is constructed. A caller checks this before trusting the type.
     */
    val supportsProvenanceSubtraction: Boolean get() = true

    /**
     * Take exactly the evidence named by [provenanceRefs] off [propositionId], leaving the rest of
     * its evidence alone.
     *
     * Passing no refs reads and returns the proposition unchanged.
     *
     * @param propositionId the proposition to subtract from
     * @param provenanceRefs evidence keys or bare locator keys naming what goes
     * @return the proposition as the subtraction left it, or null when the store holds no
     *   proposition under that id — which a caller reads as "somebody deleted it".
     */
    fun subtractProvenance(propositionId: String, provenanceRefs: List<String>): Proposition?
}
