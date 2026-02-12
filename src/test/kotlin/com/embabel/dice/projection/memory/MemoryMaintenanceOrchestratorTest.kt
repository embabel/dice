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
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.dice.operations.abstraction.PropositionAbstractor
import com.embabel.dice.proposition.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class MemoryMaintenanceOrchestratorTest {

    private lateinit var repository: PropositionRepository
    private lateinit var consolidator: MemoryConsolidator
    private lateinit var abstractor: PropositionAbstractor
    private val contextId = ContextId("test-context")

    private fun proposition(
        text: String,
        confidence: Double = 0.8,
        decay: Double = 0.1,
        entityId: String? = null,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        created: Instant = Instant.now(),
        revised: Instant = Instant.now(),
    ): Proposition {
        val mentions = if (entityId != null) {
            listOf(EntityMention(span = entityId, type = "Entity", resolvedId = entityId))
        } else {
            emptyList()
        }
        return Proposition(
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = confidence,
            decay = decay,
            status = status,
            created = created,
            revised = revised,
        )
    }

    @BeforeEach
    fun setup() {
        repository = mockk(relaxed = true)
        consolidator = mockk(relaxed = true)
        abstractor = mockk(relaxed = true)

        every { repository.query(any()) } returns emptyList()
    }

    @Nested
    inner class BuilderTests {

        @Test
        fun `builds with required parameters only`() {
            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)

            assertNotNull(orchestrator)
        }

        @Test
        fun `builds with all optional parameters`() {
            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)
                .withAbstractionThreshold(3)
                .withAbstractionTargetCount(2)
                .withRetireBelow(0.1)
                .withRetireDecayK(3.0)

            assertNotNull(orchestrator)
        }

        @Test
        fun `fluent methods return new instance`() {
            val base = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)

            val withAbstractor = base.withAbstractor(abstractor)
            val withRetire = base.withRetireBelow(0.1)

            assertNotEquals(base, withAbstractor)
            assertNotEquals(base, withRetire)
        }
    }

    @Nested
    inner class ConsolidationPhaseTests {

        @Test
        fun `skips consolidation when no session propositions`() {
            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)

            val result = orchestrator.maintain(contextId)

            assertNull(result.consolidation)
            verify(exactly = 0) { consolidator.consolidate(any(), any()) }
        }

        @Test
        fun `consolidates session propositions against existing`() {
            val existing = listOf(proposition("existing fact", entityId = "alice"))
            val session = listOf(proposition("new fact"))

            every { repository.query(any()) } returns existing
            every { consolidator.consolidate(session, existing) } returns ConsolidationResult(
                promoted = listOf(session[0].copy(status = PropositionStatus.ACTIVE)),
                reinforced = emptyList(),
                discarded = emptyList(),
                merged = emptyList(),
            )

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)

            val result = orchestrator.maintain(contextId, session)

            assertNotNull(result.consolidation)
            assertEquals(1, result.consolidation!!.promoted.size)
            verify { consolidator.consolidate(session, existing) }
        }

        @Test
        fun `persists promoted propositions`() {
            val session = listOf(proposition("promoted fact"))
            val promoted = session[0].copy(status = PropositionStatus.ACTIVE)

            every { consolidator.consolidate(any(), any()) } returns ConsolidationResult(
                promoted = listOf(promoted),
                reinforced = emptyList(),
                discarded = emptyList(),
                merged = emptyList(),
            )

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)

            orchestrator.maintain(contextId, session)

            verify { repository.saveAll(listOf(promoted)) }
        }

        @Test
        fun `persists reinforced propositions`() {
            val session = listOf(proposition("reinforced fact"))
            val reinforced = proposition("existing reinforced", confidence = 0.9)

            every { consolidator.consolidate(any(), any()) } returns ConsolidationResult(
                promoted = emptyList(),
                reinforced = listOf(reinforced),
                discarded = emptyList(),
                merged = emptyList(),
            )

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)

            orchestrator.maintain(contextId, session)

            verify { repository.saveAll(listOf(reinforced)) }
        }

        @Test
        fun `persists merged propositions`() {
            val session = listOf(proposition("to merge"))
            val mergeResult = proposition("merged result")
            val merge = PropositionMerge(sources = listOf(session[0]), result = mergeResult)

            every { consolidator.consolidate(any(), any()) } returns ConsolidationResult(
                promoted = emptyList(),
                reinforced = emptyList(),
                discarded = emptyList(),
                merged = listOf(merge),
            )

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)

            orchestrator.maintain(contextId, session)

            verify { repository.saveAll(listOf(mergeResult)) }
        }
    }

    @Nested
    inner class AbstractionPhaseTests {

        @Test
        fun `skips abstraction when no abstractor configured`() {
            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)

            val result = orchestrator.maintain(contextId)

            assertTrue(result.abstractions.isEmpty())
            assertTrue(result.superseded.isEmpty())
        }

        @Test
        fun `skips abstraction when entity group below threshold`() {
            val props = (1..4).map { proposition("fact $it", entityId = "alice") }
            every { repository.query(any()) } returns props

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)
                .withAbstractionThreshold(5)

            val result = orchestrator.maintain(contextId)

            assertTrue(result.abstractions.isEmpty())
            verify(exactly = 0) { abstractor.abstract(any<com.embabel.dice.operations.PropositionGroup>(), any()) }
        }

        @Test
        fun `abstracts entity group meeting threshold`() {
            val props = (1..5).map { proposition("fact $it about alice", entityId = "alice") }
            val abstraction = Proposition(
                contextId = contextId,
                text = "Alice abstraction",
                mentions = emptyList(),
                confidence = 0.9,
                level = 1,
                sourceIds = props.map { it.id },
            )

            every { repository.query(any()) } returns props
            every { abstractor.abstract(any<com.embabel.dice.operations.PropositionGroup>(), any()) } returns listOf(abstraction)

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)
                .withAbstractionThreshold(5)
                .withAbstractionTargetCount(1)

            val result = orchestrator.maintain(contextId)

            assertEquals(1, result.abstractions.size)
            assertEquals("Alice abstraction", result.abstractions[0].text)
        }

        @Test
        fun `marks source propositions as SUPERSEDED`() {
            val props = (1..5).map { proposition("fact $it", entityId = "alice") }

            every { repository.query(any()) } returns props
            every { abstractor.abstract(any<com.embabel.dice.operations.PropositionGroup>(), any()) } returns listOf(
                Proposition(
                    contextId = contextId,
                    text = "abstraction",
                    mentions = emptyList(),
                    confidence = 0.9,
                    level = 1,
                    sourceIds = props.map { it.id },
                )
            )

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)
                .withAbstractionThreshold(5)

            val result = orchestrator.maintain(contextId)

            assertEquals(5, result.superseded.size)
            assertTrue(result.superseded.all { it.status == PropositionStatus.SUPERSEDED })
        }

        @Test
        fun `persists abstractions and superseded propositions`() {
            val props = (1..5).map { proposition("fact $it", entityId = "alice") }
            val abstraction = Proposition(
                contextId = contextId,
                text = "abstraction",
                mentions = emptyList(),
                confidence = 0.9,
                level = 1,
                sourceIds = props.map { it.id },
            )

            every { repository.query(any()) } returns props
            every { abstractor.abstract(any<com.embabel.dice.operations.PropositionGroup>(), any()) } returns listOf(abstraction)

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)
                .withAbstractionThreshold(5)

            orchestrator.maintain(contextId)

            // Abstractions persisted
            verify { repository.saveAll(listOf(abstraction)) }
            // Superseded sources persisted
            verify { repository.saveAll(match<List<Proposition>> { list ->
                list.size == 5 && list.all { it.status == PropositionStatus.SUPERSEDED }
            }) }
        }

        @Test
        fun `respects custom abstraction target count`() {
            val props = (1..5).map { proposition("fact $it", entityId = "alice") }

            every { repository.query(any()) } returns props
            every { abstractor.abstract(any<com.embabel.dice.operations.PropositionGroup>(), eq(2)) } returns emptyList()

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)
                .withAbstractionThreshold(5)
                .withAbstractionTargetCount(2)

            orchestrator.maintain(contextId)

            verify { abstractor.abstract(any<com.embabel.dice.operations.PropositionGroup>(), 2) }
        }

        @Test
        fun `abstracts multiple entity groups independently`() {
            val aliceProps = (1..5).map { proposition("alice fact $it", entityId = "alice") }
            val bobProps = (1..5).map { proposition("bob fact $it", entityId = "bob") }

            every { repository.query(any()) } returns aliceProps + bobProps
            every { abstractor.abstract(any<com.embabel.dice.operations.PropositionGroup>(), any()) } returns listOf(
                Proposition(
                    contextId = contextId,
                    text = "group abstraction",
                    mentions = emptyList(),
                    confidence = 0.9,
                    level = 1,
                    sourceIds = listOf("source"),
                )
            )

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)
                .withAbstractionThreshold(5)

            val result = orchestrator.maintain(contextId)

            assertEquals(2, result.abstractions.size)
            assertEquals(10, result.superseded.size)
        }

        @Test
        fun `only groups level 0 propositions for abstraction`() {
            // The query uses withMaxLevel(0), so higher-level propositions should be excluded
            // by the repository query. We verify the query is correct.
            val querySlot = slot<PropositionQuery>()
            every { repository.query(capture(querySlot)) } returns emptyList()

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)

            orchestrator.maintain(contextId)

            // The abstraction phase query should filter to level 0
            val queries = mutableListOf<PropositionQuery>()
            verify { repository.query(capture(queries)) }
            val abstractionQuery = queries.find { it.maxLevel == 0 }
            assertNotNull(abstractionQuery)
            assertEquals(PropositionStatus.ACTIVE, abstractionQuery!!.status)
        }
    }

    @Nested
    inner class RetirementPhaseTests {

        @Test
        fun `skips retirement when retireBelow is null`() {
            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)

            val result = orchestrator.maintain(contextId)

            assertTrue(result.retired.isEmpty())
            verify(exactly = 0) { repository.delete(any()) }
        }

        @Test
        fun `retires propositions below threshold`() {
            // Create old propositions with high decay that will have low effective confidence
            val old = Instant.now().minus(365, ChronoUnit.DAYS)
            val expiredProp = proposition("old fact", confidence = 0.5, decay = 0.5, revised = old)
            val freshProp = proposition("fresh fact", confidence = 0.9, decay = 0.01)

            every { repository.query(any()) } returns listOf(expiredProp, freshProp)

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withRetireBelow(0.3)

            val result = orchestrator.maintain(contextId)

            assertTrue(result.retired.isNotEmpty())
            verify { repository.delete(expiredProp.id) }
            verify(exactly = 0) { repository.delete(freshProp.id) }
        }

        @Test
        fun `uses custom decay K for retirement`() {
            val old = Instant.now().minus(30, ChronoUnit.DAYS)
            val prop = proposition("moderate fact", confidence = 0.5, decay = 0.3, revised = old)

            every { repository.query(any()) } returns listOf(prop)

            // With very high K, more propositions decay below threshold
            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withRetireBelow(0.3)
                .withRetireDecayK(10.0)

            val result = orchestrator.maintain(contextId)

            // Effective confidence with high K should be very low for this old proposition
            assertTrue(result.retired.contains(prop))
        }

        @Test
        fun `does not retire propositions above threshold`() {
            val freshProp = proposition("fresh fact", confidence = 0.9, decay = 0.01)

            every { repository.query(any()) } returns listOf(freshProp)

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withRetireBelow(0.1)

            val result = orchestrator.maintain(contextId)

            assertTrue(result.retired.isEmpty())
            verify(exactly = 0) { repository.delete(any()) }
        }
    }

    @Nested
    inner class ResultTests {

        @Test
        fun `totalPersisted counts all persisted items`() {
            val result = MaintenanceResult(
                consolidation = ConsolidationResult(
                    promoted = listOf(proposition("promoted")),
                    reinforced = listOf(proposition("reinforced")),
                    discarded = listOf(proposition("discarded")),
                    merged = listOf(PropositionMerge(emptyList(), proposition("merged"))),
                ),
                abstractions = listOf(
                    Proposition(
                        contextId = contextId,
                        text = "abstract",
                        mentions = emptyList(),
                        confidence = 0.9,
                        level = 1,
                        sourceIds = listOf("src"),
                    )
                ),
                superseded = listOf(proposition("superseded", status = PropositionStatus.SUPERSEDED)),
                retired = emptyList(),
            )

            // promoted(1) + reinforced(1) + merged(1) + abstractions(1) + superseded(1) = 5
            assertEquals(5, result.totalPersisted)
        }

        @Test
        fun `totalPersisted handles null consolidation`() {
            val result = MaintenanceResult(
                consolidation = null,
                abstractions = listOf(
                    Proposition(
                        contextId = contextId,
                        text = "abstract",
                        mentions = emptyList(),
                        confidence = 0.9,
                        level = 1,
                        sourceIds = listOf("src"),
                    )
                ),
                superseded = emptyList(),
                retired = emptyList(),
            )

            assertEquals(1, result.totalPersisted)
        }

        @Test
        fun `totalRemoved counts retired items`() {
            val result = MaintenanceResult(
                consolidation = null,
                abstractions = emptyList(),
                superseded = emptyList(),
                retired = listOf(proposition("retired1"), proposition("retired2")),
            )

            assertEquals(2, result.totalRemoved)
        }
    }

    @Nested
    inner class FullPipelineTests {

        @Test
        fun `runs all three phases in order`() {
            val session = listOf(proposition("session fact"))
            val aliceProps = (1..5).map { proposition("alice fact $it", entityId = "alice") }

            val old = Instant.now().minus(365, ChronoUnit.DAYS)
            val decayedProp = proposition("old fact", confidence = 0.5, decay = 0.5, revised = old)

            // Consolidation returns promoted
            every { consolidator.consolidate(any(), any()) } returns ConsolidationResult(
                promoted = session.map { it.copy(status = PropositionStatus.ACTIVE) },
                reinforced = emptyList(),
                discarded = emptyList(),
                merged = emptyList(),
            )

            // Repository returns different results per query context
            // First call: consolidation phase (existing ACTIVE props)
            // Second call: abstraction phase (level 0 ACTIVE props)
            // Third call: retirement phase (ACTIVE props)
            val queryResults = mutableListOf(
                emptyList(),                    // consolidation: existing
                aliceProps,                     // abstraction: level 0 active
                listOf(decayedProp),            // retirement: active
            )
            every { repository.query(any()) } answers {
                if (queryResults.isNotEmpty()) queryResults.removeFirst() else emptyList()
            }

            every { abstractor.abstract(any<com.embabel.dice.operations.PropositionGroup>(), any()) } returns listOf(
                Proposition(
                    contextId = contextId,
                    text = "alice abstraction",
                    mentions = emptyList(),
                    confidence = 0.9,
                    level = 1,
                    sourceIds = aliceProps.map { it.id },
                )
            )

            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)
                .withAbstractionThreshold(5)
                .withRetireBelow(0.3)

            val result = orchestrator.maintain(contextId, session)

            // All phases ran
            assertNotNull(result.consolidation)
            assertEquals(1, result.consolidation!!.promoted.size)
            assertEquals(1, result.abstractions.size)
            assertEquals(5, result.superseded.size)
            assertEquals(1, result.retired.size)
        }

        @Test
        fun `scheduled maintenance without session propositions`() {
            val orchestrator = MemoryMaintenanceOrchestrator
                .withRepository(repository)
                .withConsolidator(consolidator)
                .withAbstractor(abstractor)
                .withRetireBelow(0.1)

            val result = orchestrator.maintain(contextId)

            assertNull(result.consolidation)
            verify(exactly = 0) { consolidator.consolidate(any(), any()) }
        }
    }
}
