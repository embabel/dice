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
package example.dice.decisions;

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.revision.RevisionResult;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import example.dice.common.ScriptedChatModel;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tells the desk six messages about Acme, one fact each, and checks what the decision did to
 * the stored facts after each one. Each test is one message, in order, so each starts from what
 * the earlier ones stored.
 *
 * <p>The model's replies are recorded. Every Team and Enterprise plan fact is extracted with
 * confidence 0.6, so the confidence of the first fact moves by exact amounts.
 */
@SpringBootTest
@Import(ScriptedModel.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class DecisionRevisionTests {

    static final String TEAM = "Acme is on the Team plan";
    static final String USES_TEAM = "Acme uses the Team plan";
    static final String RENEWED = "Acme renewed the Team plan for a year";
    static final String ENTERPRISE = "Acme moved to the Enterprise plan";
    static final String EMAIL = "Acme prefers email over phone";
    static final String SEATS = "Acme has 40 seats on the Enterprise plan";

    /** What every revision decision's user message starts with. */
    static final String ANY_DECISION = "{\"input\":\"New proposition:";

    static final double EXACT = 1e-9;

    @Autowired RevisionDesk desk;
    @Autowired InMemoryPropositionRepository facts;
    @Autowired ScriptedChatModel model;

    /** The prompts the last message sent to the model. */
    private List<String> prompts = List.of();

    private String teamId = "";
    private String enterpriseId = "";
    private String emailId = "";

    @Test
    @Order(1)
    void aFirstFactAboutAcmeHasNothingToCompareWithSoItIsNewAndNoDecisionIsAsked() {

        var results = tell("message-1", TEAM);

        assertThat(results).singleElement().isInstanceOf(RevisionResult.New.class);
        teamId = ((RevisionResult.New) results.get(0)).getProposition().getId();

        var team = stored(teamId);
        assertThat(team.getText()).isEqualTo(TEAM);
        assertThat(team.getStatus()).isEqualTo(PropositionStatus.ACTIVE);
        assertThat(team.getConfidence()).isCloseTo(0.6, within(EXACT));
        assertThat(team.getReinforceCount()).isZero();

        // Only the extraction prompt.
        assertThat(prompts).hasSize(1);
        assertThat(decisionPrompts()).isEmpty();
    }

    @Test
    @Order(2)
    void sayingTheSamePlanAgainMergesIntoTheFirstFactAndRaisesItsConfidence() {

        var results = tell("message-2", USES_TEAM);

        assertThat(results).singleElement().isInstanceOf(RevisionResult.Merged.class);
        var merged = (RevisionResult.Merged) results.get(0);
        assertThat(merged.getOriginal().getId()).isEqualTo(teamId);
        assertThat(merged.getRevised().getId()).isEqualTo(teamId);

        var team = stored(teamId);
        assertThat(team.getText()).isEqualTo(TEAM);
        assertThat(team.getStatus()).isEqualTo(PropositionStatus.ACTIVE);
        assertThat(team.getConfidence()).isCloseTo(0.78, within(EXACT));
        assertThat(team.getDecay()).isCloseTo(0.07, within(EXACT));
        assertThat(team.getReinforceCount()).isEqualTo(1);
        assertThat(team.getGrounding()).containsExactlyInAnyOrder("message-1", "message-2");

        // The duplicate is folded in, not stored on its own.
        assertThat(facts.findAll()).extracting(Proposition::getId).containsExactly(teamId);

        assertThat(decisionPrompts()).singleElement().asString()
                .contains(ScriptedModel.decisionAbout(USES_TEAM))
                .contains("\\nCandidate 1: " + TEAM)
                .doesNotContain("Candidate 2:");
    }

    @Test
    @Order(3)
    void aRenewalOfThePlanReinforcesTheFirstFact() {

        var results = tell("message-3", RENEWED);

        assertThat(results).singleElement().isInstanceOf(RevisionResult.Reinforced.class);
        var reinforced = (RevisionResult.Reinforced) results.get(0);
        assertThat(reinforced.getOriginal().getId()).isEqualTo(teamId);

        var team = stored(teamId);
        assertThat(team.getText()).isEqualTo(TEAM);
        assertThat(team.getStatus()).isEqualTo(PropositionStatus.ACTIVE);
        assertThat(team.getConfidence()).isCloseTo(0.84, within(EXACT));
        assertThat(team.getDecay()).isCloseTo(0.07 * 0.85, within(EXACT));
        assertThat(team.getReinforceCount()).isEqualTo(2);
        assertThat(facts.findAll()).extracting(Proposition::getId).containsExactly(teamId);

        assertThat(decisionPrompts()).singleElement().asString()
                .contains(ScriptedModel.decisionAbout(RENEWED))
                .contains("\\nCandidate 1: " + TEAM)
                .doesNotContain("Candidate 2:");
    }

    @Test
    @Order(4)
    void movingToAnotherPlanContradictsTheFirstFactAndStoresTheNewOne() {

        var results = tell("message-4", ENTERPRISE);

        assertThat(results).singleElement().isInstanceOf(RevisionResult.Contradicted.class);
        var contradicted = (RevisionResult.Contradicted) results.get(0);
        assertThat(contradicted.getOriginal().getId()).isEqualTo(teamId);
        enterpriseId = contradicted.getNew().getId();

        var team = stored(teamId);
        assertThat(team.getStatus()).isEqualTo(PropositionStatus.CONTRADICTED);
        assertThat(team.getConfidence()).isCloseTo(0.252, within(EXACT));
        assertThat(team.getDecay()).isCloseTo(0.07 * 0.85 + 0.15, within(EXACT));

        var enterprise = stored(enterpriseId);
        assertThat(enterprise.getText()).isEqualTo(ENTERPRISE);
        assertThat(enterprise.getStatus()).isEqualTo(PropositionStatus.ACTIVE);
        assertThat(enterprise.getConfidence()).isCloseTo(0.6, within(EXACT));

        assertThat(decisionPrompts()).singleElement().asString()
                .contains(ScriptedModel.decisionAbout(ENTERPRISE))
                .contains("\\nCandidate 1: " + TEAM)
                .doesNotContain("Candidate 2:");
    }

    @Test
    @Order(5)
    void aPreferenceIsUnrelatedToThePlanSoItIsNewAndTheContradictedFactIsNotAskedAbout() {

        var results = tell("message-5", EMAIL);

        assertThat(results).singleElement().isInstanceOf(RevisionResult.New.class);
        emailId = ((RevisionResult.New) results.get(0)).getProposition().getId();

        assertThat(stored(emailId).getText()).isEqualTo(EMAIL);
        assertThat(stored(emailId).getStatus()).isEqualTo(PropositionStatus.ACTIVE);
        assertThat(stored(enterpriseId).getStatus()).isEqualTo(PropositionStatus.ACTIVE);
        assertThat(stored(enterpriseId).getConfidence()).isCloseTo(0.6, within(EXACT));

        // Only active facts are candidates, so the contradicted Team plan fact is left out.
        assertThat(decisionPrompts()).singleElement().asString()
                .contains(ScriptedModel.decisionAbout(EMAIL))
                .contains("\\nCandidate 1: " + ENTERPRISE)
                .doesNotContain(TEAM)
                .doesNotContain("Candidate 2:");
    }

    @Test
    @Order(6)
    void aDecisionReplyThatIsNotJsonStoresTheFactAsNewAndChangesNothingStored(CapturedOutput output) {

        var enterpriseBefore = stored(enterpriseId);
        var emailBefore = stored(emailId);
        var teamBefore = stored(teamId);

        var results = tell("message-6", SEATS);

        assertThat(results).singleElement().isInstanceOf(RevisionResult.New.class);
        var seats = ((RevisionResult.New) results.get(0)).getProposition();
        assertThat(stored(seats.getId()).getText()).isEqualTo(SEATS);

        assertThat(stored(enterpriseId)).isEqualTo(enterpriseBefore);
        assertThat(stored(emailId)).isEqualTo(emailBefore);
        assertThat(stored(teamId)).isEqualTo(teamBefore);
        assertThat(facts.findAll()).hasSize(4);

        // Both active facts about Acme were asked about in the one decision. Which is candidate
        // 1 depends on their random ids, so only their presence is checked.
        assertThat(decisionPrompts()).singleElement().asString()
                .contains(ScriptedModel.decisionAbout(SEATS))
                .contains("\\nCandidate 1: ")
                .contains("\\nCandidate 2: ")
                .contains(ENTERPRISE)
                .contains(EMAIL)
                .doesNotContain(TEAM);

        assertThat(output.getOut() + output.getErr())
                .contains("WARN")
                .contains(seats.getId())
                .contains(SEATS)
                .contains("Storing it as new");
    }

    /**
     * Tells the desk one message and keeps the prompts it sent to the model.
     */
    private List<RevisionResult> tell(String messageId, String text) {
        int before = model.promptsSeen().size();
        var results = desk.tell(messageId, text);
        var seen = model.promptsSeen();
        prompts = seen.subList(before, seen.size());
        return results;
    }

    /**
     * The prompts of the last message that asked the revision decision.
     */
    private List<String> decisionPrompts() {
        return prompts.stream().filter(prompt -> prompt.contains(ANY_DECISION)).toList();
    }

    private Proposition stored(String id) {
        return Objects.requireNonNull(facts.findById(id), "No stored fact with id " + id);
    }
}
