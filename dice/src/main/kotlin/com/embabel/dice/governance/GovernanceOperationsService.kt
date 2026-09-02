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

import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.SweptBaselineStore
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.spi.DriftSweepCapable
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The one place an operator reaches the schema governance loop: read what the last checks found,
 * run a check, and let a quarantined proposition back into use.
 *
 * Everything the loop produces was previously reachable only from a debugger. The stores keep drift
 * reports and version stamps, the runner performs a check, and the sweep holds and releases
 * propositions — and none of them had a caller a person could invoke. This class is that caller, and
 * both shipped front ends (`GovernanceController` over HTTP, `GovernanceTools` for an agent) run
 * through it, so the two can never answer differently.
 *
 * ## Reading is bounded, and scope is named at the call site
 *
 * A drift log grows once per check forever, so every read takes a `limit` and refuses one outside
 * [MIN_REPORT_LIMIT]`..`[MAX_REPORT_LIMIT] with a [GovernanceRequestException] naming the bound.
 * [latestReports] answers for whole-graph checks and [reportsInContext] for one context's, which is
 * the same split [DriftReportStore] makes; a caller asking for one never silently gets the other.
 *
 * ## Running a check changes nothing
 *
 * [runCheck] calls [DriftCheckRunner], which reads, compares and writes a report, and moves no
 * proposition on any path. So every run is an evaluation, and [DriftCheckDto] carries the whole
 * comparison a sweep would act on: both drift sets, the declared diff, and the two merged into
 * [DriftCheckDto.sweepImpact]. Performing the sweep stays a separate, deliberate call a host makes
 * on [DriftSweepCapable].
 *
 * ## Releasing is scoped before it writes
 *
 * [releaseProposition] takes a context and checks the proposition belongs to it before the release
 * happens, so a caller holding one context's id can never lift a hold in another. The release itself
 * goes through [DriftSweepCapable.releaseFromQuarantine], which restores the status the proposition
 * carried before quarantine and clears its quarantine metadata in one write.
 *
 * There is no operation here that releases a whole drift report's worth of propositions. Nothing in
 * the model ties a quarantined proposition back to the report whose application quarantined it: a
 * [com.embabel.dice.metamodel.DriftReport] has no identity of its own beyond its natural key, and
 * the reason the sweep writes onto a proposition names the two schemas and nothing about the check.
 * Guessing the set from the reason text would release propositions a different check had held.
 *
 * @param declaredSchemaSource What the application declares. Its schema name scopes every read, so
 *   an operator always reads the log belonging to the declaration in force.
 * @param versionStore Where stamps live. Consulted to say whether the current declaration has ever
 *   been recorded, and for the reconciled baseline when the store tracks one.
 * @param driftReportStore The durable drift log.
 * @param driftCheckRunner Performs a check.
 * @param driftSweep The store-side quarantine operations. Only [DriftSweepCapable.releaseFromQuarantine]
 *   is used here; nothing in this class sweeps.
 * @param propositions Read to confirm a proposition's context before a release touches it.
 */
class GovernanceOperationsService(
    private val declaredSchemaSource: DeclaredSchemaSource,
    private val versionStore: MetamodelVersionStore,
    private val driftReportStore: DriftReportStore,
    private val driftCheckRunner: DriftCheckRunner,
    private val driftSweep: DriftSweepCapable,
    private val propositions: PropositionStore,
) {

    private val logger = LoggerFactory.getLogger(GovernanceOperationsService::class.java)

    /**
     * The most recent whole-graph drift checks for the declared schema, newest first.
     *
     * Checks scoped to a context are left out; ask [reportsInContext] for those.
     *
     * @param limit The most reports to return.
     * @param since When non-null, only reports captured at or after this instant.
     * @return At most [limit] reports, newest first.
     * @throws GovernanceRequestException if [limit] is outside its bounds.
     */
    @JvmOverloads
    fun latestReports(limit: Int, since: Instant? = null): List<DriftReportDto> {
        requireLimit(limit)
        val schemaName = declaredSchemaName()
        logger.debug("Reading up to {} global drift report(s) for schema {}", limit, schemaName)
        return driftReportStore.globalDriftReports(schemaName, limit, since).map { DriftReportDto.from(it) }
    }

    /**
     * The most recent drift checks scoped to [contextId], newest first. Whole-graph checks and other
     * contexts' are left out.
     *
     * @param contextId The context to read.
     * @param limit The most reports to return.
     * @param since When non-null, only reports captured at or after this instant.
     * @return At most [limit] reports, newest first.
     * @throws GovernanceRequestException if [limit] is outside its bounds or [contextId] is blank.
     */
    @JvmOverloads
    fun reportsInContext(contextId: String, limit: Int, since: Instant? = null): List<DriftReportDto> {
        val scope = requireContext(contextId)
        requireLimit(limit)
        val schemaName = declaredSchemaName()
        logger.debug("Reading up to {} drift report(s) for schema {} in {}", limit, schemaName, scope.value)
        return driftReportStore.driftReportsInContext(schemaName, scope, limit, since)
            .map { DriftReportDto.from(it) }
    }

    /**
     * What the application declares right now, with whether governance has recorded it and which
     * declaration the last completed sweep reconciled against.
     *
     * The baseline is answered only when the version store tracks one. A store that keeps stamps and
     * nothing else reports `null` there, which is the honest answer: guessing a baseline from write
     * order is how an interrupted sweep gets mistaken for a finished one.
     *
     * @return The current declaration.
     */
    fun currentDeclaredVersion(): DeclaredVersionDto {
        val version = declaredSchemaSource.declare().version
        val stamped = versionStore.findVersion(version.schemaName, version.contentHash) != null
        val swept = (versionStore as? SweptBaselineStore)?.sweptVersion(version.schemaName)
        return DeclaredVersionDto.from(version, stamped, swept)
    }

    /**
     * Run a drift check and hand back everything it found.
     *
     * The check stamps the declaration, snapshots what the graph holds, compares both ways and
     * persists a report. It moves no proposition, so the answer is a preview: the returned
     * [DriftCheckDto] carries the drift sets, the declared diff, and the merged comparison a sweep
     * would evaluate.
     *
     * @param contextId The context to check, or `null` for the whole graph.
     * @return What the check found.
     * @throws GovernanceRequestException if [contextId] is supplied and blank.
     */
    @JvmOverloads
    fun runCheck(contextId: String? = null): DriftCheckDto {
        val scope = contextId?.let { requireContext(it) }
        logger.info("Running a drift check scoped to {}", scope?.value ?: "the whole graph")
        return DriftCheckDto.from(driftCheckRunner.run(scope))
    }

    /**
     * Let one quarantined proposition back into use.
     *
     * [contextId] is an access-control bound and is checked before anything is written: a
     * proposition belonging to another context answers `null` and is left exactly as it was.
     *
     * Releasing twice is safe. The second call finds a proposition that is no longer quarantined and
     * answers `null` without writing.
     *
     * @param contextId The context the proposition must belong to.
     * @param propositionId The proposition to release.
     * @return Where the proposition stands after the release, or `null` when no proposition in that
     *   context has that id, or when the one that does is not quarantined.
     * @throws GovernanceRequestException if either identifier is blank.
     */
    fun releaseProposition(contextId: String, propositionId: String): ReleasedPropositionDto? {
        val scope = requireContext(contextId)
        if (propositionId.isBlank()) {
            throw GovernanceRequestException("propositionId must be supplied")
        }

        val held = propositions.findById(propositionId)
        if (held == null || held.contextIdValue != scope.value) {
            logger.debug("No proposition (id={}) to release in {}", propositionId, scope.value)
            return null
        }

        val released = driftSweep.releaseFromQuarantine(propositionId)
        if (released == null) {
            logger.debug("Proposition (id={}) is not quarantined; nothing released", propositionId)
            return null
        }
        logger.info("Released proposition (id={}) back to {}", released.id, released.status)
        return ReleasedPropositionDto.from(released)
    }

    /** The schema every read is scoped to: whatever the application declares right now. */
    private fun declaredSchemaName(): String = declaredSchemaSource.declare().version.schemaName

    private fun requireLimit(limit: Int) {
        if (limit < MIN_REPORT_LIMIT || limit > MAX_REPORT_LIMIT) {
            throw GovernanceRequestException(
                "limit must be between $MIN_REPORT_LIMIT and $MAX_REPORT_LIMIT, but was $limit",
            )
        }
    }

    private fun requireContext(contextId: String): ContextId {
        if (contextId.isBlank()) {
            throw GovernanceRequestException("contextId must be supplied")
        }
        return ContextId(contextId)
    }

    companion object {

        /** The smallest useful read. Asking for none of a bounded read is a mistake worth naming. */
        const val MIN_REPORT_LIMIT: Int = 1

        /**
         * The most reports one read returns. A drift log grows once per check per schema forever, so
         * an operator asking for "everything" would work on a laptop and time out in production
         * after a month of hourly checks. Page by moving `since` backwards.
         */
        const val MAX_REPORT_LIMIT: Int = 200

        /** What a read returns when a caller names no limit. */
        const val DEFAULT_REPORT_LIMIT: Int = 20
    }
}
