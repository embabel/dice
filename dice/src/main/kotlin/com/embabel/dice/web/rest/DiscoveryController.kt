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

import com.embabel.agent.core.ContextId
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.projection.memory.CollectorRunner
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.query.discovery.CollectorDryRunDto
import com.embabel.dice.query.discovery.DiscoveryQuery
import com.embabel.dice.query.discovery.DiscoveryResult
import com.embabel.dice.query.discovery.LineageDto
import com.embabel.dice.query.discovery.PathDto
import com.embabel.dice.query.discovery.ProjectionHealthDto
import com.embabel.dice.query.discovery.RetrievalRouter
import com.embabel.dice.query.graph.GraphQuery
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Opt-in REST surface exposing the discovery operations over OpenAPI-discoverable endpoints, all
 * returning only the leak-free discovery DTOs.
 *
 * This controller is NOT component-scanned: it activates only when imported via
 * [DiceRestConfiguration] AND a [PropositionStore] bean is present (the same opt-in pattern the
 * other DICE controllers use). It rides the existing optional Spring MVC dependency and adds no new
 * dependency of its own — a consumer's own springdoc generates the OpenAPI spec from the plain
 * `@RestController` and the leak-free DTOs.
 *
 * Every operation is scoped by the `{contextId}` path variable: a per-request [RetrievalRouter] is
 * built with that context so a caller can never read across contexts and can never override the
 * context from a request body. Result size and traversal depth are clamped by the router.
 *
 * @param store the backing proposition store; its declared fragments determine native mode support
 * @param graphQuery the portable graph facade for path / why-explain / graph-walk
 * @param projectionRecordStore the inverse projection index summarized into per-target health
 * @param collectorRunner the mark-and-sweep runner invoked in non-mutating dry-run mode
 */
@RestController
@RequestMapping("/api/v1/contexts/{contextId}/discovery")
@ConditionalOnBean(PropositionStore::class)
class DiscoveryController(
    private val store: PropositionStore,
    private val graphQuery: GraphQuery,
    private val projectionRecordStore: ProjectionRecordStore,
    private val collectorRunner: CollectorRunner,
) {

    private val logger = LoggerFactory.getLogger(DiscoveryController::class.java)

    /**
     * Retrieve propositions via a chosen retrieval mode. The context comes from the path only; the
     * request body's mode/text/entity/window/bounds are honoured, never a context override.
     */
    @PostMapping("/query")
    fun query(
        @PathVariable contextId: String,
        @RequestBody request: DiscoveryQuery,
    ): ResponseEntity<DiscoveryResult> {
        logger.debug("Discovery query for context {} mode {}", contextId, request.mode)
        return ResponseEntity.ok(router(contextId).retrieve(request))
    }

    /** Find how two entities are connected, as leak-free path summaries. */
    @GetMapping("/path")
    fun path(
        @PathVariable contextId: String,
        @RequestParam from: String,
        @RequestParam to: String,
    ): ResponseEntity<List<PathDto>> {
        logger.debug("Discovery path for context {} {} -> {}", contextId, from, to)
        return ResponseEntity.ok(router(contextId).graphPath(from, to))
    }

    /** Explain why a stored fact holds; 404 when the proposition id is unknown. */
    @GetMapping("/why/{propositionId}")
    fun why(
        @PathVariable contextId: String,
        @PathVariable propositionId: String,
    ): ResponseEntity<LineageDto> {
        logger.debug("Discovery why-explain for context {} proposition {}", contextId, propositionId)
        val lineage = router(contextId).whyExplain(propositionId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(lineage)
    }

    /** Per-target projection lifecycle counts. Pure read; mutates nothing. */
    @GetMapping("/projection-health")
    fun projectionHealth(
        @PathVariable contextId: String,
    ): ResponseEntity<ProjectionHealthDto> {
        logger.debug("Discovery projection health for context {}", contextId)
        // Scope to this context's lineage only — never aggregate across contexts.
        return ResponseEntity.ok(ProjectionHealthDto.from(projectionRecordStore.findByContext(contextId)))
    }

    /** Preview what the maintenance collector would mark and sweep, without mutating anything. */
    @PostMapping("/collector/dry-run")
    fun collectorDryRun(
        @PathVariable contextId: String,
    ): ResponseEntity<CollectorDryRunDto> {
        logger.debug("Discovery collector dry-run for context {}", contextId)
        val result = collectorRunner.run(ContextId(contextId), dryRun = true)
        return ResponseEntity.ok(CollectorDryRunDto.from(result))
    }

    /**
     * Sanitize any failure from a live store/driver (timeouts, query errors) into a generic 500.
     * The cause is logged server-side; the response body carries only a fixed message so internal
     * or driver detail never leaks to the caller, regardless of the consumer's global error config.
     */
    @ExceptionHandler(Exception::class)
    fun handleFailure(e: Exception): ResponseEntity<Map<String, String>> {
        logger.error("Discovery operation failed", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "discovery operation failed"))
    }

    /** Build a router scoped to the path-supplied context only. */
    private fun router(contextId: String): RetrievalRouter =
        RetrievalRouter(store, graphQuery, ContextId(contextId))
}
