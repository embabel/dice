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

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.provenance.ProvenanceEntry;
import example.dice.common.ScriptedChatModel;
import example.dice.starter.tickets.Ticket;
import example.dice.starter.tickets.Ticket.TicketId;
import example.dice.starter.tickets.Tickets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static example.dice.starter.memory.TicketMemoryTests.ACME;
import static example.dice.starter.memory.TicketMemoryTests.GLOBEX;
import static example.dice.starter.memory.TicketMemoryTests.TICKET_1042;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Files a ticket more than once: edited, changed back, delivered late, and from several threads
 * at once. Each test starts with empty stores.
 */
@SpringBootTest
@Import(ScriptedModel.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RefiledTicketTests {

    static final String STARTER_PLAN = """
            Ben Ruiz at Globex asked for a copy of the October invoice. \
            Globex is on the Starter plan.""";

    static final String TEAM_PLAN = """
            Ben Ruiz at Globex asked for a copy of the October invoice. \
            Globex is on the Team plan.""";

    static final Ticket AS_FILED = new Ticket(new TicketId("ticket-2210"), GLOBEX, 1, STARTER_PLAN);

    static final Ticket AS_EDITED = new Ticket(new TicketId("ticket-2210"), GLOBEX, 2, TEAM_PLAN);

    static final Ticket EDITED_BACK = new Ticket(new TicketId("ticket-2210"), GLOBEX, 3, STARTER_PLAN);

    @Autowired Tickets tickets;
    @Autowired CustomerMemory memory;
    @Autowired PropositionRepository propositions;
    @Autowired ScriptedChatModel model;

    @Test
    void anEditedTicketReplacesWhatItsEarlierVersionSaid() {

        tickets.file(AS_FILED);
        var modelCallsBefore = model.promptsSeen().size();

        tickets.file(AS_EDITED);

        assertThat(memory.currentFacts(GLOBEX)).extracting(Proposition::getText).containsExactlyInAnyOrder(
                "Globex is on the Team plan",
                "Ben Ruiz asked for a copy of the October invoice for Globex");
        assertThat(model.promptsSeen()).hasSize(modelCallsBefore + 1);

        // Every current fact was read from the edited text.
        assertThat(memory.currentFacts(GLOBEX))
                .flatExtracting(Proposition::getProvenanceEntries)
                .extracting(ProvenanceEntry::getSourceRevision)
                .containsOnly("2");

        // The earlier version's facts are still stored. Only their status changed.
        var superseded = propositions.query(
                PropositionQuery.againstContext(GLOBEX.value()).withStatus(PropositionStatus.SUPERSEDED));
        assertThat(superseded).extracting(Proposition::getText).contains("Globex is on the Starter plan");
    }

    @Test
    void aTicketEditedBackSaysWhatItFirstSaid() {

        tickets.file(AS_FILED);
        tickets.file(AS_EDITED);
        var modelCallsBefore = model.promptsSeen().size();

        // The third revision has the first one's text.
        tickets.file(EDITED_BACK);

        assertThat(memory.currentFacts(GLOBEX)).extracting(Proposition::getText).containsExactlyInAnyOrder(
                "Globex is on the Starter plan",
                "Ben Ruiz asked for a copy of the October invoice for Globex");
        assertThat(memory.currentFacts(GLOBEX))
                .flatExtracting(Proposition::getProvenanceEntries)
                .extracting(ProvenanceEntry::getSourceRevision)
                .containsOnly("3");
        assertThat(model.promptsSeen()).hasSize(modelCallsBefore + 1);
    }

    @Test
    void aLateCopyOfAnEarlierRevisionChangesNothing() {

        tickets.file(AS_FILED);
        tickets.file(AS_EDITED);
        var modelCallsBefore = model.promptsSeen().size();

        // The first revision's event arrives again after the edit, for example on a retry.
        tickets.file(AS_FILED);

        assertThat(memory.currentFacts(GLOBEX)).extracting(Proposition::getText).containsExactlyInAnyOrder(
                "Globex is on the Team plan",
                "Ben Ruiz asked for a copy of the October invoice for Globex");
        assertThat(model.promptsSeen()).hasSize(modelCallsBefore);
    }

    @Test
    void aTicketFiledFromSeveralThreadsAtOnceIsStoredOnce() throws Exception {

        var modelCallsBefore = model.promptsSeen().size();
        var start = new CountDownLatch(1);

        try (var threads = Executors.newFixedThreadPool(8)) {
            var filings = IntStream.range(0, 8)
                    .mapToObj(i -> threads.submit(() -> {
                        start.await();
                        tickets.file(TICKET_1042);
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (var filing : filings) {
                filing.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(memory.currentFacts(ACME)).extracting(Proposition::getText).containsExactlyInAnyOrder(
                "Acme is on the Team plan",
                "Ada Okafor is a contact at Acme",
                "CSV export times out at Acme for reports over 50,000 rows",
                "She prefers to be contacted by email");
        assertThat(model.promptsSeen()).hasSize(modelCallsBefore + 1);
    }
}
