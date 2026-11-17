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
package com.embabel.dice.proposition.revision

import com.embabel.dice.proposition.Proposition
import java.time.Instant

/**
 * What changes on a proposition when new evidence backs it up. Each caller states its own rule
 * and [reinforce] applies it. Confidence never drops below the stored value, whatever
 * [confidence] returns.
 *
 * @property confidence works out the new confidence from the existing proposition and the evidence
 * @property decayFactor multiplies the existing decay, or leaves decay alone when null
 * @property touchLastAccessed when true, stamps the last-accessed time with the revision time
 */
internal class ReinforcementRule(
    val confidence: (existing: Proposition, evidence: Proposition) -> Double,
    val decayFactor: Double? = null,
    val touchLastAccessed: Boolean = false,
)

/**
 * Strengthens a stored proposition with new evidence.
 *
 * The evidence's grounding, provenance entries and source ids are folded in with
 * [Proposition.absorbEvidence]. The stored proposition's own id is never folded into its source
 * ids. The reinforce count goes up by one for new evidence. Confidence never goes below the
 * stored value. One instant is stamped on both the content and metadata revision times.
 *
 * The stored proposition comes back as it is in two cases. The evidence has the same id, or the
 * evidence names a source and the stored proposition already holds all of it. A source is a
 * grounding id, a provenance entry or a source id. Without provenance entries the grounding ids
 * are the source identity. Evidence that names no source counts as new and still reinforces,
 * although [Proposition.absorbEvidence] alone treats it as already held. Evidence whose only
 * source is the stored proposition leaves it unchanged.
 *
 * @param existing the stored proposition
 * @param evidence the proposition that backs it up
 * @param rule what else changes
 * @return [existing] itself when nothing new arrives, otherwise a reinforced copy of it
 */
internal fun reinforce(existing: Proposition, evidence: Proposition, rule: ReinforcementRule): Proposition {
    if (existing.id == evidence.id) return existing
    val sources = evidence.copy(sourceIds = evidence.sourceIds.filter { it != existing.id })
    val folded = existing.absorbEvidence(sources)
    val namesNoSource = evidence.grounding.isEmpty() &&
        evidence.provenanceEntries.isEmpty() &&
        evidence.sourceIds.isEmpty()
    if (!namesNoSource && folded.reinforceCount == existing.reinforceCount) return existing
    val now = Instant.now()
    return folded.copy(
        confidence = rule.confidence(existing, evidence).coerceIn(existing.confidence, 1.0),
        decay = rule.decayFactor?.let { (existing.decay * it).coerceIn(0.0, 1.0) } ?: existing.decay,
        reinforceCount = existing.reinforceCount + 1,
        contentRevised = now,
        metadataRevised = now,
        lastAccessed = if (rule.touchLastAccessed) now else existing.lastAccessed,
    )
}
