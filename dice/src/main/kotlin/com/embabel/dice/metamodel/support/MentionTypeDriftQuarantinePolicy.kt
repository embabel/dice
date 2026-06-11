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
package com.embabel.dice.metamodel.support

import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.DriftQuarantinePolicy
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.QuarantineDecision
import com.embabel.dice.metamodel.QuarantineResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory

/**
 * Default [DriftQuarantinePolicy] that quarantines propositions whose entity mentions reference
 * entity types affected by a **lossy** schema change. A change is lossy when it can orphan
 * existing mentions:
 *
 * - the entity type was **removed** ([MetamodelDiff.removedEntityTypes]); or
 * - the entity type's name is preserved but it **lost** labels or properties
 *   ([MetamodelChange.EntityTypeModified] with non-empty `removedLabels`/`removedProperties`).
 *
 * Additive changes (new types, added labels, added properties) are non-lossy and never trigger
 * quarantine. A proposition is quarantined when at least one of its mentions has a type matching
 * a lossy change. Quarantining:
 *
 * 1. Transitions the proposition to [PropositionStatus.STALE] via [Proposition.withStatus].
 * 2. Annotates it with a human-readable reason under [DiceMetadataKeys.QUARANTINE_REASON].
 *
 * Both operations produce an immutable copy — the original proposition is never mutated.
 * The caller is responsible for persisting the returned copies.
 */
class MentionTypeDriftQuarantinePolicy : DriftQuarantinePolicy {

    private val logger = LoggerFactory.getLogger(MentionTypeDriftQuarantinePolicy::class.java)

    override fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult {
        val removedTypes = diff.removedEntityTypes
        // Types whose name is preserved but which lost labels or properties — also lossy, since a
        // mention may have relied on a label/property that no longer exists. Keyed by type name.
        val lossyModified = diff.modifiedEntityTypes
            .filter { it.removedLabels.isNotEmpty() || it.removedProperties.isNotEmpty() }
            .associateBy { it.typeName }

        if (removedTypes.isEmpty() && lossyModified.isEmpty()) {
            // Fast path: no lossy change means no proposition can be affected.
            val conforming = propositions.map { QuarantineDecision.Conforming(it) }
            return QuarantineResult(conforming = conforming, quarantined = emptyList())
        }

        val conforming = mutableListOf<QuarantineDecision.Conforming>()
        val quarantined = mutableListOf<QuarantineDecision.Quarantined>()

        for (proposition in propositions) {
            // Skip propositions that were already quarantined by a previous drift sweep so we
            // don't overwrite their original quarantine reason. The caller can force a re-sweep
            // by clearing the QUARANTINE_REASON metadata before passing propositions here.
            if (proposition.status == PropositionStatus.STALE
                && proposition.metadata.containsKey(DiceMetadataKeys.QUARANTINE_REASON)
            ) {
                conforming += QuarantineDecision.Conforming(proposition)
                continue
            }

            val mentionTypes = proposition.mentions.map { it.type }.toSet()
            val removedHit = mentionTypes intersect removedTypes
            val lossyHit = mentionTypes intersect lossyModified.keys
            val affectedTypes = removedHit + lossyHit

            if (affectedTypes.isEmpty()) {
                conforming += QuarantineDecision.Conforming(proposition)
            } else {
                val reason = buildReason(
                    removedTypes = removedHit,
                    lossyChanges = lossyHit.map { lossyModified.getValue(it) },
                    fromSchema = diff.fromVersion.schemaName,
                    toSchema = diff.toVersion.schemaName,
                )
                val flagged = proposition
                    .withStatus(PropositionStatus.STALE)
                    .withMetadataValue(DiceMetadataKeys.QUARANTINE_REASON, reason)

                logger.debug(
                    "Quarantining proposition '{}' (id={}): {}",
                    proposition.text,
                    proposition.id,
                    reason,
                )

                quarantined += QuarantineDecision.Quarantined(
                    proposition = flagged,
                    reason = reason,
                    affectedMentionTypes = affectedTypes,
                )
            }
        }

        logger.info(
            "Drift quarantine sweep complete: {} conforming, {} quarantined " +
                "(removed types: {}, lossy-modified types: {})",
            conforming.size,
            quarantined.size,
            removedTypes,
            lossyModified.keys,
        )

        return QuarantineResult(conforming = conforming, quarantined = quarantined)
    }

    private fun buildReason(
        removedTypes: Set<String>,
        lossyChanges: List<MetamodelChange.EntityTypeModified>,
        fromSchema: String,
        toSchema: String,
    ): String {
        val clauses = mutableListOf<String>()
        if (removedTypes.isNotEmpty()) {
            clauses += "type(s) [${removedTypes.sorted().joinToString(", ")}] removed"
        }
        lossyChanges.sortedBy { it.typeName }.forEach { change ->
            val losses = mutableListOf<String>()
            if (change.removedLabels.isNotEmpty()) {
                losses += "label(s) [${change.removedLabels.sorted().joinToString(", ")}]"
            }
            if (change.removedProperties.isNotEmpty()) {
                losses += "propert${if (change.removedProperties.size == 1) "y" else "ies"} " +
                    "[${change.removedProperties.sorted().joinToString(", ")}]"
            }
            clauses += "type '${change.typeName}' lost ${losses.joinToString(" and ")}"
        }
        return "Schema drift '$fromSchema' → '$toSchema': ${clauses.joinToString("; ")}"
    }
}
