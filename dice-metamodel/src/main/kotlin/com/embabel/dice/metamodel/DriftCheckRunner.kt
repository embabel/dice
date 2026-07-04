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
package com.embabel.dice.metamodel

import com.embabel.dice.proposition.PropositionRepository
import org.slf4j.LoggerFactory

/**
 * Summary of a single [DriftCheckRunner.run] call.
 *
 * @property dryRun Whether this was a preview run (no propositions were quarantined).
 * @property schemaName The declared schema name the check was performed against.
 * @property driftedEntityTypes Entity type names observed in the graph but never declared.
 * @property driftedRelationshipTypes Relationship type names observed but never declared.
 * @property quarantinedCount How many propositions were newly quarantined. Always 0 on a dry run
 *   or when there was no entity-type drift.
 * @property reportRef The [DriftReport] this run persisted — every run persists one, even a clean one.
 */
data class DriftCheckResult(
    val dryRun: Boolean,
    val schemaName: String,
    val driftedEntityTypes: Set<String>,
    val driftedRelationshipTypes: Set<String>,
    val quarantinedCount: Int,
    val reportRef: DriftReport,
) {
    /** `true` when the graph contained any type or relationship never declared. */
    val hasDrift: Boolean get() = driftedEntityTypes.isNotEmpty() || driftedRelationshipTypes.isNotEmpty()
}

/**
 * Runs a drift check: snapshots what a live graph actually contains, compares it against the
 * declared schema, records the observation, and — opt-in — quarantines propositions whose
 * mentions reference types the graph has but the schema doesn't.
 *
 * Mirrors [com.embabel.dice.projection.memory.CollectorRunner]'s shape: dry-run by default, no
 * scheduling of its own (a consumer schedules calls to [run]), and every run leaves a durable
 * record — "checked and found nothing" has to be as retrievable as "checked and found drift".
 */
interface DriftCheckRunner {

    /**
     * Snapshot, diff, persist, and — if [dryRun] is `false` and drift touched any entity type —
     * quarantine.
     *
     * @param dryRun When `true` (the default), the check runs and its [DriftReport] is persisted,
     *   but no proposition is touched. When `false`, propositions whose mentions reference a
     *   drifted entity type are handed to the configured [DriftQuarantinePolicy] and any it flags
     *   are persisted as quarantined.
     * @return A [DriftCheckResult] summarizing what was found and (if live) what was quarantined.
     */
    fun run(dryRun: Boolean = true): DriftCheckResult
}

/**
 * Default [DriftCheckRunner]. Stateless and safe to call repeatedly or concurrently for different
 * schemas; concurrent checks of the *same* schema aren't corrupting (each is a distinct, fully
 * captured snapshot) but are wasteful — serialize at the scheduling layer if that matters.
 *
 * @param observedSchemaSource Snapshots what the live graph actually contains.
 * @param declaredSchemaSource Supplies the schema as declared.
 * @param differ Compares the declared schema against the observed one.
 * @param store Durable store for the persisted [DriftReport] (and, separately, schema version
 *   history — this runner only writes reports, not versions).
 * @param quarantinePolicy Decides which drift-affected propositions to quarantine. Only consulted
 *   on a live (non-dry) run with entity-type drift; this runner never reimplements its decision,
 *   only feeds it a diff and persists what it decides.
 * @param propositionRepository Read the candidate propositions from, and save quarantine decisions to.
 */
class DefaultDriftCheckRunner(
    private val observedSchemaSource: ObservedSchemaSource,
    private val declaredSchemaSource: DeclaredSchemaSource,
    private val differ: DeclaredObservedDiffer,
    private val store: MetamodelStore,
    private val quarantinePolicy: DriftQuarantinePolicy,
    private val propositionRepository: PropositionRepository,
) : DriftCheckRunner {

    private val logger = LoggerFactory.getLogger(DefaultDriftCheckRunner::class.java)

    override fun run(dryRun: Boolean): DriftCheckResult {
        val observed = observedSchemaSource.observe()
        val declared = declaredSchemaSource.declare()

        val diff = differ.diffAgainstObserved(
            declared = declared.version,
            declaredRelationshipTypeNames = declared.relationshipTypeNames,
            observed = observed,
        )

        val report = DriftReport(
            schemaName = declared.version.schemaName,
            versionHash = declared.version.contentHash,
            driftingEntityTypes = diff.driftedEntityTypes,
            driftingRelationshipTypes = diff.driftedRelationshipTypes,
            capturedAt = observed.capturedAt,
        )
        // Persisted unconditionally: a zero-drift check is itself a fact worth having on record,
        // not just a no-op.
        store.saveDriftReport(report)

        val quarantinedCount = if (!dryRun && diff.driftedEntityTypes.isNotEmpty()) {
            quarantineDriftedEntityTypes(declared.version, diff.driftedEntityTypes)
        } else {
            0
        }

        logger.info(
            "Drift check for '{}' complete (dryRun={}): {} drifted entity type(s), {} drifted " +
                "relationship type(s), {} quarantined",
            declared.version.schemaName,
            dryRun,
            diff.driftedEntityTypes.size,
            diff.driftedRelationshipTypes.size,
            quarantinedCount,
        )

        return DriftCheckResult(
            dryRun = dryRun,
            schemaName = declared.version.schemaName,
            driftedEntityTypes = diff.driftedEntityTypes,
            driftedRelationshipTypes = diff.driftedRelationshipTypes,
            quarantinedCount = quarantinedCount,
            reportRef = report,
        )
    }

    /**
     * Delegates to [quarantinePolicy] and persists whatever it decides to quarantine.
     *
     * [DriftQuarantinePolicy.evaluate] takes a [MetamodelDiff] (a comparison between two *declared*
     * versions) keyed off [MetamodelDiff.removedEntityTypes], but what we have here is a
     * [DeclaredObservedDiff] (declared vs. a live observation) — a different comparison entirely.
     * The two agree on the part that matters to the policy, though: a mention whose type is no
     * longer recognized by the declared schema is orphaned either way, whether that's because the
     * type was removed from a newer declared version or because the graph holds a type that was
     * never declared at all. So we synthesize a [MetamodelDiff] whose only content is
     * [MetamodelChange.EntityTypeRemoved] for each drifted entity type, and hand that to the real
     * policy rather than re-deciding quarantine here. Both `fromVersion` and `toVersion` point at
     * the same declared version — there was no "old declared vs. new declared" transition, so
     * they're only there to give the policy's human-readable reason string something to name.
     */
    private fun quarantineDriftedEntityTypes(declaredVersion: MetamodelVersion, driftedEntityTypes: Set<String>): Int {
        val syntheticDiff = MetamodelDiff(
            fromVersion = declaredVersion,
            toVersion = declaredVersion,
            changes = driftedEntityTypes.sorted().map { MetamodelChange.EntityTypeRemoved(it) },
        )
        val propositions = propositionRepository.findAll()
        val result = quarantinePolicy.evaluate(syntheticDiff, propositions)
        result.quarantined.forEach { decision -> propositionRepository.save(decision.proposition) }
        return result.quarantined.size
    }
}
