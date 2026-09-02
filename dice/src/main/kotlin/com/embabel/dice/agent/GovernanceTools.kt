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
package com.embabel.dice.agent

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.Tool
import com.embabel.dice.governance.GovernanceOperationsService
import com.embabel.dice.governance.GovernanceRequestException
import com.embabel.dice.governance.parseSince
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory

/**
 * LLM-invocable tools over the schema governance operator surface: read the drift log and the
 * current declaration, run a drift check, release a quarantined proposition.
 *
 * Framework-light, the same way `DiscoveryTools` is: only `@LlmTool` annotations already on the
 * classpath, no MCP SDK and no servlet dependency. A consuming application calls [asTools] and
 * registers the returned `List<Tool>` with its own MCP server or agent tool set.
 *
 * Every tool runs through one [GovernanceOperationsService], the same object `GovernanceController`
 * calls over HTTP, so an agent and an operator reading the same state get one answer. Results are
 * leak-free JSON via [Tool.Result.text]; a refused request — a limit outside its bounds, a blank
 * identifier, a timestamp that will not parse — comes back as [Tool.Result.error] carrying the bound
 * that was broken.
 *
 * ## This is an administrative surface
 *
 * Unlike `DiscoveryTools`, no context is baked in at construction, because two of these operations
 * are about the whole graph: the drift log's global half and a whole-graph check both cover every
 * context at once. So the per-context operations take their context as an argument, and an agent
 * holding these tools can read and release in any context. A host decides whether an agent gets
 * them at all by choosing to register them.
 *
 * Running a check writes a drift report and moves no proposition. Releasing a proposition is the one
 * tool here that changes stored data, and it changes exactly one proposition.
 *
 * @param operations The single governance service every tool delegates to.
 */
class GovernanceTools(
    private val operations: GovernanceOperationsService,
) {

    private val logger = LoggerFactory.getLogger(GovernanceTools::class.java)

    /** The declaration in force, and where it sits against what governance has recorded. */
    @LlmTool(
        name = "declared_schema_version",
        description = "Report the schema the application currently declares: its name, content hash, " +
            "governed entity types and relationships, whether that exact version has been recorded, " +
            "and which version the last completed drift sweep reconciled against.",
    )
    fun declaredSchemaVersion(): Tool.Result {
        logger.info("Reading the current declared schema version")
        return call { json(operations.currentDeclaredVersion()) }
    }

    /** Whole-graph drift checks, newest first. */
    @LlmTool(
        name = "latest_drift_reports",
        description = "List the most recent whole-graph drift checks for the declared schema, newest " +
            "first. Each report says which entity and relationship types the graph held that the schema " +
            "never declared, and how the declaration itself moved since the last completed sweep. " +
            "Checks scoped to a single context are excluded; use drift_reports_in_context for those.",
    )
    fun latestDriftReports(
        @LlmTool.Param(description = "How many reports to return, at most 200", required = false)
        limit: Int = GovernanceOperationsService.DEFAULT_REPORT_LIMIT,
        @LlmTool.Param(description = "Only reports captured at or after this ISO-8601 instant", required = false)
        since: String? = null,
    ): Tool.Result {
        logger.info("Reading up to {} global drift report(s)", limit)
        return call { json(operations.latestReports(limit, parseSince(since))) }
    }

    /** Drift checks scoped to one context, newest first. */
    @LlmTool(
        name = "drift_reports_in_context",
        description = "List the most recent drift checks scoped to one context, newest first. " +
            "Whole-graph checks and other contexts' checks are excluded. Provide the context id.",
    )
    fun driftReportsInContext(
        @LlmTool.Param(description = "The context whose drift checks to read")
        contextId: String,
        @LlmTool.Param(description = "How many reports to return, at most 200", required = false)
        limit: Int = GovernanceOperationsService.DEFAULT_REPORT_LIMIT,
        @LlmTool.Param(description = "Only reports captured at or after this ISO-8601 instant", required = false)
        since: String? = null,
    ): Tool.Result {
        logger.info("Reading up to {} drift report(s) in context {}", limit, contextId)
        return call { json(operations.reportsInContext(contextId, limit, parseSince(since))) }
    }

    /** Run a check. It records a report and moves nothing. */
    @LlmTool(
        name = "run_drift_check",
        description = "Run a schema drift check and return everything it found: the entity and " +
            "relationship types the graph holds that the schema never declared, how the declaration " +
            "moved since the last completed sweep, and the merged comparison a quarantine sweep would " +
            "evaluate. The check records a report and changes no stored fact. Omit the context id to " +
            "check the whole graph.",
    )
    fun runDriftCheck(
        @LlmTool.Param(description = "Check only this context; omit to check the whole graph", required = false)
        contextId: String? = null,
    ): Tool.Result {
        logger.info("Running a drift check scoped to {}", contextId ?: "the whole graph")
        return call { json(operations.runCheck(contextId)) }
    }

    /** Lift the hold on one quarantined proposition. */
    @LlmTool(
        name = "release_quarantined_proposition",
        description = "Let one quarantined fact (proposition) back into use: restore the status it " +
            "carried before schema governance held it and clear the quarantine note. Returns where the " +
            "fact stands afterwards, or an error when that context holds no such fact or the fact is " +
            "not quarantined. Provide the context id and the fact id.",
    )
    fun releaseQuarantinedProposition(
        @LlmTool.Param(description = "The context the fact belongs to")
        contextId: String,
        @LlmTool.Param(description = "The id of the quarantined fact to release")
        propositionId: String,
    ): Tool.Result {
        logger.info("Releasing proposition {} in context {}", propositionId, contextId)
        return call {
            val released = operations.releaseProposition(contextId, propositionId)
                ?: return@call null
            json(released)
        }
    }

    /**
     * Run [body] and turn its answer into a tool result: its JSON when there was one, a "nothing to
     * act on" error when it answered `null`, and the refusal's own message when the service turned
     * the request down.
     */
    private fun call(body: () -> String?): Tool.Result =
        try {
            body()?.let { Tool.Result.text(it) }
                ?: Tool.Result.error("Nothing matched that request.")
        } catch (e: GovernanceRequestException) {
            Tool.Result.error(e.message ?: "invalid governance request")
        }

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)

    companion object {

        private val objectMapper = jacksonObjectMapper()

        /**
         * Create [Tool] instances exposing the governance operator surface.
         *
         * ```kotlin
         * val tools = GovernanceTools.asTools(governanceOperationsService)
         * ```
         *
         * @param operations The governance service the tools delegate to.
         * @return The tools, ready to register alongside an agent's other ones.
         */
        @JvmStatic
        fun asTools(operations: GovernanceOperationsService): List<Tool> =
            Tool.fromInstance(GovernanceTools(operations))
    }
}
