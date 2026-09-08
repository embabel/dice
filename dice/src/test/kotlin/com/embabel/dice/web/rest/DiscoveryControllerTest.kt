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
import com.embabel.dice.agent.DiscoveryTools
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.projection.memory.CollectorRunResult
import com.embabel.dice.projection.memory.CollectorRunner
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaMethod

/**
 * Opt-in and leak-free contract for the discovery REST surface, plus the cross-tier signature gate
 * that completes the no-leak guarantee: neither the controller's public method signatures nor the
 * MCP tool method signatures may surface a store / RAG / graph / domain type.
 */
class DiscoveryControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var repository: TestPropositionRepository
    private val contextId = "ctx-discovery"

    private val emptyRecordStore = object : ProjectionRecordStore {
        override fun record(record: ProjectionRecord) = Unit
        override fun all(): List<ProjectionRecord> = emptyList()
    }

    private val noopCollectorRunner = object : CollectorRunner {
        override fun collect(contextId: ContextId): CollectorRunResult = empty(contextId)
        override fun run(contextId: ContextId, dryRun: Boolean): CollectorRunResult = empty(contextId)
        private fun empty(contextId: ContextId) = CollectorRunResult(
            runId = "dry-${contextId.value}",
            dryRun = true,
            marks = emptyList(),
            applied = emptyList(),
            skipped = emptyList(),
            hardDeleted = emptyList(),
            startedAt = Instant.now(),
        )
    }

    @BeforeEach
    fun setUp() {
        repository = TestPropositionRepository()
        val objectMapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .findAndAddModules()
            .build()
        val controller = DiscoveryController(
            store = repository,
            projectionRecordStore = emptyRecordStore,
            collectorRunner = noopCollectorRunner,
        )
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(JacksonJsonHttpMessageConverter(objectMapper))
            .build()
    }

    @Test
    fun `DiceRestConfiguration can be imported without discovery support beans`() {
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean(
                "propositionRepository",
                PropositionRepository::class.java,
                java.util.function.Supplier { InMemoryPropositionRepository() },
            )
            context.register(DiceRestConfiguration::class.java)

            assertDoesNotThrow { context.refresh() }
            assertFalse(
                context.getBeansOfType(DiscoveryController::class.java).isNotEmpty(),
                "DiscoveryController must stay inactive until all discovery support beans are present",
            )
        }
    }

    @Test
    fun `POST query routes by mode and returns a leak-free result`() {
        repository.save(
            Proposition(
                id = "p1",
                contextId = ContextId(contextId),
                text = "A relates to B",
                mentions = listOf(EntityMention("A", "Entity", "A", MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )
        mockMvc.perform(
            post("/api/v1/contexts/$contextId/discovery/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mode":"ENTITY","entityId":"A"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mode").value("ENTITY"))
            .andExpect(jsonPath("$.supported").value(true))
            .andExpect(jsonPath("$.propositions").isArray)
    }

    @Test
    fun `GET why returns 404 for an unknown proposition`() {
        mockMvc.perform(get("/api/v1/contexts/$contextId/discovery/why/does-not-exist"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET projection-health returns a per-target summary`() {
        mockMvc.perform(get("/api/v1/contexts/$contextId/discovery/projection-health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.perTarget").isArray)
    }

    @Test
    fun `POST collector dry-run returns a non-mutating preview`() {
        mockMvc.perform(post("/api/v1/contexts/$contextId/discovery/collector/dry-run"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dryRun").value(true))
    }

    @Test
    fun `discovery query scopes by the path context, not a body override`() {
        // A proposition exists only under a DIFFERENT context. An ENTITY query under ctx-discovery
        // must not see it, proving the router is built from the path var, not any body context.
        repository.save(
            Proposition(
                id = "other",
                contextId = ContextId("some-other-context"),
                text = "Foreign fact",
                mentions = listOf(EntityMention("A", "Entity", "A", MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )
        mockMvc.perform(
            post("/api/v1/contexts/$contextId/discovery/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mode":"ENTITY","entityId":"A"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.propositions.length()").value(0))
    }

    @Test
    fun `a live store failure is sanitized to a generic 500 without leaking detail`() {
        val failingStore = object : com.embabel.dice.proposition.PropositionStore {
            private val secret = "driver-internal: secret connection string"
            override fun save(proposition: Proposition): Proposition = proposition
            override fun findById(id: String): Proposition? = null
            override fun findByEntity(entityIdentifier: com.embabel.agent.rag.service.RetrievableIdentifier): List<Proposition> = emptyList()
            override fun findByStatus(status: com.embabel.dice.proposition.PropositionStatus): List<Proposition> = emptyList()
            override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
            override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
            override fun findAll(): List<Proposition> = throw RuntimeException(secret)
            override fun query(query: com.embabel.dice.proposition.PropositionQuery): List<Proposition> = throw RuntimeException(secret)
            override fun delete(id: String): Boolean = false
            override fun count(): Int = 0
        }
        val objectMapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .findAndAddModules()
            .build()
        val controller = DiscoveryController(
            store = failingStore,
            projectionRecordStore = emptyRecordStore,
            collectorRunner = noopCollectorRunner,
        )
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(JacksonJsonHttpMessageConverter(objectMapper))
            .build()

        mvc.perform(
            post("/api/v1/contexts/$contextId/discovery/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mode":"ENTITY","entityId":"A"}"""),
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error").value("discovery operation failed"))
    }

    @Test
    fun `no controller or tool public signature exposes a store, RAG, graph, or domain type`() {
        val offenders = mutableListOf<String>()
        signatureTypes(DiscoveryController::class).forEach { check(it, "DiscoveryController", offenders) }
        signatureTypes(DiscoveryTools::class).forEach { check(it, "DiscoveryTools", offenders) }
        assertTrue(
            offenders.isEmpty(),
            "discovery public surface leaks forbidden types:\n${offenders.joinToString("\n")}",
        )
    }

    /** Public-method parameter and return types of [klass], flattened through generic arguments. */
    private fun signatureTypes(klass: KClass<*>): List<KType> =
        klass.functions
            // Only methods declared on the class itself (skip Any.equals/hashCode/toString).
            .filter { it.javaMethod?.declaringClass == klass.java }
            .flatMap { fn -> fn.parameters.map { it.type } + fn.returnType }
            .flatMap { flatten(it) }

    private fun flatten(type: KType): List<KType> =
        listOf(type) + type.arguments.mapNotNull { it.type }.flatMap { flatten(it) }

    private fun check(type: KType, owner: String, offenders: MutableList<String>) {
        val fqn = (type.classifier as? KClass<*>)?.qualifiedName ?: return
        val forbiddenSubstrings = listOf(
            "neo4j", "Cypher", "RetrievableIdentifier", "rag.model.Chunk",
            "com.embabel.agent.rag", "SimilarityResult", "TextSimilaritySearchRequest",
        )
        val forbiddenExact = listOf(
            "com.embabel.dice.proposition.Proposition",
            "com.embabel.dice.query.graph.GraphPath",
            "com.embabel.dice.query.graph.GraphNeighborhood",
            "com.embabel.dice.query.graph.PropositionLineage",
            "com.embabel.dice.projection.lineage.ProjectionRecord",
            "com.embabel.dice.projection.memory.CollectorRunResult",
        )
        forbiddenSubstrings.forEach { needle ->
            if (fqn.contains(needle, ignoreCase = true)) offenders.add("$owner -> $fqn ('$needle')")
        }
        if (fqn in forbiddenExact) offenders.add("$owner -> $fqn (domain/graph/store type)")
    }
}
