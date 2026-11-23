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

package example.dice.backends;

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.provenance.ProvenanceEntry;
import com.embabel.dice.storage.AuditLogSchema;
import com.embabel.dice.storage.DiceStorageSchema;
import com.embabel.dice.storage.DrivineAuditLogStore;
import com.embabel.dice.storage.DrivinePropositionRepository;
import com.embabel.dice.storage.SqliteWorldProgression;
import com.embabel.dice.store.ContextReadScope;
import com.embabel.dice.store.ReadBudget;
import com.embabel.dice.store.ReadCriteria;
import com.embabel.dice.store.ReadQuery;
import com.embabel.dice.store.ScopedReadSpec;
import com.embabel.dice.temporal.TemporalMetadata;
import org.drivine.manager.PersistenceManager;
import org.drivine.schema.SchemaCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.embabel.dice.storage.DiceStorageSchemaKt.diceStorageCatalog;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "embabel.dice.store.type=",
        "embabel.dice.store.decay.enabled=false",
        "embabel.dice.store.world-progression.enabled=true",
        "embabel.models.default-llm=scripted",
        "database.datasources.local.type=SQLITE",
})
@Import({ScriptedModel.class, WorldProgressionTests.AuditConfiguration.class})
@DirtiesContext
class WorldProgressionTests {

    private static final String ACCOUNT = "acme";

    @TempDir
    static Path temporaryDirectory;

    @Autowired
    DrivinePropositionRepository repository;

    @Autowired
    PersistenceManager persistenceManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) {
        registry.add("database.datasources.local.database-name", () -> temporaryDirectory.resolve("billing.db").toString());
    }

    @Test
    void anAcceptedBillingEventPreservesThePreviousPlanAndItsDates() {

        var signedUpAt = Instant.parse("2025-02-01T00:00:00Z");
        var changedAt = Instant.parse("2025-03-01T00:00:00Z");
        var pro = repository.save(plan("Pro", "signup-17").withTemporal(new TemporalMetadata(null, signedUpAt)));
        var audit = new DrivineAuditLogStore(persistenceManager, transactionManager);
        var progression = SqliteWorldProgression.enable(repository, audit);

        audit.duringWrite(ACCOUNT, () -> {
            audit.initializeBaseline(ACCOUNT, Instant.now(), repository.query(PropositionQuery.againstContext(ACCOUNT)));
            return null;
        });

        // Billing supplies the event ID and the effective date after accepting the change.
        var nextPlan = plan("Basic", "subscription-42");
        var basic = progression.record("subscription-42", pro, nextPlan, changedAt);
        var historyScope = ScopedReadSpec.of(ContextReadScope.ofValues(ACCOUNT));
        var historicalStatuses = ReadCriteria.empty().withStatuses(PropositionStatus.ACTIVE, PropositionStatus.SUPERSEDED);
        var plans = repository.scopedReads().query(historyScope, ReadQuery.of(historicalStatuses), ReadBudget.ofRows(10));

        assertThat(plans).filteredOn(claim -> claim.getTemporal().isCurrentAsOf(changedAt.minusNanos(1)))
                .extracting(Proposition::getId).containsExactly(pro.getId());
        assertThat(plans).filteredOn(claim -> claim.getTemporal().isCurrentAsOf(changedAt))
                .extracting(Proposition::getId).containsExactly(basic.getId());
        assertThat(plans).filteredOn(claim -> claim.getStatus() == PropositionStatus.SUPERSEDED)
                .singleElement().satisfies(previous -> {
                    assertThat(previous.getId()).isEqualTo(pro.getId());
                    assertThat(previous.getTemporal().getValidTo()).isEqualTo(changedAt);
                });
        assertThat(basic.getTemporal().getSupersedes()).containsExactly(pro.getId());
        assertThat(progression.record("subscription-42", pro, nextPlan, changedAt).getId()).isEqualTo(basic.getId());

        var recordedAt = audit.history(ACCOUNT, basic.getId()).getFirst().getRecordedAt();
        assertThat(audit.beliefSetAsOf(ACCOUNT, recordedAt))
                .extracting(claim -> claim.getId()).containsExactlyInAnyOrder(pro.getId(), basic.getId());
    }

    private static Proposition plan(String plan, String billingRecord) {
        return Proposition.draft(ACCOUNT, "Acme is on the " + plan + " plan", 0.9)
                .withProvenance(List.of(new ProvenanceEntry(new ConnectorRef("billing", billingRecord))))
                .toProposition();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuditConfiguration {

        @Bean
        DiceStorageSchema auditSchema() {
            return AuditLogSchema.forRegistration();
        }

        @Bean
        SchemaCatalog auditCatalog(DiceStorageSchema auditSchema) {
            return diceStorageCatalog(List.of(auditSchema)).forDatabase("local");
        }
    }
}
