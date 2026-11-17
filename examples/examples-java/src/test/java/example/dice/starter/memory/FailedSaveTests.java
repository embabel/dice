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
package example.dice.starter.memory;

import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.agent.rag.service.RelationshipData;
import com.embabel.agent.rag.service.RetrievableIdentifier;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import example.dice.common.ScriptedChatModel;
import example.dice.starter.tickets.Tickets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.atomic.AtomicBoolean;

import static example.dice.starter.memory.RefiledTicketTests.AS_EDITED;
import static example.dice.starter.memory.RefiledTicketTests.AS_FILED;
import static example.dice.starter.memory.TicketMemoryTests.ACME;
import static example.dice.starter.memory.TicketMemoryTests.GLOBEX;
import static example.dice.starter.memory.TicketMemoryTests.TICKET_1042;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Files a ticket whose save fails, then files it again. Each test starts with empty stores.
 */
@SpringBootTest
@Import({ScriptedModel.class, FailedSaveTests.FailingEntityStore.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FailedSaveTests {

    @Autowired Tickets tickets;
    @Autowired CustomerMemory memory;
    @Autowired ScriptedChatModel model;
    @Autowired AccountStores stores;

    @Test
    void aTicketWhoseSaveFailedIsStoredOnceWhenItIsFiledAgain() {

        // The facts and entities are saved, then the first relationship write throws.
        assertThatIllegalStateException()
                .isThrownBy(() -> tickets.file(TICKET_1042))
                .withMessage("The entity store is unavailable");

        // The writer took the facts and the entities back out.
        assertThat(memory.currentFacts(ACME)).isEmpty();
        var entities = stores.of(ACME).entities();
        assertThat(entities.findByLabel("Customer")).isEmpty();
        assertThat(entities.findByLabel("Contact")).isEmpty();
        assertThat(entities.findByLabel("Plan")).isEmpty();

        tickets.file(TICKET_1042);

        assertThat(memory.currentFacts(ACME)).extracting(Proposition::getText).containsExactlyInAnyOrder(
                "Acme is on the Team plan",
                "Ada Okafor is a contact at Acme",
                "CSV export times out at Acme for reports over 50,000 rows",
                "She prefers to be contacted by email");
        assertThat(model.promptsSeen()).hasSize(2);
        assertThat(entities.findByLabel("Customer")).extracting(entity -> entity.getName()).containsExactly("Acme");
    }

    @Test
    void anEditWhoseSaveFailedIsFinishedWhenItIsFiledAgain() {

        // The account's first save fails, so the ticket is filed twice to store it.
        assertThatIllegalStateException().isThrownBy(() -> tickets.file(AS_FILED));
        tickets.file(AS_FILED);
        ((FailsOnce) stores.of(GLOBEX).entities()).failAgain();

        assertThatIllegalStateException()
                .isThrownBy(() -> tickets.file(AS_EDITED))
                .withMessage("The entity store is unavailable");

        // The first revision was retired before the save, and the writer took the edit back out.
        assertThat(memory.currentFacts(GLOBEX)).isEmpty();

        tickets.file(AS_EDITED);

        assertThat(memory.currentFacts(GLOBEX)).extracting(Proposition::getText).containsExactlyInAnyOrder(
                "Globex is on the Team plan",
                "Ben Ruiz asked for a copy of the October invoice for Globex");
    }

    /**
     * Gives each account an entity store whose first relationship write fails.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingEntityStore {

        /**
         * Takes the place of the application's {@code AccountStores}.
         *
         * @param schema       the support desk's schema
         * @param propositions the application's fact store
         * @return account stores built on failing entity stores
         */
        @Bean
        @Primary
        AccountStores accountStoresThatFailOnce(@Qualifier("supportSchema") DataDictionary schema,
                InMemoryPropositionRepository propositions) {
            return new AccountStores(schema, propositions) {
                @Override
                NamedEntityDataRepository newEntityStore() {
                    return new FailsOnce(schema);
                }
            };
        }
    }

    /**
     * An entity store whose first relationship write throws. The writer saves entities and
     * facts before it writes relationships, so the failure comes after both are stored.
     */
    static class FailsOnce extends InMemoryNamedEntityDataRepository {

        private final AtomicBoolean failed = new AtomicBoolean();

        /**
         * @param schema the schema the store reads entity types from
         */
        FailsOnce(DataDictionary schema) {
            super(schema);
        }

        /**
         * Makes the next relationship write throw as well.
         */
        void failAgain() {
            failed.set(false);
        }

        @Override
        public void createRelationship(RetrievableIdentifier a, RetrievableIdentifier b, RelationshipData relationship) {
            if (failed.compareAndSet(false, true)) {
                throw new IllegalStateException("The entity store is unavailable");
            }
            super.createRelationship(a, b, relationship);
        }
    }
}
