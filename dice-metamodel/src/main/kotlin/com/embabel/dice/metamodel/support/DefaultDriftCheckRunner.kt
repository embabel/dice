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

import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DeclaredObservedDiffer
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckResult
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.MetamodelDiffer
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.metamodel.SweptBaselineStore
import org.slf4j.LoggerFactory

/**
 * The shipped [DriftCheckRunner]. It sequences the collaborators and makes no decisions of its own:
 * the comparisons belong to the two differs, and this class puts them in an order that leaves a
 * coherent record behind.
 *
 * Stateless, so calling it repeatedly or for different schemas at once is fine. Two concurrent
 * checks of the same schema don't corrupt anything, since each captures its own complete snapshot,
 * but they duplicate work; serialize at the scheduling layer if that matters.
 *
 * ## Nothing here writes to a proposition
 *
 * This class reads, compares, and writes a [DriftReport]. It holds no quarantine policy and no
 * proposition store, so there is no path through it that can move a proposition to `STALE` or move
 * the swept baseline. Acting on a check is a deliberate host step through
 * `DriftSweepCapable`, and the baseline moves when the host says a sweep finished, through
 * [SweptBaselineStore.markSwept].
 *
 * Two writes still happen, and both are records of the check itself: the declared version stamp, and
 * the report.
 *
 * ## The version is stamped before the report is written
 *
 * The declared version is saved to [versionStore] on every run, before the report is written, even
 * when it hasn't changed and re-saves onto its own key. A [DriftReport] records the
 * [MetamodelVersion.contentHash] it was judged against, and that hash is only useful when it
 * resolves back to a real stamp through [MetamodelVersionStore.findVersion]. Stamping last, or only
 * when the schema moved, would leave the first check after a schema change pointing at a hash
 * nothing has recorded. `saveVersion` upserts on `(schemaName, contentHash)`, so doing it every run
 * costs one idempotent write.
 *
 * This is a history write, and it never moves the reconciled baseline. That is a promise
 * [SweptBaselineStore] makes on the store's side, and it is what lets a check stamp freely without
 * retiring a lossy declared change nobody has acted on yet.
 *
 * ## Both comparisons reach the report
 *
 * A check runs two comparisons and reports both:
 *
 * - **Declared vs. observed** ([differ]): what the live graph holds that this declaration doesn't
 *   recognise. [DriftReport.driftedEntityTypes] records it, and it is blind to a property that
 *   quietly narrowed or disappeared on a type the graph and the declaration still agree on.
 * - **Declared vs. previous declared** ([metamodelDiffer]): what changed in the declaration itself
 *   since [SweptBaselineStore.sweptVersion] — a removed property, a narrowed cardinality, a whole
 *   type dropped — regardless of what the graph currently holds. [DriftReport.declaredDiff] records
 *   it, and it is `null` when no sweep has ever completed for this schema, or when the version store
 *   tracks no baseline at all.
 *
 * The second comparison is what catches a schema edit that silently strands previously-extracted
 * data (a value type narrowing from `long` to `int`, say) when the graph itself never drifts out of
 * step with the *new* declaration. Both go into the report, so
 * [DriftCheckResult.quarantineDiff] can hand back the complete comparison a sweep would evaluate —
 * a check that reported only the graph-truth half could read clean while a sweep on the same state
 * quarantined.
 *
 * ## The baseline is read through an optional capability
 *
 * [versionStore] supplies the baseline only when it is a [SweptBaselineStore]. A store that tracks
 * no baseline leaves [DriftReport.declaredDiff] `null` and the check reports the graph-truth half
 * alone, which is the honest answer: a baseline read off write order would move on ordinary stamping
 * and quietly retire changes nothing had swept for.
 *
 * @param declaredSchemaSource Supplies the schema as declared. Read first, so everything downstream
 *   is judged against one declaration.
 * @param versionStore Where the declared stamp is recorded each run, so report hashes always
 *   resolve, and where the reconciled baseline is read from when the store tracks one.
 * @param observedSchemaSource Snapshots what the live graph actually contains.
 * @param differ Compares the declaration against the observation.
 * @param metamodelDiffer Compares the declaration against its reconciled baseline. The same
 *   [StructuralMetamodelDiffer] instance ordinarily implements both this and [differ].
 * @param driftReportStore Durable log the report is written to, on every run.
 */
class DefaultDriftCheckRunner(
    private val declaredSchemaSource: DeclaredSchemaSource,
    private val versionStore: MetamodelVersionStore,
    private val observedSchemaSource: ObservedSchemaSource,
    private val differ: DeclaredObservedDiffer,
    private val metamodelDiffer: MetamodelDiffer,
    private val driftReportStore: DriftReportStore,
) : DriftCheckRunner {

    private val logger = LoggerFactory.getLogger(DefaultDriftCheckRunner::class.java)

    override fun run(contextId: ContextId?): DriftCheckResult {
        val declared = declaredSchemaSource.declare()

        // The reconciled baseline, read before this run's own history write below so it can never
        // read back its own stamp. Null when no sweep has ever completed for this schema, and null
        // for the whole life of a store that tracks no baseline. See the class doc.
        val previousVersion = (versionStore as? SweptBaselineStore)?.sweptVersion(declared.version.schemaName)

        // Every run stamps its declaration into history so a report's hash always resolves -- see
        // the class doc. This is a history write and leaves the baseline alone.
        versionStore.saveVersion(declared.version)

        val observed = observedSchemaSource.observe(contextId)
        val diff = differ.diffAgainstObserved(declared = declared, observed = observed)

        // What moved in the declaration since the reconciled baseline -- property removals, narrowed
        // cardinality, a whole type dropped -- which diff above never sees, since it only compares
        // the current declaration against the graph as it stands right now.
        val declaredDiff = previousVersion?.let { metamodelDiffer.diff(it, declared.version) }

        val report = DriftReport(
            schemaName = declared.version.schemaName,
            versionHash = declared.version.contentHash,
            driftedEntityTypes = diff.driftedEntityTypes,
            driftedRelationshipTypes = diff.driftedRelationshipTypes,
            // The instant the graph was looked at, which is a different instant from this write: the
            // report is a statement about the snapshot.
            capturedAt = observed.capturedAt,
            contextId = contextId,
            declaredDiff = declaredDiff,
        )
        // Written on every run, including checks that found nothing.
        driftReportStore.saveDriftReport(report)

        logger.info(
            "Drift check for '{}' complete (contextId={}): {} drifted entity type(s), " +
                "{} drifted relationship type(s), {} declared change(s) since the swept baseline",
            declared.version.schemaName,
            contextId?.value,
            diff.driftedEntityTypes.size,
            diff.driftedRelationshipTypes.size,
            declaredDiff?.changes?.size ?: 0,
        )

        return DriftCheckResult(report = report, declaredVersion = declared.version)
    }
}
