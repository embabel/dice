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

import com.embabel.dice.common.UngroundedMentionsFound;
import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.Proposition;
import example.dice.common.ScriptedChatModel;
import example.dice.starter.customers.AccountId;
import example.dice.starter.memory.ScriptedModel.DiceEvents;
import example.dice.starter.tickets.Ticket;
import example.dice.starter.tickets.Ticket.TicketId;
import example.dice.starter.tickets.Tickets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Files four tickets for two accounts and reads back what the support desk remembers.
 */
@SpringBootTest
@Import(ScriptedModel.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class TicketMemoryTests {

    static final AccountId ACME = new AccountId("acme");
    static final AccountId GLOBEX = new AccountId("globex");

    static final Ticket TICKET_1042 = new Ticket(new TicketId("ticket-1042"), ACME, """
            Ada Okafor from Acme wrote in on 3 November. Acme is on the Team plan. \
            Ada reports that CSV export times out for reports over 50,000 rows. \
            She prefers to be contacted by email.""");

    @Autowired Tickets tickets;
    @Autowired CustomerMemory memory;
    @Autowired DiceEvents diceEvents;
    @Autowired AccountEntities entities;
    @Autowired ScriptedChatModel model;

    @BeforeAll
    void fileTickets() {

        tickets.file(TICKET_1042);

        tickets.file(new Ticket(new TicketId("ticket-1057"), ACME, """
                Follow-up with Ada Okafor on 9 November. Ada confirmed the CSV export fix works for \
                her largest report. Acme wants single sign-on enabled before their audit in December."""));

        tickets.file(new Ticket(new TicketId("ticket-2210"), GLOBEX, """
                Ben Ruiz at Globex asked for a copy of the October invoice. \
                Globex is on the Starter plan."""));

        // Globex has a contact with the same name as Acme's.
        tickets.file(new Ticket(new TicketId("ticket-2231"), GLOBEX, """
                Ada Okafor at Globex asked to be the billing contact for the account."""));
    }

    @Test
    void remembersTheFactsFromEveryTicketOfAnAccount() {

        assertThat(memory.currentFacts(ACME)).extracting(Proposition::getText).containsExactlyInAnyOrder(
                "Acme is on the Team plan",
                "Ada Okafor is a contact at Acme",
                "CSV export times out at Acme for reports over 50,000 rows",
                "She prefers to be contacted by email",
                "Ada Okafor confirmed the CSV export fix works",
                "Acme wants single sign-on enabled before its December audit");
    }

    @Test
    void keepsEachAccountsFactsToItself() {

        assertThat(memory.currentFacts(GLOBEX)).extracting(Proposition::getText).containsExactlyInAnyOrder(
                "Globex is on the Starter plan",
                "Ben Ruiz asked for a copy of the October invoice for Globex",
                "Ada Okafor is the billing contact at Globex");

        assertThat(memory.currentFacts(new AccountId("initech"))).isEmpty();
    }

    @Test
    void everyFactPointsBackAtItsTicket() {

        Map<String, List<String>> ticketOfEachFact = memory.currentFacts(ACME).stream()
                .collect(Collectors.toMap(Proposition::getText, Proposition::getGrounding));

        assertThat(ticketOfEachFact).containsOnly(
                Map.entry("Acme is on the Team plan", List.of("ticket-1042")),
                Map.entry("Ada Okafor is a contact at Acme", List.of("ticket-1042")),
                Map.entry("CSV export times out at Acme for reports over 50,000 rows", List.of("ticket-1042")),
                Map.entry("She prefers to be contacted by email", List.of("ticket-1042")),
                Map.entry("Ada Okafor confirmed the CSV export fix works", List.of("ticket-1057")),
                Map.entry("Acme wants single sign-on enabled before its December audit", List.of("ticket-1057")));
    }

    @Test
    void resolvesAMentionInALaterTicketToTheEntityFromAnEarlierOne() {

        var first = mention(ACME, "Acme is on the Team plan", "Acme");
        var later = mention(ACME, "Acme wants single sign-on enabled before its December audit", "Acme");

        assertThat(later.getResolvedId()).isNotNull().isEqualTo(first.getResolvedId());
        assertThat(entities.of(ACME).findById(first.getResolvedId()).getName()).isEqualTo("Acme");
    }

    @Test
    void givesEachAccountItsOwnEntityForASharedName() {

        var atAcme = mention(ACME, "Ada Okafor is a contact at Acme", "Ada Okafor");
        var atGlobex = mention(GLOBEX, "Ada Okafor is the billing contact at Globex", "Ada Okafor");

        assertThat(atAcme.getResolvedId()).isNotNull();
        assertThat(atGlobex.getResolvedId()).isNotNull().isNotEqualTo(atAcme.getResolvedId());

        assertThat(entities.of(GLOBEX).findById(atGlobex.getResolvedId()).getName()).isEqualTo("Ada Okafor");
        assertThat(entities.of(GLOBEX).findById(atAcme.getResolvedId())).isNull();
        assertThat(entities.of(ACME).findById(atGlobex.getResolvedId())).isNull();
    }

    @Test
    void filingTheSameTicketAgainStoresNothingNew() {

        var factsBefore = memory.currentFacts(ACME);
        var modelCallsBefore = model.promptsSeen().size();

        tickets.file(TICKET_1042);

        assertThat(memory.currentFacts(ACME)).containsExactlyInAnyOrderElementsOf(factsBefore);
        assertThat(model.promptsSeen()).hasSize(modelCallsBefore);
    }

    @Test
    void dropsAMentionItsSentenceNeverMakesAndReportsIt() {

        // The recorded reply for ticket-1042 tags this sentence with Ada Okafor, a name the
        // sentence does not contain.
        assertThat(fact(ACME, "She prefers to be contacted by email").getMentions()).isEmpty();

        var reports = diceEvents.ofType(UngroundedMentionsFound.class);

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getChunkId()).isEqualTo("ticket-1042");
        assertThat(reports.get(0).getMentions()).hasSize(1);
        assertThat(reports.get(0).getMentions().get(0).getMention().getSpan()).isEqualTo("Ada Okafor");
    }

    @Test
    void sendsTheSchemaAndTheTicketTextToTheModel() {

        assertThat(model.promptsSeen()).hasSize(4);
        assertThat(model.promptsSeen().get(0)).contains("Customer", "Contact", "Ada Okafor from Acme wrote in");
    }

    @Test
    void keepsTheDecayRateTheModelGaveEachFact() {

        assertThat(fact(ACME, "Acme is on the Team plan").getDecay()).isEqualTo(0.005);
        assertThat(fact(ACME, "CSV export times out at Acme for reports over 50,000 rows").getDecay()).isEqualTo(0.05);
    }

    /**
     * Finds one remembered fact by its text.
     *
     * @param account whose memory to look in
     * @param text    the fact's exact text
     * @return the fact
     */
    private Proposition fact(AccountId account, String text) {
        return memory.currentFacts(account).stream()
                .filter(fact -> fact.getText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Finds one mention on one of an account's facts.
     *
     * @param account whose memory to look in
     * @param text    the fact's exact text
     * @param span    the mention's exact text
     * @return the mention
     */
    private EntityMention mention(AccountId account, String text, String span) {
        return fact(account, text).getMentions().stream()
                .filter(mention -> mention.getSpan().equals(span))
                .findFirst()
                .orElseThrow();
    }
}
