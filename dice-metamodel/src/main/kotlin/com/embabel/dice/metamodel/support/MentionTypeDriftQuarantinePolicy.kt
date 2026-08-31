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
 * - a property's shape **narrowed**: its value type or reference target changed to something outside
 *   [SAFE_TYPE_WIDENINGS], it flipped between holding a value and pointing at another type, or its
 *   cardinality shrank (a list collapsing to a single value, an optional becoming required). This
 *   covers a property that kept its name and one that was renamed under a declared alias; both carry
 *   a before and an after signature, and both are judged by the same rule.
 *
 * Additive changes never trigger quarantine: new types, new labels, new properties, and cardinality
 * moving the other way, since a single value becoming a list still holds everything it held before.
 * The diff itself makes no judgement. [MetamodelChange.PropertySignatureChanged] states that `age`
 * went from `string` to `integer`, and this policy decides that stranding is possible and pulls the
 * affected propositions out of normal use until a person looks.
 *
 * ## Declared renames
 *
 * A rename is a declared fact about one type or property, so on its own it strands nothing.
 * [MetamodelChange.EntityTypeRenamed] and [MetamodelChange.PropertyRenamed] are non-lossy per se,
 * and [MetamodelChange.EntityTypeAliasesChanged] never quarantines at all: it says the declaration's
 * list of former names moved, and no label, property or relationship went with it.
 *
 * Whatever else moved on a renamed type is reported under the type's **new** name, and the data in
 * the graph still carries the old one. So a mention type is matched against its own name plus every
 * current type name that used to go by it, read off the newer version's whole declared alias map.
 * Those accumulate, so a type renamed `A` → `B` → `C` declares `{A, B}` and a lossy change on `C`
 * quarantines propositions mentioning `A`, `B` or `C` alike.
 *
 * Matching reads the declaration rather than this diff's rename entries, so it holds when the rename
 * and the loss land in different releases: a diff that only drops a property from a type renamed two
 * stamps ago still reaches data written under the old name.
 *
 * A **removed** type is resolved from the older version instead, since it has no entry on the newer
 * side at all. Removing `C` after it had gone by `{A, B}` strands data under all three names, and
 * the removal is matched under all three. The exception is a former name the newer version declares
 * as a live type of its own: reusing a retired name is legal once its claimant is gone, and data
 * under it is judged as that live type's.
 *
 * A former name the declaration deliberately **retires** stops matching. Retiring is a statement
 * that the schema no longer claims the name, and from then on data still carrying it is reported by
 * the observed-side comparison as ordinary undeclared drift.
 *
 * A rename's own propagation — the type's own label swapping, a referrer's signature pointing at the
 * new name, a child's inherited label — folds into [MetamodelChange.EntityTypeRenamed] in the diff
 * and never reaches this policy as loss.
 *
 * ## Value types
 *
 * A changed value type counts as lossy in both directions except for the four promotions in
 * [SAFE_TYPE_WIDENINGS]. Outside those, we know the declared type names moved and we don't know how
 * the backend stored the values or whether the new type can read the old ones, and guessing wrong in
 * the permissive direction leaves unreadable data looking healthy. A changed reference target is
 * always lossy: it names a different entity type, which is not a promotion of anything.
 *
 * Quarantining moves the proposition to [PropositionStatus.STALE] and annotates it under
 * [DiceMetadataKeys.QUARANTINE_REASON]. Both produce an immutable copy; the original is never
 * mutated, and persisting the copies is the caller's job.
 *
 * A proposition an earlier sweep already quarantined comes back as
 * [QuarantineDecision.AlreadyQuarantined], untouched, with its original reason preserved and outside
 * the conforming bucket. That holds for any diff, an empty one included, because being already
 * quarantined is a fact about the proposition.
 *
 * Rename awareness and the widening allow-list are experimental: behavior may change before 1.0.
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
            .filter { isNarrowing(it.before, it.after) }
            .groupBy { it.typeName }

        // Types carrying a property that was renamed and narrowed in the same step. The rename is
        // harmless; the shape move underneath it is judged by the same rule as any other.
        val narrowedRenames = diff.renamedProperties
            .filter { isNarrowing(it.before, it.after) }
            .groupBy { it.typeName }

        // Every name a surviving type has gone by, pointing at the name its changes are reported
        // under, and every name a removed type has gone by, pointing at the removal. Two maps, read
        // off opposite sides of the diff, because a removed type is absent from the newer side.
        // Extracted once per sweep rather than per proposition.
        val currentNamesByFormerName = formerTypeNames(diff)
        val formerNamesOfRemovedTypes = formerNamesOfRemovedTypes(diff)

        // There is deliberately no "nothing lossy, so everything conforms" shortcut here. Whether a
        // proposition is already quarantined is a fact about the proposition and doesn't depend on
        // the diff, so a shortcut would report an earlier sweep's quarantined records as Conforming
        // on any check that found nothing new. Drift checks run on a schedule and most runs find
        // nothing, so that would be the common case. Every proposition goes down one code path.

        val conforming = mutableListOf<QuarantineDecision.Conforming>()
        val quarantined = mutableListOf<QuarantineDecision.Quarantined>()
        // Former names that actually matched something, for the summary line. The map above holds
        // every former name the declaration knows; this holds the ones a proposition was labelled
        // with, which is what an operator reading the log is trying to find out.
        val formerNamesMatched = sortedSetOf<String>()
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

            val affectedTypes = sortedSetOf<String>()
            val removedHit = sortedSetOf<String>()
            val lossyHit = LinkedHashSet<MetamodelChange.EntityTypeModified>()
            val narrowedHit = LinkedHashSet<MetamodelChange.PropertySignatureChanged>()
            val renamedHit = LinkedHashSet<MetamodelChange.PropertyRenamed>()
            // Mention types that only matched because a type used to go by them, and what that type
            // is called now. The reason says so; without it an operator reads a complaint about
            // 'Human' on a proposition whose mentions all say 'Person'.
            val matchedByFormerName = sortedMapOf<String, MutableSet<String>>()

            for (mentionType in mentionTypes) {
                var affected = false

                // Note that this mention type isn't the schema's own name for the type it hit.
                fun recordFormerName(schemaName: String) {
                    if (schemaName != mentionType) {
                        matchedByFormerName.getOrPut(mentionType) { sortedSetOf() } += schemaName
                        formerNamesMatched += mentionType
                    }
                }

                // Removals resolve through the OLDER version's aliases. A removed type takes its
                // former names down with it, and the newer version has no record they were ever
                // this type's, so the surviving-type map above can't see them.
                val removals = sortedSetOf<String>()
                if (mentionType in removedTypes) removals += mentionType
                removals += formerNamesOfRemovedTypes[mentionType].orEmpty()
                if (removals.isNotEmpty()) {
                    removedHit += removals
                    affected = true
                    removals.forEach(::recordFormerName)
                }

                for (currentName in setOf(mentionType) + currentNamesByFormerName[mentionType].orEmpty()) {
                    var lossyUnderThisName = false
                    lossyModified[currentName]?.let { lossyHit += it; lossyUnderThisName = true }
                    narrowedProperties[currentName]?.let { narrowedHit += it; lossyUnderThisName = true }
                    narrowedRenames[currentName]?.let { renamedHit += it; lossyUnderThisName = true }
                    if (lossyUnderThisName) {
                        affected = true
                        recordFormerName(currentName)
                    }
                }
                if (affected) affectedTypes += mentionType
            }

            if (affectedTypes.isEmpty()) {
                conforming += QuarantineDecision.Conforming(proposition)
                continue
            }

            val reason = buildReason(
                removedTypes = removedHit,
                lossyChanges = lossyHit.toList(),
                narrowedChanges = narrowedHit.toList(),
                renamedChanges = renamedHit.toList(),
                matchedByFormerName = matchedByFormerName,
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
                "{} newly quarantined (removed types: {}, lossy-modified types: {}, narrowed-property types: {}, " +
                "narrowed renamed-property types: {}, former names data was matched under: {})",
            conforming.size,
            alreadyQuarantined.size,
            quarantined.size,
            removedTypes,
            lossyModified.keys,
            narrowedProperties.keys,
            narrowedRenames.keys,
            formerNamesMatched,
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
     * Every name an entity type has gone by, mapped to what that type is called now.
     *
     * Read off the **newer version's whole declared alias map**, not just the renames this diff
     * happens to contain. A rename and a loss usually land in different releases: stamp 2 renames
     * `Person` to `Human`, stamp 3 drops a property, and the stamp-2-to-stamp-3 diff holds no rename
     * at all while the graph still holds nodes labelled `Person` and the declaration still says
     * `Human` used to be one. Keying off the diff's renames would let that loss pass over every
     * proposition it stranded, silently, which is the direction this policy exists to avoid.
     *
     * Safe to read unconditionally because of the declaration guard: an alias may not name a type
     * the schema still declares, so no key here can shadow a live type name.
     *
     * A former name can point at more than one current type — the same guard says nothing about two
     * live types both claiming one retired name. Nothing distinguishes which of the two a piece of
     * data under that name belongs to, so it is checked against both and a lossy change on either
     * quarantines.
     */
    private fun formerTypeNames(diff: MetamodelDiff): Map<String, Set<String>> {
        val byFormerName = mutableMapOf<String, MutableSet<String>>()
        diff.toVersion.entityTypeAliases.forEach { (typeName, formerNames) ->
            for (formerName in formerNames - typeName) {
                byFormerName.getOrPut(formerName) { sortedSetOf() } += typeName
            }
        }
        // A diff assembled by hand rather than by the differ can carry a rename whose old name the
        // stamp's alias map doesn't hold.
        for (rename in diff.renamedEntityTypes) {
            byFormerName.getOrPut(rename.before) { sortedSetOf() } += rename.after
        }
        return byFormerName
    }

    /**
     * Every name a **removed** type had gone by, mapped to the removed type it belonged to.
     *
     * Read off the OLDER version, which is the only side that still has the entry. A removed type
     * takes its former names with it: `C` with former names `{A, B}` disappearing leaves
     * `removedEntityTypes = [C]` and nothing in the newer version recording that `A` and `B` were
     * ever `C`'s. Matching the removal on the current name alone would return propositions labelled
     * `A` or `B` conforming, when no declared type describes them at all — the same evasion the
     * surviving-type map closes, on the one path that map can't see.
     *
     * A former name the newer version declares as a live type of its own is left out. Reusing a
     * retired name is legal once the type that claimed it is gone, and the removal's rationale is
     * that nothing describes those mentions any more, which is false when the schema declares a type
     * by that exact name. Data under it is judged as that type's, like any other mention.
     */
    private fun formerNamesOfRemovedTypes(diff: MetamodelDiff): Map<String, Set<String>> {
        val removed = diff.removedEntityTypes
        if (removed.isEmpty()) return emptyMap()

        val stillDeclared = diff.toVersion.entityTypeNames.toSet()
        val byFormerName = mutableMapOf<String, MutableSet<String>>()
        for (typeName in removed) {
            val formerNames = diff.fromVersion.entityTypeAliases[typeName].orEmpty()
            for (formerName in formerNames - typeName - stillDeclared) {
                byFormerName.getOrPut(formerName) { sortedSetOf() } += typeName
            }
        }
        return byFormerName
    }

    /**
     * Whether a property's new shape might not hold what its old shape did.
     *
     * A flip between value and reference always counts. A changed type counts unless it is one of
     * the promotions in [SAFE_TYPE_WIDENINGS]. Cardinality counts only when it shrank: the four
     * cardinalities line up as `ONE` ⊂ `OPTIONAL` ⊂ `SET` ⊂ `LIST` by what they can hold, so moving
     * up that order is safe (one value fits in a list) and moving down can strand something (a list
     * of three doesn't fit in a single value; a list collapsing to a set drops duplicates).
     *
     * Takes the two signatures rather than a change entry, so a renamed property and one that kept
     * its name are judged by the same code.
     */
    private fun isNarrowing(before: PropertySignature, after: PropertySignature): Boolean =
        before.kind != after.kind ||
            (before.type != after.type && !isSafeWidening(before, after)) ||
            breadth(after.cardinality) < breadth(before.cardinality)

    /**
     * Whether a value type moved to one that holds everything the old one held.
     *
     * Scoped to [PropertySignature.Kind.VALUE] on both sides. A reference target names an entity
     * type, and one entity type is never a promotion of another.
     */
    private fun isSafeWidening(before: PropertySignature, after: PropertySignature): Boolean =
        before.kind == PropertySignature.Kind.VALUE &&
            after.kind == PropertySignature.Kind.VALUE &&
            SAFE_TYPE_WIDENINGS[before.type] == after.type

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
        renamedChanges: List<MetamodelChange.PropertyRenamed>,
        matchedByFormerName: Map<String, Set<String>>,
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

        renamedChanges
            .sortedWith(compareBy({ it.typeName }, { it.after.name }))
            .forEach { change ->
                clauses += "type '${change.typeName}' renamed property '${change.before.name}' to " +
                    "'${change.after.name}' and narrowed it " +
                    "(${describe(change.before)} -> ${describe(change.after)})"
            }

        matchedByFormerName.forEach { (mentionType, currentNames) ->
            clauses += "mention type '$mentionType' is a declared former name of " +
                "[${currentNames.sorted().joinToString(", ")}]"
        }

        return "Schema drift '$fromSchema' → '$toSchema': ${clauses.joinToString("; ")}"
    }

    /** A property signature as a person would read it: `string ONE`, `Company LIST`. */
    private fun describe(signature: PropertySignature): String =
        "${signature.type.ifEmpty { signature.kind.name.lowercase() }} ${signature.cardinality}"

    companion object {

        /**
         * Value type promotions this policy accepts as safe, keyed by the older type name.
         *
         * The names are the ones a stamp actually carries: `PropertySignature.of` copies the
         * dictionary's type string verbatim, and for a JVM-reflected type that string is
         * `Class.getSimpleName()`. So a Kotlin `Int` field renders as `int` and a nullable `Int?` as
         * `Integer`. `DriftQuarantinePolicyTest` pins all four pairs against signatures rendered
         * from real declarations, so a rendering change upstream fails the build instead of quietly
         * emptying this list.
         *
         * Iceberg defines two of these as safe column promotions, `int` → `long` and `float` →
         * `double`; the boxed pair is the same two promotions as a JVM dictionary spells them. The
         * reason carries over: every value of the older type has an exact representation in the
         * newer one, so nothing already written needs rewriting or can fail to read back.
         *
         * Primitive to primitive and boxed to boxed only. `int` → `Long` is a boxing change and
         * `Integer` → `long` a nullability change; both alter what the property can hold beyond the
         * numeric range, so neither is here. Narrowing is never safe, so no pair appears reversed.
         *
         * Experimental: the list may grow before 1.0.
         */
        @JvmField
        val SAFE_TYPE_WIDENINGS: Map<String, String> = java.util.Collections.unmodifiableMap(
            linkedMapOf(
                "int" to "long",
                "float" to "double",
                "Integer" to "Long",
                "Float" to "Double",
            ),
        )
    }
}
