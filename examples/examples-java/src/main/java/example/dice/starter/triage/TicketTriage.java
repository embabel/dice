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
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.dice.proposition.Proposition;
import example.dice.starter.memory.CustomerMemory;
import example.dice.starter.tickets.Ticket;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Decides how urgent a ticket is, which team gets it and how upset the customer sounds.
 *
 * <p>The model sees the ticket together with everything DICE currently believes about the
 * customer, so a short ticket from a customer with a known history can still be read in context.
 * All three questions go out in one decision.
 */
@Service
public class TicketTriage {

    static final PropositionQuestionSpec URGENT = Questions.named("urgent")
            .proposition("Does the customer need help today?")
            .build();

    static final ChoiceQuestionSpec DEPARTMENT = Questions.named("department")
            .choice("Which team should handle the ticket?")
            .option("billing", "Payments, invoices and refunds")
            .option("technical", "Errors, outages and integrations")
            .option("account", "Plans, contacts and access")
            .build();

    static final RatingQuestionSpec FRUSTRATION = Questions.named("frustration")
            .rating("How frustrated does the customer sound?")
            .level("calm", "Neutral or friendly")
            .level("annoyed", "Some impatience")
            .level("angry", "Upset or threatening to leave")
            .build();

    static final DecisionSpec TRIAGE = DecisionSpec.of(URGENT, DEPARTMENT, FRUSTRATION);

    /** The decision role the platform maps to a decision service. */
    static final String ROLE = "support-triage";

    private final Ai ai;
    private final CustomerMemory memory;

    /**
     * @param ai     picks the decision service by role
     * @param memory supplies what is known about the ticket's customer
     */
    TicketTriage(Ai ai, CustomerMemory memory) {
        this.ai = ai;
        this.memory = memory;
    }

    /**
     * Triages one ticket.
     *
     * @param ticket the ticket to triage
     * @return the answers, with {@code needsHuman} set when any of them is missing
     */
    public Triage triage(Ticket ticket) {
        String input = inputFor(ticket);
        DecisionResponse response = ai.decisions().byRole(ROLE).ask(input, TRIAGE);

        Boolean urgent = response.answer(URGENT) instanceof PropositionResult.Answered answered
                ? answered.getAnswer()
                : null;
        String department = response.answer(DEPARTMENT) instanceof ClassificationResult.Selected selected
                ? selected.getCategoryId()
                : null;
        String frustration = levelOf(response.answer(FRUSTRATION));

        boolean needsHuman = urgent == null || department == null || frustration == null;
        return new Triage(urgent, department, frustration, needsHuman, input);
    }

    private String inputFor(Ticket ticket) {
        var input = new StringBuilder(ticket.text())
                .append("\n\nWhat we know about this customer:");
        for (Proposition fact : memory.currentFacts(ticket.account())) {
            input.append("\n- ").append(fact.getText());
        }
        return input.toString();
    }

    // A rating can be answered with only a score and no level, which still leaves us without one.
    private static @Nullable String levelOf(RatingResult result) {
        return result instanceof RatingResult.Answered answered ? answered.getSelectedLevelId() : null;
    }
}
