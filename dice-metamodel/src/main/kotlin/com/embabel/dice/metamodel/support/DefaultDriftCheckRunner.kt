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
import com.embabel.dice.metamodel.DriftQuarantinePolicy
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.proposition.PropositionStore
import org.slf4j.LoggerFactory

/**
 * The shipped [DriftCheckRunner]. It sequences the collaborators and decides nothing itself: the
 * comparison belongs to the differ, the quarantine call to the policy, and this class only makes
 * sure they happen in an order that leaves a coherent record behind.
 *
 * Stateless, so calling it repeatedly or for different schemas at once is fine. Two concurrent
 * checks of the *same* schema aren't corrupting — each captures its own complete snapshot — but
 * they are wasteful; serialize at the scheduling layer if that matters.
 *
 * ## Stamp before you report
 *
 * The declared version is saved to [versionStore] on **every** run, before the report is written.
 * That looks redundant, because a version that hasn't changed re-saves onto its own key and stores
 * nothing new. The point is the guarantee it buys: a [DriftReport] records the
 * [MetamodelVersion.contentHash] it was judged against, and that hash is only useful if it resolves
 * back to a real stamp through [MetamodelVersionStore.findVersion]. Stamping last, or only when the
 * schema moved, leaves the first check after a schema change pointing at a hash nothing has ever
 * recorded — the reports that matter most are exactly the ones that would dangle. Since
 * `saveVersion` upserts on `(schemaName, contentHash)`, paying for it every run costs one idempotent
 * write and removes the failure mode entirely.
 *
 * @param declaredSchemaSource Supplies the schema as declared. Read first, so everything downstream
 *   is judged against one declaration.
 * @param versionStore Where the declared stamp is recorded each run, so report hashes always
 *   resolve.
 * @param observedSchemaSource Snapshots what the live graph actually contains.
 * @param differ Compares the declaration against the observation.
 * @param driftReportStore Durable log the report is written to — every run, drift or not.
 * @param quarantinePolicy Decides which stranded propositions to quarantine. Consulted only on a
 *   live run that found entity-type drift; this runner never reimplements the decision.
 * @param propositionStore Where candidate propositions are read from and quarantined copies are
 *   saved back to. The base persistence port, not `PropositionRepository`: a drift check only ever
 *   reads by context or in bulk and saves, so asking for vector search, graph traversal and
 *   temporal query alongside would shut a plain store-and-retrieve backend out of drift checking
 *   for capabilities it is never asked to use.
 */
class DefaultDriftCheckRunner(
    private val declaredSchemaSource: DeclaredSchemaSource,
    private val versionStore: MetamodelVersionStore,
    private val observedSchemaSource: ObservedSchemaSource,
    private val differ: DeclaredObservedDiffer,
    private val driftReportStore: DriftReportStore,
    private val quarantinePolicy: DriftQuarantinePolicy,
    private val propositionStore: PropositionStore,
) : DriftCheckRunner {

    private val logger = LoggerFactory.getLogger(DefaultDriftCheckRunner::class.java)

    override fun run(dryRun: Boolean, contextId: ContextId?): DriftCheckResult {
        val declared = declaredSchemaSource.declare()

        // Stamp first — see the class doc. This has to happen before the report is written, so the
        // hash the report carries is already resolvable by the time anyone can read it.
        versionStore.saveVersion(declared.version)

        val observed = observedSchemaSource.observe(contextId)
        val diff = differ.diffAgainstObserved(declared = declared, observed = observed)

        val report = DriftReport(
            schemaName = declared.version.schemaName,
            versionHash = declared.version.contentHash,
            driftedEntityTypes = diff.driftedEntityTypes,
            driftedRelationshipTypes = diff.driftedRelationshipTypes,
            // The instant the graph was looked at, not the instant this write happens: the report
            // is a statement about the snapshot.
            capturedAt = observed.capturedAt,
            contextId = contextId,
        )
        // Written unconditionally. A zero-drift check is a fact worth having on record, not a no-op.
        driftReportStore.saveDriftReport(report)

        val quarantinedCount = if (!dryRun && diff.driftedEntityTypes.isNotEmpty()) {
            quarantineDriftedEntityTypes(declared.version, diff.driftedEntityTypes, contextId)
        } else {
            0
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
     * Hand the drifted types to [quarantinePolicy] and persist whatever it flags.
     *
     * The policy takes a [MetamodelDiff] — two *declared* versions compared — but what a drift check
     * has is a declaration compared against a live observation, which is a different question. They
     * agree on the part the policy cares about, though: a mention whose type the declared schema
     * doesn't recognise is stranded either way, whether the type was dropped from a newer
     * declaration or was never declared at all. So we synthesize the equivalent diff — nothing but a
     * [MetamodelChange.EntityTypeRemoved] per drifted type — and let the real policy decide, rather
     * than re-deciding quarantine here with a second, subtly different rule.
     *
     * Both ends of the synthesized diff point at the same declared version. There was no old-to-new
     * transition; the two sides are there only so the policy's reason string has something to name.
     */
    private fun quarantineDriftedEntityTypes(
        declaredVersion: MetamodelVersion,
        driftedEntityTypes: Set<String>,
        contextId: ContextId?,
    ): Int {
        val syntheticDiff = MetamodelDiff(
            fromVersion = declaredVersion,
            toVersion = declaredVersion,
            changes = driftedEntityTypes.sorted().map { MetamodelChange.EntityTypeRemoved(it) },
        )
        // Scoping is the whole blast radius: a proposition in another context is never a candidate,
        // so nothing this run does can reach it, whatever its mentions say.
        val propositions = if (contextId != null) {
            propositionStore.findByContextId(contextId)
        } else {
            propositionStore.findAll()
        }
        val result = quarantinePolicy.evaluate(syntheticDiff, propositions)
        result.quarantined.forEach { decision -> propositionStore.save(decision.proposition) }
        return result.quarantined.size
    }
}
