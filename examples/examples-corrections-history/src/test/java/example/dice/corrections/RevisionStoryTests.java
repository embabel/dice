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
package example.dice.corrections;

import com.embabel.dice.evidence.Evidence;
import com.embabel.dice.evidence.EvidenceConverter;
import com.embabel.dice.evidence.EvidenceItem;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.revision.RevisionResult;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.spi.ConflictType;
import example.dice.common.ScriptedChatModel;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tells the desk seven things about Acme, one message at a time, and checks what revision
 * decided for each.
 *
 * <p>The tests run in order and share one desk, because each turn is revised against what the
 * earlier turns stored. Each message has its own id, so a repeat counts as new evidence rather
 * than the same message arriving twice.
 */
@SpringBootTest
@Import(ScriptedModel.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class RevisionStoryTests {

    private static final Logger logger = LoggerFactory.getLogger(RevisionStoryTests.class);

    static final String PRO = "Acme is on the Pro plan";
    static final String PAYS_FOR_PRO = "Acme pays for the Pro plan";
    static final String PAYING = "Acme is a paying customer";
    static final String ANNUAL = "Acme's invoices are paid annually";
    static final String MONTHLY = "Acme's invoices are paid monthly";
    static final String BASIC = "Acme moved to the Basic plan";

    @Autowired CorrectionsDesk desk;
    @Autowired InMemoryPropositionRepository facts;
    @Autowired ScriptedChatModel model;

    @Test
    @Order(1)
    void storesTheFirstClaimAsNew() {

        var result = tell(1, SourceAuthority.CHAT, "chat-1", PRO);

        assertThat(result).isInstanceOfSatisfying(RevisionResult.New.class,
                created -> assertThat(created.getProposition().getText()).isEqualTo(PRO));
    }

    @Test
    @Order(2)
    void mergesAWordForWordRepeatWithoutAskingTheModel() {

        var modelCallsBefore = model.promptsSeen().size();

        var result = tell(2, SourceAuthority.CHAT, "chat-2", PRO);

        assertThat(result).isInstanceOfSatisfying(RevisionResult.Merged.class, merged -> {
            assertThat(merged.getOriginal().getText()).isEqualTo(PRO);
            assertThat(merged.getRevised().getGrounding()).contains("chat-1", "chat-2");
            assertThat(merged.isReplay()).isFalse();
        });
        // One call, to extract the fact. The stored copy matched on its text, so revision
        // never needed the model.
        assertThat(model.promptsSeen()).hasSize(modelCallsBefore + 1);
    }

    @Test
    @Order(3)
    void reinforcesTheStoredClaimWhenBillingBacksItUp() {

        var result = tell(3, SourceAuthority.BILLING, "invoice-3", PAYS_FOR_PRO);

        assertThat(result).isInstanceOfSatisfying(RevisionResult.Reinforced.class, reinforced -> {
            assertThat(reinforced.getOriginal().getText()).isEqualTo(PRO);
            // Reinforcement keeps the stored wording. The billing sentence is never stored.
            assertThat(reinforced.getRevised().getText()).isEqualTo(PRO);
            assertThat(reinforced.getRevised().getGrounding()).contains("invoice-3");
        });
    }

    @Test
    @Order(4)
    void storesABroaderClaimAsAGeneralizationOfTheStoredOne() {

        var result = tell(4, SourceAuthority.BILLING, "invoice-4", PAYING);

        assertThat(result).isInstanceOfSatisfying(RevisionResult.Generalized.class, generalized -> {
            assertThat(generalized.getProposition().getText()).isEqualTo(PAYING);
            assertThat(generalized.getGeneralizes()).extracting(Proposition::getText).containsExactly(PRO);
        });
    }

    @Test
    @Order(5)
    void storesAClaimOnAnotherTopicAsNew() {

        var result = tell(5, SourceAuthority.CHAT, "chat-5", ANNUAL);

        assertThat(result).isInstanceOfSatisfying(RevisionResult.New.class,
                created -> assertThat(created.getProposition().getText()).isEqualTo(ANNUAL));
    }

    @Test
    @Order(6)
    void callsAConflictOnAFactThatDoesNotChangeAContradiction() {

        var result = tell(6, SourceAuthority.CHAT, "chat-6", MONTHLY);

        assertThat(result).isInstanceOfSatisfying(RevisionResult.Contradicted.class, contradicted -> {
            assertThat(contradicted.getOriginal().getText()).isEqualTo(ANNUAL);
            assertThat(contradicted.getOriginal().getStatus()).isEqualTo(PropositionStatus.CONTRADICTED);
            assertThat(contradicted.getNew().getText()).isEqualTo(MONTHLY);
            // Neither invoice fact mentions a plan, so neither carries the status predicate.
            assertThat(contradicted.getConflictType() instanceof ConflictType.Contradiction).isTrue();
            assertThat(EvidenceConverter.create().fromContradiction(contradicted))
                    .isInstanceOfSatisfying(Evidence.Conflicting.class, conflict ->
                            assertThat(conflict.getItems()).extracting(EvidenceItem::getText)
                                    .containsExactly(ANNUAL, MONTHLY));
        });
    }

    @Test
    @Order(7)
    void callsANewerConflictOnTheCustomersPlanTheWorldMovingOn() {

        var result = tell(7, SourceAuthority.BILLING, "invoice-7", BASIC);

        assertThat(result).isInstanceOfSatisfying(RevisionResult.Contradicted.class, contradicted -> {
            assertThat(contradicted.getOriginal().getText()).isEqualTo(PRO);
            assertThat(contradicted.getOriginal().getStatus()).isEqualTo(PropositionStatus.CONTRADICTED);
            assertThat(contradicted.getNew().getText()).isEqualTo(BASIC);
            // Both plan facts carry the status predicate, and the Basic plan claim is newer.
            assertThat(contradicted.getConflictType() instanceof ConflictType.WorldProgression).isTrue();
            assertThat(EvidenceConverter.create().fromContradiction(contradicted))
                    .isInstanceOfSatisfying(Evidence.Superseded.class, evidence -> {
                        assertThat(evidence.getCurrent().getText()).isEqualTo(BASIC);
                        assertThat(evidence.getPrevious()).singleElement().satisfies(previous -> {
                            assertThat(previous.getText()).isEqualTo(PRO);
                            assertThat(previous.getStatus()).isEqualTo(PropositionStatus.SUPERSEDED);
                        });
                    });
        });
    }

    @Test
    @Order(8)
    void keepsTheCorrectedClaimsMarkedContradictedRatherThanDeletingThem() {

        Map<String, PropositionStatus> statusOfEachFact = facts.findAll().stream()
                .collect(Collectors.toMap(Proposition::getText, Proposition::getStatus));

        logger.info("Stored after turn 7: {}", statusOfEachFact);

        assertThat(statusOfEachFact).containsOnly(
                Map.entry(PRO, PropositionStatus.CONTRADICTED),
                Map.entry(PAYING, PropositionStatus.ACTIVE),
                Map.entry(ANNUAL, PropositionStatus.CONTRADICTED),
                Map.entry(MONTHLY, PropositionStatus.ACTIVE),
                Map.entry(BASIC, PropositionStatus.ACTIVE));
    }

    /**
     * Tells the desk one sentence and prints what revision decided.
     *
     * @param turn      the turn's number in the story
     * @param source    where the message came from
     * @param messageId the message's id in that source
     * @param sentence  what the message says
     * @return the one revision result the sentence gave
     */
    private RevisionResult tell(int turn, String source, String messageId, String sentence) {
        var results = desk.tell(source, messageId, sentence);
        assertThat(results).hasSize(1);
        var result = results.get(0);
        logger.info("Turn {} from {}: \"{}\" -> {}", turn, source, sentence, describe(result));
        return result;
    }

    /**
     * @param result what revision decided for one fact
     * @return the decision in a line, naming the stored fact it touched
     */
    private static String describe(RevisionResult result) {
        if (result instanceof RevisionResult.New) {
            return "New";
        }
        if (result instanceof RevisionResult.Merged merged) {
            return "Merged into \"" + merged.getOriginal().getText() + "\"";
        }
        if (result instanceof RevisionResult.Reinforced reinforced) {
            return "Reinforced \"" + reinforced.getOriginal().getText() + "\"";
        }
        if (result instanceof RevisionResult.Generalized generalized) {
            return "Generalized " + generalized.getGeneralizes().stream()
                    .map(fact -> "\"" + fact.getText() + "\"").toList();
        }
        if (result instanceof RevisionResult.Contradicted contradicted) {
            return "Contradicted \"" + contradicted.getOriginal().getText() + "\" ("
                    + contradicted.getConflictType().getClass().getSimpleName() + ")";
        }
        return result.toString();
    }
}
