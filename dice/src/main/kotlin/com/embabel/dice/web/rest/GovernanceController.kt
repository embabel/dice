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
package com.embabel.dice.web.rest

import com.embabel.dice.governance.DeclaredVersionDto
import com.embabel.dice.governance.DriftCheckDto
import com.embabel.dice.governance.DriftReportDto
import com.embabel.dice.governance.GovernanceOperationsService
import com.embabel.dice.governance.GovernanceRequestException
import com.embabel.dice.governance.ReleasedPropositionDto
import com.embabel.dice.governance.parseSince
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The HTTP half of the schema governance operator surface: read the drift log and the current
 * declaration, run a check, release a quarantined proposition.
 *
 * It carries no logic of its own. Every route calls [GovernanceOperationsService], the same object
 * `GovernanceTools` calls, so an operator working over HTTP and an agent working through tools see
 * one answer. Only the leak-free governance DTOs cross this boundary.
 *
 * Paths sit under `/api/v1/metamodel`, matching the `embabel.dice.metamodel` prefix the module's
 * settings use, and a per-context operation names its context in the path the way
 * [DiscoveryController] does. `GET` reads, `POST` runs a check or performs a release.
 *
 * ## How it switches on
 *
 * This controller is not component-scanned, and it has no auto-configuration of its own. It goes on
 * the context through [DiceRestConfiguration], the one import a host uses to open any DICE REST
 * surface, and only when a [GovernanceOperationsService] bean is there to answer the routes. Two
 * decisions, both the host's: import DICE REST, and wire the governance loop.
 *
 * So a host that imports [DiceRestConfiguration] and declared no schema resolves zero
 * `/api/v1/metamodel` URLs and starts cleanly, and a host that wants the governance loop through
 * agent tools or its own code with no endpoint open leaves the import out. See
 * [GovernanceControllerImport] for why the condition is asked late enough to see a service the
 * auto-configuration built.
 *
 * `@ConditionalOnMissingBean` lets a host put these operations somewhere else — a different path,
 * extra authorization, a shape of its own — by declaring its own `GovernanceController` bean and
 * still importing [DiceRestConfiguration] for the other controllers. This one then backs off.
 *
 * Every read is bounded. `limit` is clamped by the service, and a value outside its range answers
 * `400` with the bound named in the body, so an operator who asked for too much can see what to ask
 * for. Any other failure — a driver timeout, a store error — answers a generic `500` whose body
 * carries a fixed message, with the cause logged server-side.
 *
 * @param operations The single governance service every route delegates to.
 */
@ApiStatus.Experimental
@RestController
@RequestMapping("/api/v1/metamodel")
@ConditionalOnBean(GovernanceOperationsService::class)
@ConditionalOnMissingBean(GovernanceController::class)
class GovernanceController(
    private val operations: GovernanceOperationsService,
) {

    private val logger = LoggerFactory.getLogger(GovernanceController::class.java)

    /** What the application declares right now, and where it sits against what governance recorded. */
    @GetMapping("/declared-version")
    fun declaredVersion(): ResponseEntity<DeclaredVersionDto> {
        logger.debug("Reading the current declared version")
        return ResponseEntity.ok(operations.currentDeclaredVersion())
    }

    /** The most recent whole-graph drift checks, newest first. Context-scoped checks are excluded. */
    @GetMapping("/drift-reports")
    fun latestReports(
        @RequestParam(required = false, defaultValue = DEFAULT_LIMIT) limit: Int,
        @RequestParam(required = false) since: String?,
    ): ResponseEntity<List<DriftReportDto>> {
        logger.debug("Reading up to {} global drift report(s)", limit)
        return ResponseEntity.ok(operations.latestReports(limit, parseSince(since)))
    }

    /** The most recent drift checks scoped to one context, newest first. */
    @GetMapping("/contexts/{contextId}/drift-reports")
    fun reportsInContext(
        @PathVariable contextId: String,
        @RequestParam(required = false, defaultValue = DEFAULT_LIMIT) limit: Int,
        @RequestParam(required = false) since: String?,
    ): ResponseEntity<List<DriftReportDto>> {
        logger.debug("Reading up to {} drift report(s) in context {}", limit, contextId)
        return ResponseEntity.ok(operations.reportsInContext(contextId, limit, parseSince(since)))
    }

    /**
     * Run a whole-graph drift check. It writes a report and moves no proposition, so the response is
     * the full impact a sweep would evaluate.
     */
    @PostMapping("/drift-checks")
    fun runCheck(): ResponseEntity<DriftCheckDto> {
        logger.debug("Running a whole-graph drift check")
        return ResponseEntity.ok(operations.runCheck())
    }

    /** The same check scoped to one context. */
    @PostMapping("/contexts/{contextId}/drift-checks")
    fun runCheckInContext(
        @PathVariable contextId: String,
    ): ResponseEntity<DriftCheckDto> {
        logger.debug("Running a drift check in context {}", contextId)
        return ResponseEntity.ok(operations.runCheck(contextId))
    }

    /**
     * Let one quarantined proposition back into use, answering where it stands afterwards.
     *
     * `404` when the context holds no proposition with that id, and when the one it holds is not
     * quarantined — from outside, both mean there is no hold here to lift.
     */
    @PostMapping("/contexts/{contextId}/quarantine/{propositionId}/release")
    fun releaseProposition(
        @PathVariable contextId: String,
        @PathVariable propositionId: String,
    ): ResponseEntity<ReleasedPropositionDto> {
        logger.debug("Releasing proposition {} in context {}", propositionId, contextId)
        val released = operations.releaseProposition(contextId, propositionId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(released)
    }

    /**
     * Answer a refused request with `400` carrying the check's own message, so the bound that was
     * broken reaches the caller.
     *
     * Scoped to [GovernanceRequestException] on purpose. Handling `IllegalArgumentException` here
     * would turn a store's own argument failure into a `400` and tell an operator to fix their
     * request when the fault was server-side.
     */
    @ExceptionHandler(GovernanceRequestException::class)
    fun handleRefusedRequest(e: GovernanceRequestException): ResponseEntity<Map<String, String>> {
        logger.debug("Refused a governance request: {}", e.message)
        return ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "invalid governance request")))
    }

    /**
     * Sanitize any other failure into a generic `500`. The cause is logged server-side; the response
     * body carries a fixed message, so driver or store detail never reaches a caller whatever the
     * consumer's global error config does.
     */
    @ExceptionHandler(Exception::class)
    fun handleFailure(e: Exception): ResponseEntity<Map<String, String>> {
        logger.error("Governance operation failed", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "governance operation failed"))
    }

    private companion object {

        /**
         * The service's own default, as the text `@RequestParam` needs. A Kotlin template over a
         * `const` is itself a compile-time constant, so the two can't fall out of step.
         */
        const val DEFAULT_LIMIT = "${GovernanceOperationsService.DEFAULT_REPORT_LIMIT}"
    }
}
