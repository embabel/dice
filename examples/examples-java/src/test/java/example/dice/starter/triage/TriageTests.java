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
package example.dice.starter.triage;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.RatingResult;
import example.dice.common.ScriptedChatModel;
import example.dice.starter.customers.AccountId;
import example.dice.starter.memory.ScriptedModel;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Files the four tickets of the memory tests, then triages three of them against recorded
 * decisions. The model's answers are recorded; what the tests check is how the ticket and the
 * customer's facts reach the decision, and what happens when the reply cannot be read.
 */
@SpringBootTest
@Import(ScriptedModel.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class TriageTests {

    static final AccountId ACME = new AccountId("acme");
    static final AccountId GLOBEX = new AccountId("globex");

    static final Ticket TICKET_1042 = new Ticket(new TicketId("ticket-1042"), ACME, 1, """
            Ada Okafor from Acme wrote in on 3 November. Acme is on the Team plan. \
            Ada reports that CSV export times out for reports over 50,000 rows. \
            She prefers to be contacted by email.""");

    static final Ticket TICKET_1057 = new Ticket(new TicketId("ticket-1057"), ACME, 1, """
            Follow-up with Ada Okafor on 9 November. Ada confirmed the CSV export fix works for \
            her largest report. Acme wants single sign-on enabled before their audit in December.""");

    static final Ticket TICKET_2210 = new Ticket(new TicketId("ticket-2210"), GLOBEX, 1, """
            Ben Ruiz at Globex asked for a copy of the October invoice. \
            Globex is on the Starter plan.""");

    // Globex has a contact with the same name as Acme's.
    static final Ticket TICKET_2231 = new Ticket(new TicketId("ticket-2231"), GLOBEX, 1, """
            Ada Okafor at Globex asked to be the billing contact for the account.""");

    @Autowired Tickets tickets;
    @Autowired TicketTriage triage;
    @Autowired ScriptedChatModel model;
    @Autowired Ai ai;

    @BeforeAll
    void fileTickets() {
        tickets.file(TICKET_1042);
        tickets.file(TICKET_1057);
        tickets.file(TICKET_2210);
        tickets.file(TICKET_2231);
    }

    @Test
    void anAcmeFollowUpAboutAnAuditIsUrgentAndGoesToTheAccountTeam() {

        var result = triage.triage(TICKET_1057);

        assertThat(result.urgent()).isTrue();
        assertThat(result.department()).isEqualTo("account");
        assertThat(result.frustration()).isEqualTo("calm");
        assertThat(result.needsHuman()).isFalse();
    }

    @Test
    void theDecisionSeesWhatIsRememberedAboutTheCustomerAndNothingAboutAnyOther() {

        var input = triage.triage(TICKET_1057).input();

        assertThat(input)
                .startsWith("Follow-up with Ada Okafor")
                .contains("Acme wants single sign-on enabled before its December audit")
                .contains("CSV export times out at Acme for reports over 50,000 rows")
                .doesNotContain("Globex");

        // And the model was sent the same facts.
        var prompts = model.promptsSeen();
        assertThat(prompts.get(prompts.size() - 1))
                .contains("{\"input\":\"Follow-up with Ada Okafor")
                .contains("Acme wants single sign-on enabled before its December audit")
                .doesNotContain("Globex");
    }

    @Test
    void aRequestForAnInvoiceCopyIsNotUrgentAndGoesToBilling() {

        var result = triage.triage(TICKET_2210);

        assertThat(result.urgent()).isFalse();
        assertThat(result.department()).isEqualTo("billing");
        assertThat(result.frustration()).isEqualTo("calm");
        assertThat(result.needsHuman()).isFalse();
        assertThat(result.input())
                .contains("Globex is on the Starter plan")
                .contains("Ben Ruiz asked for a copy of the October invoice for Globex")
                .doesNotContain("Acme");
    }

    @Test
    void aReplyThatCannotBeReadLeavesEveryAnswerEmptyAndAsksForAPerson() {

        var modelCallsBefore = model.promptsSeen().size();

        var result = triage.triage(TICKET_2231);

        assertThat(result.urgent()).isNull();
        assertThat(result.department()).isNull();
        assertThat(result.frustration()).isNull();
        assertThat(result.needsHuman()).isTrue();
        assertThat(result.input())
                .contains("Ada Okafor is the billing contact at Globex")
                .doesNotContain("Acme");

        // The unreadable reply is not retried.
        assertThat(model.promptsSeen()).hasSize(modelCallsBefore + 1);
        assertThat(model.promptsSeen().get(modelCallsBefore)).contains("{\"input\":\"Ada Okafor at Globex asked");
    }

    @Test
    void aReplyThatCannotBeReadComesBackAsAFailedAnswerToEveryQuestion() {

        var input = triage.triage(TICKET_2231).input();
        var modelCallsBefore = model.promptsSeen().size();

        var response = ai.decisions().byRole(TicketTriage.ROLE).ask(input, TicketTriage.TRIAGE);

        assertThat(response.answer(TicketTriage.URGENT))
                .isEqualTo(new PropositionResult.Failure(FailureReason.INVALID_RESPONSE));
        assertThat(response.answer(TicketTriage.DEPARTMENT))
                .isEqualTo(new ClassificationResult.Failure(FailureReason.INVALID_RESPONSE));
        assertThat(response.answer(TicketTriage.FRUSTRATION))
                .isEqualTo(new RatingResult.Failure(FailureReason.INVALID_RESPONSE));
        assertThat(model.promptsSeen()).hasSize(modelCallsBefore + 1);
    }
}
