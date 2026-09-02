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
package com.embabel.dice.governance

import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.DriftCheckResult
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.PropertySignature
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.jetbrains.annotations.ApiStatus
import java.time.Instant

/**
 * The shapes an operator reads. Every field here is a String, a number, a boolean, or another shape
 * in this file, so nothing about how governance stores its state reaches a caller: no
 * [MetamodelVersion], no [MetamodelDiff], no [DriftReport], no [Proposition]. The `from()` mappers
 * take those domain objects as input and emit only these.
 *
 * Instants are rendered as ISO-8601 text, which is what an operator reads and what a JSON client
 * parses without a date module.
 */

/**
 * What a schema change did to one property.
 *
 * @property typeName The entity type the property belongs to.
 * @property propertyName The property's name before the change.
 * @property renamedTo The property's new name when the change renamed it, `null` when the name held.
 * @property before How the property looked before, as a person would read it: `string ONE`,
 *   `Company LIST`.
 * @property after How it looks now, the same way.
 */
@ApiStatus.Experimental
data class PropertyChangeDto(
    val typeName: String,
    val propertyName: String,
    val renamedTo: String?,
    val before: String,
    val after: String,
)

/**
 * An entity type that changed name.
 *
 * @property before The name it went by.
 * @property after The name it goes by now.
 */
@ApiStatus.Experimental
data class TypeRenameDto(
    val before: String,
    val after: String,
)

/**
 * A comparison of two declared schema versions, flattened to names an operator can read.
 *
 * The two version hashes are here so a report can be tied back to the exact stamps it was judged
 * between; everything else is the change itself, grouped by what it does to stored data.
 *
 * @property fromVersionHash The older stamp's content hash.
 * @property toVersionHash The newer stamp's content hash.
 * @property empty `true` when the two versions describe the same shape.
 * @property addedEntityTypes Entity types the newer version declares and the older one did not.
 * @property removedEntityTypes Entity types the older version declared and the newer one drops.
 * @property modifiedEntityTypes Names of types that kept their name and gained or lost labels or
 *   properties.
 * @property renamedEntityTypes Types that changed name, with both spellings.
 * @property changedProperties Properties whose shape moved, whether or not they were renamed too.
 * @property addedRelationships Rendered `From-[name]->To` descriptors the newer version adds.
 * @property removedRelationships Descriptors the newer version drops.
 */
@ApiStatus.Experimental
data class MetamodelDiffDto(
    val fromVersionHash: String,
    val toVersionHash: String,
    val empty: Boolean,
    val addedEntityTypes: List<String>,
    val removedEntityTypes: List<String>,
    val modifiedEntityTypes: List<String>,
    val renamedEntityTypes: List<TypeRenameDto>,
    val changedProperties: List<PropertyChangeDto>,
    val addedRelationships: List<String>,
    val removedRelationships: List<String>,
) {

    companion object {

        @JvmStatic
        fun from(diff: MetamodelDiff): MetamodelDiffDto = MetamodelDiffDto(
            fromVersionHash = diff.fromVersion.contentHash,
            toVersionHash = diff.toVersion.contentHash,
            empty = diff.isEmpty,
            addedEntityTypes = diff.addedEntityTypes.sorted(),
            removedEntityTypes = diff.removedEntityTypes.sorted(),
            modifiedEntityTypes = diff.modifiedEntityTypes.map { it.typeName }.sorted(),
            renamedEntityTypes = diff.renamedEntityTypes.map { TypeRenameDto(it.before, it.after) },
            changedProperties = diff.propertySignatureChanges.map { change ->
                PropertyChangeDto(
                    typeName = change.typeName,
                    propertyName = change.propertyName,
                    renamedTo = null,
                    before = describe(change.before),
                    after = describe(change.after),
                )
            } + diff.renamedProperties.map { change ->
                PropertyChangeDto(
                    typeName = change.typeName,
                    propertyName = change.before.name,
                    renamedTo = change.after.name,
                    before = describe(change.before),
                    after = describe(change.after),
                )
            },
            addedRelationships = diff.addedRelationships.sorted(),
            removedRelationships = diff.removedRelationships.sorted(),
        )

        /**
         * A property signature as a person would read it: `string ONE`, `Company LIST`. The same
         * rendering the quarantine reason uses, so an operator comparing a report against a
         * proposition's reason sees one wording.
         */
        private fun describe(signature: PropertySignature): String =
            "${signature.type.ifEmpty { signature.kind.name.lowercase() }} ${signature.cardinality}"
    }
}

/**
 * One drift check as it was written down.
 *
 * @property schemaName The declared schema's name at check time.
 * @property versionHash The stamp the check was judged against.
 * @property contextId The context the check covered, or `null` for a whole-graph check.
 * @property capturedAt When the observation was taken, ISO-8601.
 * @property driftedEntityTypes Entity types the graph held that the declaration never named.
 * @property driftedRelationshipTypes Relationship types observed with no declaration.
 * @property hasDrift `true` when the graph held anything undeclared.
 * @property declaredDiff How the declaration itself moved since the last completed sweep, or `null`
 *   when no baseline had been reconciled yet.
 */
@ApiStatus.Experimental
data class DriftReportDto(
    val schemaName: String,
    val versionHash: String,
    val contextId: String?,
    val capturedAt: String,
    val driftedEntityTypes: List<String>,
    val driftedRelationshipTypes: List<String>,
    val hasDrift: Boolean,
    val declaredDiff: MetamodelDiffDto?,
) {

    companion object {

        @JvmStatic
        fun from(report: DriftReport): DriftReportDto = DriftReportDto(
            schemaName = report.schemaName,
            versionHash = report.versionHash,
            contextId = report.contextId?.value,
            capturedAt = report.capturedAt.toString(),
            driftedEntityTypes = report.driftedEntityTypes.toList(),
            driftedRelationshipTypes = report.driftedRelationshipTypes.toList(),
            hasDrift = report.hasDrift,
            declaredDiff = report.declaredDiff?.let { MetamodelDiffDto.from(it) },
        )
    }
}

/**
 * What the application declares right now, and where that sits against what governance has recorded.
 *
 * [stamped] and [sweptVersionHash] are the two questions an operator asks before deciding to sweep:
 * has this exact declaration ever been recorded, and which declaration did the last completed sweep
 * reconcile against. A [sweptVersionHash] differing from [contentHash] says there is a declared
 * change nobody has acted on.
 *
 * @property schemaName The declared schema's name.
 * @property contentHash The declaration's content hash — its identity.
 * @property entityTypeNames The governed entity type names, sorted.
 * @property relationshipNames Rendered `From-[name]->To` descriptors the governed types declare.
 * @property stamped Whether the version store already holds this exact hash.
 * @property sweptVersionHash The hash the last completed sweep reconciled against. `null` when no
 *   sweep has completed, and also when the version store tracks no baseline at all.
 */
@ApiStatus.Experimental
data class DeclaredVersionDto(
    val schemaName: String,
    val contentHash: String,
    val entityTypeNames: List<String>,
    val relationshipNames: List<String>,
    val stamped: Boolean,
    val sweptVersionHash: String?,
) {

    companion object {

        @JvmStatic
        fun from(
            version: MetamodelVersion,
            stamped: Boolean,
            sweptVersion: MetamodelVersion?,
        ): DeclaredVersionDto = DeclaredVersionDto(
            schemaName = version.schemaName,
            contentHash = version.contentHash,
            entityTypeNames = version.entityTypeNames,
            relationshipNames = version.relationshipNames,
            stamped = stamped,
            sweptVersionHash = sweptVersion?.contentHash,
        )
    }
}

/**
 * Everything one drift check found, including the merged comparison a sweep would evaluate.
 *
 * A check reports and moves nothing, so this is the whole preview an operator gets before deciding.
 * It carries both halves the check compared — the graph-truth half in [driftedEntityTypes] and
 * [driftedRelationshipTypes], the declaration half in [declaredDiff] — and [sweepImpact], which is
 * those two merged into the single diff a sweep evaluates propositions against. A response missing
 * [declaredDiff] could read clean while a sweep on the same state quarantined.
 *
 * @property schemaName The declared schema's name at check time.
 * @property versionHash The stamp the check ran against.
 * @property contextId The context the check covered, or `null` for a whole-graph check.
 * @property capturedAt When the observation was taken, ISO-8601.
 * @property driftedEntityTypes Entity types the graph held that the declaration never named.
 * @property driftedRelationshipTypes Relationship types observed with no declaration.
 * @property hasDrift `true` when the graph held anything undeclared.
 * @property hasAnyChange `true` when either half found something, so a sweep would have work to do.
 * @property declaredDiff How the declaration moved since the last completed sweep, or `null` when
 *   there was no baseline to compare against.
 * @property sweepImpact The merged comparison a sweep evaluates propositions against.
 */
@ApiStatus.Experimental
data class DriftCheckDto(
    val schemaName: String,
    val versionHash: String,
    val contextId: String?,
    val capturedAt: String,
    val driftedEntityTypes: List<String>,
    val driftedRelationshipTypes: List<String>,
    val hasDrift: Boolean,
    val hasAnyChange: Boolean,
    val declaredDiff: MetamodelDiffDto?,
    val sweepImpact: MetamodelDiffDto,
) {

    companion object {

        @JvmStatic
        fun from(result: DriftCheckResult): DriftCheckDto = DriftCheckDto(
            schemaName = result.schemaName,
            versionHash = result.report.versionHash,
            contextId = result.contextId?.value,
            capturedAt = result.report.capturedAt.toString(),
            driftedEntityTypes = result.driftedEntityTypes.toList(),
            driftedRelationshipTypes = result.driftedRelationshipTypes.toList(),
            hasDrift = result.hasDrift,
            hasAnyChange = result.hasAnyChange,
            declaredDiff = result.declaredDiff?.let { MetamodelDiffDto.from(it) },
            sweepImpact = MetamodelDiffDto.from(result.quarantineDiff),
        )
    }
}

/**
 * Where a proposition stands after a release: the state a caller gets back so it can see the hold
 * is gone without a second read.
 *
 * [quarantined] is read off the status, which is what decides whether a proposition is held, and
 * [quarantineReason] off the metadata the release cleared. After a successful release both say the
 * proposition is free: the status is whatever it carried before quarantine, and the reason is gone.
 *
 * @property propositionId The released proposition's id.
 * @property contextId The context it belongs to.
 * @property status The status it carries now, restored from what it had before quarantine.
 * @property quarantined Whether it is still held.
 * @property quarantineReason The explanation still on it, `null` once the release cleared it.
 * @property metadataRevised When its metadata last moved, ISO-8601.
 */
@ApiStatus.Experimental
data class ReleasedPropositionDto(
    val propositionId: String,
    val contextId: String,
    val status: String,
    val quarantined: Boolean,
    val quarantineReason: String?,
    val metadataRevised: String,
) {

    companion object {

        @JvmStatic
        fun from(proposition: Proposition): ReleasedPropositionDto = ReleasedPropositionDto(
            propositionId = proposition.id,
            contextId = proposition.contextIdValue,
            status = proposition.status.name,
            quarantined = proposition.status == PropositionStatus.QUARANTINED,
            quarantineReason = proposition.metadata[DiceMetadataKeys.QUARANTINE_REASON] as? String,
            metadataRevised = proposition.metadataRevised.toString(),
        )
    }
}

/**
 * A request the operator surface refuses before it touches anything: a limit outside its bounds, a
 * blank identifier, a timestamp that will not parse.
 *
 * Its own type, so the REST layer can answer `400` with this message and leave every other failure
 * on the generic path. The message names the bound that was broken, since an operator reading a bare
 * `400` has nothing to correct.
 */
@ApiStatus.Experimental
class GovernanceRequestException(message: String) : RuntimeException(message)

/**
 * Parse an optional ISO-8601 instant supplied by a caller.
 *
 * @param raw The text to parse. Blank or `null` means "no window".
 * @return The parsed instant, or `null` when nothing was supplied.
 * @throws GovernanceRequestException when [raw] holds something that will not parse.
 */
internal fun parseSince(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw.trim())
    } catch (_: java.time.format.DateTimeParseException) {
        throw GovernanceRequestException("since must be an ISO-8601 instant, but was '$raw'")
    }
}
