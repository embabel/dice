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
 * What changes on a proposition when new evidence backs it up. Each caller states its own rule;
 * [reinforce] applies it.
 *
 * @property confidence works out the new confidence from the existing proposition and the evidence
 * @property decayFactor multiplies the existing decay, or leaves decay alone when null
 * @property mergeProvenance when true, adds the evidence's provenance entries to the existing ones
 * @property countReinforcement when true, adds one to the reinforce count
 * @property touchLastAccessed when true, stamps the last-accessed time as well as the content time
 */
internal class ReinforcementRule(
    val confidence: (existing: Proposition, evidence: Proposition) -> Double,
    val decayFactor: Double? = null,
    val mergeProvenance: Boolean = false,
    val countReinforcement: Boolean = false,
    val touchLastAccessed: Boolean = false,
)

/**
 * Returns [existing] strengthened by [evidence] the way [rule] says. Grounding from both is always
 * combined, without duplicates, and the content-revised time is always stamped.
 */
internal fun reinforce(existing: Proposition, evidence: Proposition, rule: ReinforcementRule): Proposition {
    val now = Instant.now()
    return existing.copy(
        confidence = rule.confidence(existing, evidence),
        decay = rule.decayFactor?.let { (existing.decay * it).coerceAtLeast(0.0) } ?: existing.decay,
        grounding = (existing.grounding + evidence.grounding).distinct(),
        provenanceEntries = if (rule.mergeProvenance) {
            (existing.provenanceEntries + evidence.provenanceEntries).distinct()
        } else {
            existing.provenanceEntries
        },
        reinforceCount = if (rule.countReinforcement) existing.reinforceCount + 1 else existing.reinforceCount,
        contentRevised = now,
        lastAccessed = if (rule.touchLastAccessed) now else existing.lastAccessed,
    )
}
