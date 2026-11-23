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
package com.embabel.dice.storage.sqlite

import com.embabel.dice.audit.AuditCoverageException
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.storage.AuditLogSchema
import com.embabel.dice.storage.DrivineAuditLogStore
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.SqliteWorldProgression
import com.embabel.dice.storage.TestApplication
import com.embabel.dice.storage.diceStorageCatalog
import com.embabel.dice.store.ContextReadScope
import com.embabel.dice.store.ReadBudget
import com.embabel.dice.store.ReadCriteria
import com.embabel.dice.store.ReadQuery
import com.embabel.dice.store.ScopedReadSpec
import com.embabel.dice.temporal.TemporalMetadata
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.drivine.manager.PersistenceManager
import com.embabel.dice.storage.cypherQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class SqliteWorldProgressionIntegrationTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `an accepted plan change survives restart and a return to Pro creates a new occurrence`() {
        val clock = advancingClock()
        val firstId = start().use { app ->
            val (repository, audit) = stores(app, clock)
            val progression = SqliteWorldProgression.enable(repository, audit)
            val first = repository.save(plan("Pro").withTemporal(TemporalMetadata(validFrom = startedAt)))
            assertThatThrownBy { audit.beliefSetAsOf(contextId, recordedAt) }
                .isInstanceOf(AuditCoverageException::class.java)
            audit.initializeBaseline(contextId, startedAt, listOf(first))

            val basic = progression.record("billing-event-1", first, plan("Basic"), movedAt)
            val previous = requireNotNull(repository.findById(first.id))
            assertThat(previous.status).isEqualTo(PropositionStatus.SUPERSEDED)
            assertThat(previous.temporal).isEqualTo(TemporalMetadata(validFrom = startedAt, validTo = movedAt))
            assertThat(previous.confidence).isEqualTo(first.confidence)
            assertThat(basic.temporal).isEqualTo(TemporalMetadata(validFrom = movedAt, supersedes = listOf(first.id)))

            val handoff = audit.history(contextId, basic.id).single().recordedAt
            assertThat(audit.history(contextId, first.id).single().recordedAt).isEqualTo(handoff)
            assertThat(audit.beliefSetAsOf(contextId, handoff.minusNanos(1)).single().status)
                .isEqualTo(PropositionStatus.ACTIVE)
            assertThat(audit.beliefSetAsOf(contextId, handoff).map { it.status })
                .containsExactlyInAnyOrder(PropositionStatus.SUPERSEDED, PropositionStatus.ACTIVE)
            first.id
        }

        start(ensureSchema = false).use { app ->
            val (repository, audit) = stores(app, clock)
            val progression = SqliteWorldProgression.enable(repository, audit)
            val basic = scopedHistory(repository).single { it.status == PropositionStatus.ACTIVE }
            val proAgain = progression.record("billing-event-2", basic, plan("Pro"), returnedAt)
            assertThat(proAgain.id).isNotEqualTo(firstId)
            assertThat(progression.record("billing-event-2", basic, plan("Pro"), returnedAt).id)
                .isEqualTo(proAgain.id)
            assertThat(repository.save(plan("Pro")).id).isEqualTo(proAgain.id)

            val periods = scopedHistory(repository)
            assertThat(periods).hasSize(3)
            assertThat(periods.filter { it.temporal!!.isCurrentAsOf(movedAt.minusNanos(1)) }.map { it.id })
                .containsExactly(firstId)
            assertThat(periods.filter { it.temporal!!.isCurrentAsOf(movedAt) }.map { it.id }).containsExactly(basic.id)
            assertThat(periods.filter { it.temporal!!.isCurrentAsOf(returnedAt) }.map { it.id }).containsExactly(proAgain.id)
            assertThat(audit.beliefSetAsOf(contextId, clock.instant()).map { it.id })
                .containsExactlyInAnyOrder(firstId, basic.id, proAgain.id)
        }
    }

    @Test
    fun `a failed successor write rolls back the predecessor and the audit`() {
        start().use { app ->
            val (repository, audit) = stores(app, advancingClock())
            val progression = SqliteWorldProgression.enable(repository, audit)
            val first = repository.save(plan("Pro"))
            audit.initializeBaseline(contextId, startedAt, listOf(first))
            val locator = ConnectorRef("billing", "invoice")
            app.getBean(PersistenceManager::class.java).execute(
                cypherQuery(
                    "MERGE (s:Source {key: \$key}) " +
                        "SET s.kind = 'connector', s.connectorId = 'old', s.externalId = 'key'",
                ).bind(mapOf("key" to locator.key())),
            )
            val rejected = plan("Basic").withProvenance(listOf(ProvenanceEntry(locator)))

            assertThatThrownBy { progression.record("billing-event-1", first, rejected, movedAt) }
                .hasMessageContaining("Source key collision")
            assertThat(scopedHistory(repository).single().status).isEqualTo(PropositionStatus.ACTIVE)
            assertThat(audit.history(contextId, first.id)).isEmpty()

            repository.save(plan("Basic"))
            assertThatThrownBy { progression.record("billing-event-1", first, plan("Basic"), movedAt) }
                .hasMessageContaining("different active occurrence")
            assertThat(scopedHistory(repository).map { it.status }).containsOnly(PropositionStatus.ACTIVE)
            assertThat(audit.history(contextId, first.id)).isEmpty()
        }
    }

    private fun start(ensureSchema: Boolean = true): ConfigurableApplicationContext =
        SpringApplicationBuilder(TestApplication::class.java, AuditConfig::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--database.datasources.neo.type=SQLITE",
                "--database.datasources.neo.database-name=${temp.resolve("dice.db")}",
                "--database.datasources.neo.host=",
                "--database.datasources.neo.port=0",
                "--drivine.schema.enabled=$ensureSchema",
            )

    private fun stores(app: ConfigurableApplicationContext, clock: Clock) =
        app.getBean(DrivinePropositionRepository::class.java) to DrivineAuditLogStore(
            app.getBean(PersistenceManager::class.java),
            app.getBean(PlatformTransactionManager::class.java),
            clock,
        )

    private fun scopedHistory(repository: DrivinePropositionRepository): List<Proposition> =
        repository.scopedReads().query(
            ScopedReadSpec.of(ContextReadScope.ofValues(contextId)),
            ReadQuery.of(ReadCriteria.empty()),
            ReadBudget.ofRows(10),
        )

    private fun plan(name: String) = Proposition.draft(contextId, "Acme is on the $name plan", 0.9).toProposition()

    private fun advancingClock(): Clock = object : Clock() {
        private val ticks = AtomicLong()
        override fun instant(): Instant = recordedAt.plusSeconds(ticks.getAndIncrement())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    @Configuration(proxyBeanMethods = false)
    class AuditConfig {
        @Bean
        fun worldProgressionAuditCatalog() = diceStorageCatalog(listOf(AuditLogSchema.forRegistration()))
    }

    companion object {
        private const val contextId = "acme-plan"
        private val startedAt = Instant.parse("2026-11-01T00:00:00Z")
        private val movedAt = Instant.parse("2026-11-08T00:00:00Z")
        private val returnedAt = Instant.parse("2026-11-12T00:00:00Z")
        private val recordedAt = Instant.parse("2026-11-13T00:00:00Z")
    }
}
