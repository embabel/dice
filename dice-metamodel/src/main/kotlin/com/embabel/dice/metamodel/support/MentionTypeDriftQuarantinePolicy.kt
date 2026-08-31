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

import com.embabel.agent.core.Cardinality
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.DriftQuarantinePolicy
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.PropertySignature
import com.embabel.dice.metamodel.QuarantineDecision
import com.embabel.dice.metamodel.QuarantineResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory

/**
 * The shipped [DriftQuarantinePolicy]: quarantine a proposition when one of its entity mentions
 * names a type the schema change made **lossy**. Lossy means the change can strand data that was
 * already extracted:
 *
 * - the type was **removed**, so nothing describes those mentions any more;
 * - the type kept its name and **lost** labels or whole properties;
 * - a property kept its name and its shape **narrowed**: its value type or reference target changed,
 *   it flipped between holding a value and pointing at another type, or its cardinality shrank (a
 *   list collapsing to a single value, an optional becoming required).
 *
 * Additive changes never trigger quarantine: new types, new labels, new properties, and cardinality
 * moving the other way, since a single value becoming a list still holds everything it held before.
 * The diff itself makes no judgement. [MetamodelChange.PropertySignatureChanged] states that `age`
 * went from `string` to `integer`, and this policy decides that stranding is possible and pulls the
 * affected propositions out of normal use until a person looks.
 *
 * A type change counts as lossy in either direction. We know the declared type names moved; we don't
 * know how the backend stored the values or whether the new type can read the old ones, and guessing
 * wrong in the permissive direction leaves unreadable data looking healthy. Swap in a different
 * policy if your storage makes some widenings provably safe.
 *
 * Quarantining moves the proposition to [PropositionStatus.STALE] and annotates it under
 * [DiceMetadataKeys.QUARANTINE_REASON]. Both produce an immutable copy; the original is never
 * mutated, and persisting the copies is the caller's job.
 *
 * A proposition an earlier sweep already quarantined comes back as
 * [QuarantineDecision.AlreadyQuarantined], untouched, with its original reason preserved and outside
 * the conforming bucket. That holds for any diff, an empty one included, because being already
 * quarantined is a fact about the proposition.
 */
class MentionTypeDriftQuarantinePolicy : DriftQuarantinePolicy {

    private val logger = LoggerFactory.getLogger(MentionTypeDriftQuarantinePolicy::class.java)

    override fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult {
        val removedTypes = diff.removedEntityTypes

        // Types whose name survived and which lost labels or whole properties. Also lossy, because
        // a mention may have relied on a label or property that is now gone. Keyed by type name.
        val lossyModified = diff.modifiedEntityTypes
            .filter { it.removedLabels.isNotEmpty() || it.removedProperties.isNotEmpty() }
            .associateBy { it.typeName }

        // Types carrying a property that kept its name but narrowed. Grouped by type name, since
        // one type can have several such properties and the reason should name them all.
        val narrowedProperties = diff.propertySignatureChanges
            .filter { isNarrowing(it) }
            .groupBy { it.typeName }

        // There is deliberately no "nothing lossy, so everything conforms" shortcut here. Whether a
        // proposition is already quarantined is a fact about the proposition and doesn't depend on
        // the diff, so a shortcut would report an earlier sweep's quarantined records as Conforming
        // on any check that found nothing new. Drift checks run on a schedule and most runs find
        // nothing, so that would be the common case. Every proposition goes down one code path.

        val conforming = mutableListOf<QuarantineDecision.Conforming>()
        val quarantined = mutableListOf<QuarantineDecision.Quarantined>()
        // Propositions left alone because a previous sweep already quarantined them. Their own
        // bucket rather than folded into conforming, so conforming.size counts only clean ones.
        val alreadyQuarantined = mutableListOf<QuarantineDecision.AlreadyQuarantined>()

        for (proposition in propositions) {
            if (isAlreadyQuarantined(proposition)) {
                logger.debug(
                    "Leaving already-quarantined proposition (id={}) untouched; original reason preserved",
                    proposition.id,
                )
                alreadyQuarantined += QuarantineDecision.AlreadyQuarantined(
                    proposition = proposition,
                    originalReason = proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON] as? String,
                )
                continue
            }

            val mentionTypes = proposition.mentions.mapTo(mutableSetOf()) { it.type }
            val removedHit = mentionTypes intersect removedTypes
            val lossyHit = mentionTypes intersect lossyModified.keys
            val narrowedHit = mentionTypes intersect narrowedProperties.keys
            val affectedTypes = removedHit + lossyHit + narrowedHit

            if (affectedTypes.isEmpty()) {
                conforming += QuarantineDecision.Conforming(proposition)
                continue
            }

            val reason = buildReason(
                removedTypes = removedHit,
                lossyChanges = lossyHit.map { lossyModified.getValue(it) },
                narrowedChanges = narrowedHit.flatMap { narrowedProperties.getValue(it) },
                fromSchema = diff.fromVersion.schemaName,
                toSchema = diff.toVersion.schemaName,
            )
            val flagged = proposition
                .withStatus(PropositionStatus.STALE)
                .withMetadataValue(DiceMetadataKeys.QUARANTINE_REASON, reason)

            logger.debug("Quarantining proposition '{}' (id={}): {}", proposition.text, proposition.id, reason)

            quarantined += QuarantineDecision.Quarantined(
                proposition = flagged,
                reason = reason,
                affectedMentionTypes = affectedTypes,
            )
        }

        logger.info(
            "Drift quarantine sweep complete: {} conforming, {} already quarantined from a prior sweep, " +
                "{} newly quarantined (removed types: {}, lossy-modified types: {}, narrowed-property types: {})",
            conforming.size,
            alreadyQuarantined.size,
            quarantined.size,
            removedTypes,
            lossyModified.keys,
            narrowedProperties.keys,
        )

        return QuarantineResult(
            conforming = conforming,
            quarantined = quarantined,
            alreadyQuarantined = alreadyQuarantined,
        )
    }

    /**
     * Whether a proposition is one an earlier sweep already handled: `STALE` *and* carrying a
     * quarantine reason. Both halves matter, because a proposition made stale by ordinary decay
     * carries no reason and is still a live candidate here.
     */
    private fun isAlreadyQuarantined(proposition: Proposition): Boolean =
        proposition.status == PropositionStatus.STALE &&
            proposition.metadata.containsKey(DiceMetadataKeys.QUARANTINE_REASON)

    /**
     * Whether a property's new shape might not hold what its old shape did.
     *
     * A changed value type or a flip between value and reference always counts. Cardinality counts
     * only when it shrank: the four cardinalities line up as `ONE` ⊂ `OPTIONAL` ⊂ `SET` ⊂ `LIST` by
     * what they can hold, so moving up that order is safe (one value fits in a list) and moving
     * down can strand something (a list of three doesn't fit in a single value; a list collapsing
     * to a set drops duplicates).
     */
    private fun isNarrowing(change: MetamodelChange.PropertySignatureChanged): Boolean =
        change.typeChanged ||
            change.kindChanged ||
            breadth(change.after.cardinality) < breadth(change.before.cardinality)

    /** How much a cardinality can hold, as a rank: bigger holds everything smaller can. */
    private fun breadth(cardinality: Cardinality): Int = when (cardinality) {
        Cardinality.ONE -> 0
        Cardinality.OPTIONAL -> 1
        Cardinality.SET -> 2
        Cardinality.LIST -> 3
    }

    private fun buildReason(
        removedTypes: Set<String>,
        lossyChanges: List<MetamodelChange.EntityTypeModified>,
        narrowedChanges: List<MetamodelChange.PropertySignatureChanged>,
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
                // Names rather than full signatures: a person reads this reason to decide whether
                // to rescue the proposition, and a rendered PropertySignature buries the name in
                // constructor noise.
                losses += "propert${if (change.removedPropertyNames.size == 1) "y" else "ies"} " +
                    "[${change.removedPropertyNames.sorted().joinToString(", ")}]"
            }
            clauses += "type '${change.typeName}' lost ${losses.joinToString(" and ")}"
        }

        narrowedChanges
            .sortedWith(compareBy({ it.typeName }, { it.propertyName }))
            .forEach { change ->
                clauses += "type '${change.typeName}' narrowed property '${change.propertyName}' " +
                    "(${describe(change.before)} -> ${describe(change.after)})"
            }

        return "Schema drift '$fromSchema' → '$toSchema': ${clauses.joinToString("; ")}"
    }

    /** A property signature as a person would read it: `string ONE`, `Company LIST`. */
    private fun describe(signature: PropertySignature): String =
        "${signature.type.ifEmpty { signature.kind.name.lowercase() }} ${signature.cardinality}"
}
