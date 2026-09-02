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
package com.embabel.dice.spi

import com.embabel.agent.core.Cardinality
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.DeclaredSchema
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.PropertySignature
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
 * ## Two spellings of one type name
 *
 * A declared name can be fully qualified where the data is simple. A JVM-backed type is declared as
 * `com.example.Person`, extraction records the mention as `Person`, and the graph writes `Person` as
 * the label. Matching on the declared spelling alone would read a lossy change on
 * `com.example.Person` as touching nothing at all, and a schema that dropped the type outright would
 * leave every proposition it stranded looking healthy.
 *
 * So every name this policy matches on — removed types, types that lost shape, declared former names
 * — is registered under both spellings: the name as declared, and the label it writes onto a node
 * (`DeclaredSchema.ownLabelOf`). This is the same cut the declared-vs-observed comparison makes, so
 * the two halves of a drift check agree about which type is which. Two declared types in different
 * packages share one label, and a graph can't tell them apart either, so a mention under that label
 * is checked against both.
 *
 * Matching a mention under the other spelling of its own type is ordinary matching, so it never
 * shows up in the reason as a former name.
 *
 * ## Value types
 *
 * A changed value type counts as lossy in both directions except for the four promotions in
 * [SAFE_TYPE_WIDENINGS]. Outside those, we know the declared type names moved and we don't know how
 * the backend stored the values or whether the new type can read the old ones, and guessing wrong in
 * the permissive direction leaves unreadable data looking healthy. A changed reference target is
 * always lossy: it names a different entity type, which is not a promotion of anything.
 *
 * Quarantining moves the proposition to [PropositionStatus.QUARANTINED], annotates it under
 * [DiceMetadataKeys.QUARANTINE_REASON], and records the status it came from. All of that produces an
 * immutable copy; the original is never mutated, and persisting the copies is the caller's job.
 *
 * A proposition an earlier sweep already quarantined comes back as
 * [QuarantineDecision.AlreadyQuarantined], untouched, with its original reason preserved and outside
 * the conforming bucket. That holds for any diff, an empty one included, because being already
 * quarantined is a fact about the proposition — its status says so.
 *
 * ## Pinned propositions
 *
 * A pinned proposition a lossy change would otherwise catch is never flipped to `STALE`. Pinning is
 * DICE's cross-cutting promise that a proposition resists reclamation, the same promise the decay
 * collector, the sweep policy and contradiction resolution already honor, and quarantine is one more
 * reclamation path that has to keep it. The match still gets reported, as
 * [QuarantineDecision.Protected], so an operator can see what the schema change would have caught
 * without the proposition itself being touched. A proposition an earlier sweep already quarantined
 * before it was pinned is unaffected by this: it still comes back as
 * [QuarantineDecision.AlreadyQuarantined], since idempotency is checked first.
 *
 * Rename awareness and the widening allow-list are experimental: behavior may change before 1.0.
 */
class MentionTypeDriftQuarantinePolicy : DriftQuarantinePolicy {

    private val logger = LoggerFactory.getLogger(MentionTypeDriftQuarantinePolicy::class.java)

    override fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult {
        val signals = lossySignalsOf(diff)

        // There is deliberately no "nothing lossy, so everything conforms" shortcut here. Whether a
        // proposition is already quarantined is a fact about the proposition and doesn't depend on
        // the diff, so a shortcut would report an earlier sweep's quarantined records as Conforming
        // on any check that found nothing new. Drift checks run on a schedule and most runs find
        // nothing, so that would be the common case. Every proposition goes down one code path.

        val conforming = mutableListOf<QuarantineDecision.Conforming>()
        val quarantined = mutableListOf<QuarantineDecision.Quarantined>()
        val protected = mutableListOf<QuarantineDecision.Protected>()
        // Former names that actually matched something, for the summary line. The map above holds
        // every former name the declaration knows; this holds the ones a proposition was labelled
        // with, which is what an operator reading the log is trying to find out.
        val formerNamesMatched = sortedSetOf<String>()
        // Propositions left alone because a previous sweep already quarantined them. Their own
        // bucket, so conforming.size counts only clean ones.
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

                // Note that this mention type isn't the schema's own name for the type it hit. Two
                // spellings of one name don't count: a mention of `Person` against a declared
                // `com.example.Person` is the same type, and calling that a former name would put a
                // baffling line in the reason.
                fun recordFormerName(schemaName: String) {
                    if (DeclaredSchema.ownLabelOf(schemaName) != DeclaredSchema.ownLabelOf(mentionType)) {
                        matchedByFormerName.getOrPut(mentionType) { sortedSetOf() } += schemaName
                        formerNamesMatched += mentionType
                    }
                }

                // Removals resolve through the OLDER version's aliases. A removed type takes its
                // former names down with it, and the newer version has no record they were ever
                // this type's, so the surviving-type map can't see them.
                val removals = sortedSetOf<String>()
                removals += signals.removedTypesBySpelling[mentionType].orEmpty()
                removals += signals.formerNamesOfRemovedTypes[mentionType].orEmpty()
                if (removals.isNotEmpty()) {
                    removedHit += removals
                    affected = true
                    removals.forEach(::recordFormerName)
                }

                for (currentName in setOf(mentionType) + signals.currentNamesByFormerName[mentionType].orEmpty()) {
                    var lossyUnderThisName = false
                    signals.lossyModified[currentName]?.let { lossyHit += it; lossyUnderThisName = true }
                    signals.narrowedProperties[currentName]?.let { narrowedHit += it; lossyUnderThisName = true }
                    signals.narrowedRenames[currentName]?.let { renamedHit += it; lossyUnderThisName = true }
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

            // Pinning promises cross-cutting immunity from reclamation, the same promise the decay
            // collector, the sweep policy and contradiction resolution already honor. A pinned match
            // is reported so an operator can still see what the schema change would have caught, but
            // the proposition itself is never touched.
            if (proposition.pinned) {
                logger.debug(
                    "Protecting pinned proposition (id={}) from quarantine: {}",
                    proposition.id, reason,
                )
                protected += QuarantineDecision.Protected(
                    proposition = proposition,
                    reason = reason,
                    affectedMentionTypes = affectedTypes,
                )
                continue
            }

            // Where the proposition came from, written onto the copy so a release can put it back
            // exactly there. A quarantined proposition can have arrived from any status, so a
            // release with nothing recorded here could only guess.
            val previousStatus = proposition.status
            val flagged = proposition
                .withStatus(PropositionStatus.QUARANTINED)
                .withMetadataValue(DiceMetadataKeys.QUARANTINE_REASON, reason)
                .withMetadataValue(DriftQuarantineKeys.PREVIOUS_STATUS, previousStatus.name)

            logger.debug("Quarantining proposition '{}' (id={}): {}", proposition.text, proposition.id, reason)

            quarantined += QuarantineDecision.Quarantined(
                proposition = flagged,
                reason = reason,
                affectedMentionTypes = affectedTypes,
                previousStatus = previousStatus,
            )
        }

        logger.info(
            "Drift quarantine sweep complete: {} conforming, {} already quarantined from a prior sweep, " +
                "{} newly quarantined, {} protected by pin (removed types: {}, lossy-modified types: {}, " +
                "narrowed-property types: {}, narrowed renamed-property types: {}, " +
                "former names data was matched under: {})",
            conforming.size,
            alreadyQuarantined.size,
            quarantined.size,
            protected.size,
            diff.removedEntityTypes,
            signals.lossyModified.keys,
            signals.narrowedProperties.keys,
            signals.narrowedRenames.keys,
            formerNamesMatched,
        )

        return QuarantineResult(
            conforming = conforming,
            quarantined = quarantined,
            alreadyQuarantined = alreadyQuarantined,
            protected = protected,
        )
    }

    /**
     * Every mention type name this policy could match under [diff], which is what a bounded sweep
     * asks its store for.
     *
     * It is read off exactly the same [lossySignals] the evaluation uses, so the two can't drift
     * apart: a name that would quarantine a proposition is a name a sweep asks for. Both spellings
     * of every name are here, since a graph writes the simple label for a fully qualified
     * declaration and a sweep must ask for what the store actually holds.
     */
    override fun candidateMentionTypes(diff: MetamodelDiff): Set<String> {
        val signals = lossySignalsOf(diff)
        val lossyNames = signals.lossyModified.keys + signals.narrowedProperties.keys + signals.narrowedRenames.keys
        val formerNamesOfLossyTypes = signals.currentNamesByFormerName
            .filterValues { currentNames -> currentNames.any { it in lossyNames } }
            .keys
        return java.util.Collections.unmodifiableSet(
            sortedSetOf<String>().apply {
                addAll(signals.removedTypesBySpelling.keys)
                addAll(signals.formerNamesOfRemovedTypes.keys)
                addAll(lossyNames)
                addAll(formerNamesOfLossyTypes)
            },
        )
    }

    /**
     * The lossy parts of a diff, gathered once and keyed by every spelling a mention could use.
     *
     * @property removedTypesBySpelling Each spelling of a removed type name, pointing at the
     *   declared name (or names) it stands for.
     * @property lossyModified Types whose name survived and which lost labels or whole properties.
     *   Lossy, because a mention may have relied on a label or property that is now gone.
     * @property narrowedProperties Types carrying a property that kept its name and narrowed.
     * @property narrowedRenames Types carrying a property renamed and narrowed in the same step. The
     *   rename is harmless; the shape move underneath it is judged by the same rule as any other.
     * @property currentNamesByFormerName Every name a surviving type has gone by, pointing at the
     *   name its changes are reported under.
     * @property formerNamesOfRemovedTypes Every name a removed type had gone by, pointing at the
     *   removal. Two maps, read off opposite sides of the diff, because a removed type is absent
     *   from the newer side.
     */
    private class LossySignals(
        val removedTypesBySpelling: Map<String, Set<String>>,
        val lossyModified: Map<String, List<MetamodelChange.EntityTypeModified>>,
        val narrowedProperties: Map<String, List<MetamodelChange.PropertySignatureChanged>>,
        val narrowedRenames: Map<String, List<MetamodelChange.PropertyRenamed>>,
        val currentNamesByFormerName: Map<String, Set<String>>,
        val formerNamesOfRemovedTypes: Map<String, Set<String>>,
    )

    private fun lossySignalsOf(diff: MetamodelDiff): LossySignals = LossySignals(
        removedTypesBySpelling = bySpelling(diff.removedEntityTypes),
        lossyModified = diff.modifiedEntityTypes
            .filter { it.removedLabels.isNotEmpty() || it.removedProperties.isNotEmpty() }
            .groupBySpelling { it.typeName },
        narrowedProperties = diff.propertySignatureChanges
            .filter { isNarrowing(it.before, it.after) }
            .groupBySpelling { it.typeName },
        narrowedRenames = diff.renamedProperties
            .filter { isNarrowing(it.before, it.after) }
            .groupBySpelling { it.typeName },
        currentNamesByFormerName = formerTypeNames(diff),
        formerNamesOfRemovedTypes = formerNamesOfRemovedTypes(diff),
    )

    /**
     * Whether a proposition is one an earlier sweep already handled: its status is
     * [PropositionStatus.QUARANTINED].
     *
     * The status is the whole answer. Quarantine has a status of its own, so nothing else in DICE
     * can put a proposition there and nothing else can take it out — a proposition made stale by
     * ordinary decay is `STALE` and still a live candidate here, and one whose quarantine reason
     * was edited away by hand is still held, because the hold is the status. Release is what lets
     * one back through evaluation.
     */
    private fun isAlreadyQuarantined(proposition: Proposition): Boolean =
        proposition.status == PropositionStatus.QUARANTINED

    /**
     * Every name an entity type has gone by, mapped to what that type is called now, under every
     * spelling a mention could carry.
     *
     * Read off the **newer version's whole declared alias map**, and never off only the renames this
     * diff happens to contain. A rename and a loss usually land in different releases: stamp 2
     * renames `Person` to `Human`, stamp 3 drops a property, and the stamp-2-to-stamp-3 diff holds
     * no rename at all while the graph still holds nodes labelled `Person` and the declaration still
     * says `Human` used to be one. Keying off the diff's renames would let that loss pass over every
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
            for (formerName in formerNames) {
                for (spelling in spellingsOf(formerName) - spellingsOf(typeName)) {
                    byFormerName.getOrPut(spelling) { sortedSetOf() } += typeName
                }
            }
        }
        // A diff assembled by hand, with no differ involved, can carry a rename whose old name the
        // stamp's alias map doesn't hold.
        for (rename in diff.renamedEntityTypes) {
            for (spelling in spellingsOf(rename.before)) {
                byFormerName.getOrPut(spelling) { sortedSetOf() } += rename.after
            }
        }
        return byFormerName
    }

    /**
     * Every name a **removed** type had gone by, mapped to the removed type it belonged to, under
     * every spelling a mention could carry.
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
     * by that exact name. Data under it is judged as that type's, like any other mention. The
     * exclusion goes by spelling too: a graph writing `Person` can't tell a retired `Person` from a
     * live `com.example.Person`.
     */
    private fun formerNamesOfRemovedTypes(diff: MetamodelDiff): Map<String, Set<String>> {
        val removed = diff.removedEntityTypes
        if (removed.isEmpty()) return emptyMap()

        val stillDeclared = diff.toVersion.entityTypeNames.flatMapTo(mutableSetOf()) { spellingsOf(it) }
        val byFormerName = mutableMapOf<String, MutableSet<String>>()
        for (typeName in removed) {
            val formerNames = diff.fromVersion.entityTypeAliases[typeName].orEmpty()
            for (formerName in formerNames) {
                for (spelling in spellingsOf(formerName) - spellingsOf(typeName) - stillDeclared) {
                    byFormerName.getOrPut(spelling) { sortedSetOf() } += typeName
                }
            }
        }
        return byFormerName
    }

    /**
     * The spellings one declared type name can appear under in a graph: the name itself, and the
     * label that name writes onto a node.
     *
     * A stamp holds `com.example.Person` for a JVM-backed type while extraction records the mention
     * as `Person`, so matching either spelling alone reads a lossy change as touching nothing. This
     * is the same cut `DeclaredObservedDiffer` makes on the declared side of a drift comparison, so
     * the two halves of a drift check agree about which type is which.
     */
    private fun spellingsOf(typeName: String): Set<String> =
        setOf(typeName, DeclaredSchema.ownLabelOf(typeName))

    /** Each spelling of each name, pointing at the declared name (or names) it stands for. */
    private fun bySpelling(names: Collection<String>): Map<String, Set<String>> {
        val bySpelling = mutableMapOf<String, MutableSet<String>>()
        for (name in names) {
            for (spelling in spellingsOf(name)) {
                bySpelling.getOrPut(spelling) { sortedSetOf() } += name
            }
        }
        return bySpelling
    }

    /**
     * Group changes under every spelling of the type name [typeNameOf] reads off them. A list per
     * key, since two declared types in different packages share one label and a graph can't tell
     * them apart.
     */
    private fun <T> List<T>.groupBySpelling(typeNameOf: (T) -> String): Map<String, List<T>> =
        flatMap { change -> spellingsOf(typeNameOf(change)).map { it to change } }
            .groupBy({ it.first }, { it.second })


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
