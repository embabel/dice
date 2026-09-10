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
package com.embabel.dice.projection.memory.collector

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.provenance.ProvenanceEvidenceKey
import com.embabel.dice.spi.RetiredProposition

/**
 * The undo record for one proposition about to be folded into a survivor: only the grounding,
 * provenance and source ids the survivor does not already hold.
 *
 * `absorbEvidence` is a deduplicating union, so anything the survivor owned before the merge
 * (common for near-duplicates from the same source) is left out. Recording it as folded would let
 * a later undo strip evidence the survivor held on its own.
 *
 * Evidence is compared by its full evidence key, so one document read at two revisions counts as
 * two pieces of evidence: a survivor citing r1 does not hide the loser's r2, and undo can name r2
 * exactly. The locator keys stay beside them for trace readers and for consumers still reading the
 * older field.
 */
internal fun retiredByFold(loser: Proposition, survivor: Proposition): RetiredProposition {
    val survivorGrounding = survivor.grounding.toSet()
    val survivorProvenanceRefs = survivor.provenanceEntries
        .map { it.locator.key() }
        .toSet()
    val survivorEvidenceKeys = survivor.provenanceEntries
        .map(ProvenanceEvidenceKey::encode)
        .toSet()
    val survivorSourceIds = survivor.sourceIds.toSet()
    return RetiredProposition(
        propositionId = loser.id,
        priorStatus = loser.status,
        foldedGrounding = loser.grounding - survivorGrounding,
        foldedProvenanceRefs = loser.provenanceEntries
            .map { it.locator.key() }
            .distinct() - survivorProvenanceRefs,
        foldedSourceIds = loser.sourceIds - survivorSourceIds,
        foldedProvenanceEvidenceKeys = loser.provenanceEntries
            .map(ProvenanceEvidenceKey::encode)
            .distinct() - survivorEvidenceKeys,
    )
}
