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
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.metamodel.DeclaredObservedDiffer
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckResult
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.DriftQuarantinePolicy
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelDiffer
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.proposition.PropositionStore
import org.slf4j.LoggerFactory

/**
 * The shipped [DriftCheckRunner]. It sequences the collaborators and makes no decisions of its own:
 * the comparison belongs to the differ and the quarantine call to the policy, and this class puts
 * them in an order that leaves a coherent record behind.
 *
 * Stateless, so calling it repeatedly or for different schemas at once is fine. Two concurrent
 * checks of the same schema don't corrupt anything, since each captures its own complete snapshot,
 * but they duplicate work; serialize at the scheduling layer if that matters.
 *
 * ## The version is stamped before the report is written
 *
 * The declared version is saved to [versionStore] on every run, before the report is written, even
 * when it hasn't changed and re-saves onto its own key. A [DriftReport] records the
 * [MetamodelVersion.contentHash] it was judged against, and that hash is only useful when it
 * resolves back to a real stamp through [MetamodelVersionStore.findVersion]. Stamping last, or only
 * when the schema moved, would leave the first check after a schema change pointing at a hash
 * nothing has recorded. `saveVersion` upserts on `(schemaName, contentHash)`, so doing it every run
 * costs one idempotent write. Every run does this, dry or live — it's a history write, not the diff
 * baseline update below, for any store that tracks [MetamodelVersionStore.sweptVersion]
 * independently of write order (see that method's doc). A store that doesn't override it inherits
 * the interface default, which answers [MetamodelVersionStore.latestVersion], and that method's own
 * contract draws the exact line: saving a stamp that isn't already recorded moves `latestVersion` —
 * and so the forwarded baseline — to it; re-saving a stamp that's already there keeps its original
 * write-order position, leaving `latestVersion`, and the forwarded baseline, unmoved. So on such a
 * store, this write moves the baseline precisely when the current run's declaration is a genuinely
 * new stamp, dry, scoped, or crashed run alike — see [MetamodelVersionStore.sweptVersion]'s doc for
 * what that costs.
 *
 * ## Two sources of drift
 *
 * A live run's quarantine candidates come from two independent comparisons, merged into one diff
 * before the policy ever sees them:
 *
 * - **Declared vs. observed** ([differ]): what the live graph holds that this declaration doesn't
 *   recognise. This is what [DriftReport.driftedEntityTypes] records, and it is blind to a property
 *   that quietly narrowed or disappeared on a type the graph and the declaration still agree on.
 * - **Declared vs. previous declared** ([metamodelDiffer]): what changed in the declaration itself
 *   since [MetamodelVersionStore.sweptVersion] — a removed property, a narrowed cardinality, a whole
 *   type dropped — regardless of what the graph currently holds. `null` when no live sweep has ever
 *   completed for this schema.
 *
 * Without the second comparison, a schema edit that silently strands previously-extracted data
 * (say, a value type narrowing from `long` to `int`) would never reach [quarantinePolicy] at all
 * until the graph itself drifted out of step with the *new* declaration — which, if nothing else
 * changes, is never. [DriftReport] itself is unaffected: it still reports only declared-vs-observed
 * drift, since that is the graph-truth signal an operator watching for undeclared shapes wants; the
 * declared-vs-previous comparison feeds quarantine only.
 *
 * ## The diff baseline only advances when a sweep actually finishes
 *
 * The declared-vs-previous baseline comes from [MetamodelVersionStore.sweptVersion], read before
 * [versionStore]'s history write above, and this class only calls
 * [MetamodelVersionStore.markSwept] to advance it once — after every candidate a **live, unscoped**
 * sweep was going to touch has genuinely been handled. Three things follow, each a real hazard the
 * earlier "save on every run" design had:
 *
 * - A **dry run** decides nothing, so it must not retire a lossy declared change either — the next
 *   run, live or dry, still needs to see it. `run()` with no arguments stays what the class doc for
 *   [DriftCheckRunner] promises: reports, changes nothing.
 * - A run **scoped to one context** only sweeps that context's candidates. Retiring the schema-wide
 *   baseline after it would strand every other context's candidates against a change nothing ever
 *   swept them for. A scoped run still computes and acts on the same diff — that context's
 *   candidates do get quarantined — it just leaves the baseline where it was, so a later run (scoped
 *   to another context, or unscoped) still sees the same declared-vs-previous drift and finishes the
 *   job. The already-quarantined check makes that safe to repeat: nothing already handled gets
 *   touched twice.
 * - A **crash between the history write and the end of the sweep** must not look like a completed
 *   reconciliation. `markSwept` is the last thing this class does, strictly after every quarantined
 *   proposition is saved, so an interrupted run leaves the baseline exactly where it was and the next
 *   run retries the same comparison and finishes the job.
 *
 * [MetamodelVersionStore.sweptVersion] is a different question from `latestVersion`, which the
 * store's own doc covers: `latestVersion` tracks write order and gives the wrong answer once a
 * declaration cycles back to an earlier stamp.
 *
 * @param declaredSchemaSource Supplies the schema as declared. Read first, so everything downstream
 *   is judged against one declaration.
 * @param versionStore Where the declared stamp is recorded each run, so report hashes always
 *   resolve, and where the reconciled baseline is read from and advanced. See "The diff baseline
 *   only advances when a sweep actually finishes" above.
 * @param observedSchemaSource Snapshots what the live graph actually contains.
 * @param differ Compares the declaration against the observation.
 * @param metamodelDiffer Compares the declaration against its reconciled baseline. The same
 *   [StructuralMetamodelDiffer] instance ordinarily implements both this and [differ].
 * @param driftReportStore Durable log the report is written to, on every run.
 * @param quarantinePolicy Decides which stranded propositions to quarantine. Consulted only on a
 *   live run that found drift from either source in "Two sources of drift" above.
 * @param propositionStore Where candidate propositions are read from and quarantined copies are
 *   saved back to. The base persistence port rather than `PropositionRepository`: a drift check only
 *   reads by context or in bulk and saves, so requiring vector search, graph traversal and temporal
 *   query alongside would shut a plain store-and-retrieve backend out of drift checking for
 *   capabilities it never uses.
 * @param listener Told about each quarantine as a [PropositionStatusChanged], so a consumer like
 *   `ProjectionLineageStaleCascade` hears about the transition without depending on whichever
 *   concrete [propositionStore] happens to be wired in. Defaults to a no-op: most of what
 *   [DefaultDriftCheckRunner] promises holds with nobody listening at all.
 */
class DefaultDriftCheckRunner @JvmOverloads constructor(
    private val declaredSchemaSource: DeclaredSchemaSource,
    private val versionStore: MetamodelVersionStore,
    private val observedSchemaSource: ObservedSchemaSource,
    private val differ: DeclaredObservedDiffer,
    private val metamodelDiffer: MetamodelDiffer,
    private val driftReportStore: DriftReportStore,
    private val quarantinePolicy: DriftQuarantinePolicy,
    private val propositionStore: PropositionStore,
    private val listener: DiceEventListener = DiceEventListener.DEV_NULL,
) : DriftCheckRunner {

    private val logger = LoggerFactory.getLogger(DefaultDriftCheckRunner::class.java)

    override fun run(dryRun: Boolean, contextId: ContextId?): DriftCheckResult {
        val declared = declaredSchemaSource.declare()

        // The reconciled baseline, read before this run's own history write below so it can never
        // read back its own stamp. Null when no live sweep has ever completed for this schema. See
        // "The diff baseline only advances when a sweep actually finishes" on the class doc.
        val previousVersion = versionStore.sweptVersion(declared.version.schemaName)

        // Every run stamps its declaration into history, dry or live, so a report's hash always
        // resolves — see the class doc. On a store that tracks the reconciled baseline
        // independently, this alone never moves it; only markSwept does, at the end of a live,
        // unscoped run. On a store that doesn't, this can move it too -- see the class doc.
        versionStore.saveVersion(declared.version)

        val observed = observedSchemaSource.observe(contextId)
        val diff = differ.diffAgainstObserved(declared = declared, observed = observed)

        // What moved in the declaration since the reconciled baseline — property removals, narrowed
        // cardinality, a whole type dropped — which diff above never sees, since it only compares
        // the current declaration against the graph as it stands right now.
        val declaredDiff = previousVersion?.let { metamodelDiffer.diff(it, declared.version) }

        val report = DriftReport(
            schemaName = declared.version.schemaName,
            versionHash = declared.version.contentHash,
            driftedEntityTypes = diff.driftedEntityTypes,
            driftedRelationshipTypes = diff.driftedRelationshipTypes,
            // The instant the graph was looked at, rather than the instant of this write: the
            // report is a statement about the snapshot. Declared-vs-previous drift isn't part of
            // this report; see the class doc.
            capturedAt = observed.capturedAt,
            contextId = contextId,
        )
        // Written on every run, including checks that found nothing.
        driftReportStore.saveDriftReport(report)

        val quarantinedCount = if (!dryRun && (diff.driftedEntityTypes.isNotEmpty() || declaredDiff?.isEmpty == false)) {
            quarantineAffectedPropositions(declared.version, diff.driftedEntityTypes, declaredDiff, contextId)
        } else {
            0
        }

        // This call advances the baseline only for a live, unscoped run: a dry run acted on
        // nothing, and a scoped run only ever sweeps one context's candidates against it.
        // Unconditional on whether anything was actually quarantined -- "nothing needed doing" is
        // still a completed reconciliation against this declaration, and the next check should
        // start from here. The saveVersion call above can also move the baseline, on any run, on a
        // store that doesn't track it independently -- see the class doc.
        if (!dryRun && contextId == null) {
            versionStore.markSwept(declared.version)
        }

        logger.info(
            "Drift check for '{}' complete (dryRun={}, contextId={}): {} drifted entity type(s), " +
                "{} drifted relationship type(s), {} quarantined",
            declared.version.schemaName,
            dryRun,
            contextId?.value,
            diff.driftedEntityTypes.size,
            diff.driftedRelationshipTypes.size,
            quarantinedCount,
        )

        return DriftCheckResult(dryRun = dryRun, report = report, quarantinedCount = quarantinedCount)
    }

    /**
     * Hand every quarantine-worthy change to [quarantinePolicy] and persist whatever it flags,
     * announcing each real transition to [listener] along the way.
     *
     * [driftedEntityTypes] (observed but undeclared) and [declaredDiff] (declared-vs-previous) are
     * merged into one [MetamodelDiff] before evaluation, one [MetamodelChange.EntityTypeRemoved] per
     * drifted type standing in for the ones [declaredDiff] didn't already report as removed. The
     * policy only ever sees one diff and decides once; this never runs two independent sweeps that
     * could each quarantine, or skip, the same proposition for a different reason.
     *
     * The merge keeps [MetamodelDiff]'s promised global ordering: [MetamodelChange.EntityTypeRemoved]
     * is always the differ's first block, sorted by type name, so every removed name — declared or
     * drifted — is gathered into that one sorted block. Any [MetamodelChange.EntityTypeRemoved]
     * already present in [declaredDiff]'s own changes is explicitly filtered back out before the
     * remainder is appended. This filter is what keeps a removal from showing up twice; the merged
     * block is built from the two removal sources directly, and [declaredDiff]'s other changes keep
     * their original relative order behind it.
     *
     * [declaredDiff]'s own [MetamodelDiff.fromVersion] carries forward as the merged diff's `from`
     * side when it exists, so the policy can still resolve declared former names for a type removal
     * that came from the declaration comparison. A drifted-but-undeclared type was never declared by
     * either version, so it has no former names to resolve either way.
     */
    private fun quarantineAffectedPropositions(
        declaredVersion: MetamodelVersion,
        driftedEntityTypes: Set<String>,
        declaredDiff: MetamodelDiff?,
        contextId: ContextId?,
    ): Int {
        val declaredChanges = declaredDiff?.changes.orEmpty()
        val mergedRemovedTypeNames = (declaredDiff?.removedEntityTypes.orEmpty() union driftedEntityTypes).sorted()
        val mergedRemovals = mergedRemovedTypeNames.map { MetamodelChange.EntityTypeRemoved(it) }
        val mergedDiff = MetamodelDiff(
            fromVersion = declaredDiff?.fromVersion ?: declaredVersion,
            toVersion = declaredVersion,
            changes = mergedRemovals + declaredChanges.filterNot { it is MetamodelChange.EntityTypeRemoved },
        )

        // A proposition in another context is never a candidate, whatever its mentions say, so a
        // scoped run cannot reach outside its context.
        val propositions = if (contextId != null) {
            propositionStore.findByContextId(contextId)
        } else {
            propositionStore.findAll()
        }
        // Captured before evaluation, since QuarantineDecision.Quarantined only carries the copy
        // already flipped to STALE — the emitted event needs to say what it moved from.
        val statusById = propositions.associate { it.id to it.status }

        val result = quarantinePolicy.evaluate(mergedDiff, propositions)
        result.quarantined.forEach { decision ->
            val saved = propositionStore.save(decision.proposition)
            // A proposition can arrive already STALE from ordinary decay (no quarantine reason yet,
            // so the policy still treats it as a fresh candidate) and get quarantined without its
            // status actually moving. Announcing a transition then would be a lie the listener has
            // no way to catch, so this only fires when something really changed.
            val previousStatus = statusById.getValue(decision.proposition.id)
            if (previousStatus != decision.proposition.status) {
                listener.onEvent(
                    PropositionStatusChanged(
                        proposition = saved,
                        previousStatus = previousStatus,
                        newStatus = decision.proposition.status,
                        reason = decision.reason,
                    ),
                )
            }
        }
        return result.quarantined.size
    }
}
