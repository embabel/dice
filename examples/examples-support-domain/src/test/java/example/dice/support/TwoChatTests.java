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
package example.dice.support;

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.common.Relations;
import com.embabel.dice.projection.graph.ProjectedRelationship;
import com.embabel.dice.projection.graph.RelationBasedGraphProjector;
import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.provenance.ProvenanceEntry;
import example.dice.common.ScriptedChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Two support chats about Acme go through the real pipeline, with the model's replies recorded.
 * In the first a customer says Acme is on the Pro plan. In the second Dana, Acme's admin, says
 * Acme moved back to Basic. The tests print what was stored and check what the article says
 * about it.
 */
@SpringBootTest
@Import(ScriptedModel.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class TwoChatTests {

    private static final Logger logger = LoggerFactory.getLogger(TwoChatTests.class);

    static final String CHAT_1 = "chat-1";
    static final String CHAT_2 = "chat-2";

    static final String PRO = "Acme is on the Pro plan";
    static final String DANA = "Dana is the admin at Acme";
    static final String BASIC = "Acme moved back to the Basic plan";

    @Autowired SupportDesk desk;
    @Autowired InMemoryPropositionRepository propositions;
    @Autowired ScriptedChatModel model;
    @Autowired @Qualifier("supportDictionary") DataDictionary dictionary;

    @BeforeAll
    void runBothChats() {

        desk.chat(CHAT_1, """
                Hello, I look after the account for Acme. Acme is on the Pro plan, and we would \
                like ten more seats.""");

        desk.chat(CHAT_2, """
                Hi, this is Dana, the admin at Acme. Acme moved back to the Basic plan at the \
                start of the month.""");
    }

    @Test
    void printsEveryStoredFactAndItsConfidenceFadesOverThirtyDays() {

        var now = Instant.now();
        var later = now.plus(Duration.ofDays(30));

        var stored = facts();
        stored.forEach(fact -> print(fact, now, later));

        assertThat(stored).hasSize(3);
        assertThat(stored).allSatisfy(fact ->
                assertThat(fact.effectiveConfidenceAt(later)).isLessThan(fact.effectiveConfidenceAt(now)));
    }

    @Test
    void theFirstChatTagsTheCustomerAndThePlan() {

        assertThat(mentionTypes(CHAT_1)).containsExactlyInAnyOrder("Customer", "Plan");
    }

    @Test
    void theSecondChatTagsTheContactAndThePlan() {

        // Acme is named here too, as the customer Dana is admin for and the one changing plan.
        assertThat(mentionTypes(CHAT_2)).contains("Contact", "Plan");
    }

    @Test
    void acmeIsTheSameEntityInBothChats() {

        var inChat1 = mention(PRO, "Acme").getResolvedId();

        assertThat(inChat1).isNotNull();
        assertThat(mention(DANA, "Acme").getResolvedId()).isEqualTo(inChat1);
        assertThat(mention(BASIC, "Acme").getResolvedId()).isEqualTo(inChat1);
    }

    @Test
    void everyFactNamesItsChatInProvenanceAndGrounding() {

        assertSourcedFrom(fact(PRO), CHAT_1);
        assertSourcedFrom(fact(DANA), CHAT_2);
        assertSourcedFrom(fact(BASIC), CHAT_2);
    }

    @Test
    void projectsTheChatsToEdgesThatCiteTheirStoredFacts() {
        var relations = Relations.empty()
                .withSemanticBetween("Customer", "Plan", "is on", "has a subscription")
                .withSemanticBetween("Contact", "Customer", "is the admin at", "administers an account")
                .withSemanticBetween("Customer", "Plan", "moved back to", "changed its subscription");
        var graph = RelationBasedGraphProjector.from(relations).projectAll(facts(), dictionary);
        var acme = mention(PRO, "Acme").getResolvedId();

        assertThat(graph.getProjected())
                .extracting(ProjectedRelationship::getSourceId, ProjectedRelationship::getType,
                        ProjectedRelationship::getTargetId, ProjectedRelationship::getConfidence,
                        ProjectedRelationship::getSourcePropositionIds)
                .containsExactlyInAnyOrder(
                        tuple(acme, "IS_ON", mention(PRO, "Pro plan").getResolvedId(), 0.95, List.of(fact(PRO).getId())),
                        tuple(mention(DANA, "Dana").getResolvedId(), "IS_THE_ADMIN_AT", acme, 0.9,
                                List.of(fact(DANA).getId())),
                        tuple(acme, "MOVED_BACK_TO", mention(BASIC, "Basic plan").getResolvedId(), 0.9,
                                List.of(fact(BASIC).getId())));

        for (var edge : graph.getProjected()) {
            String sourceId = edge.getSourcePropositionIds().getFirst();
            var source = propositions.findById(sourceId);
            assertThat(source).isNotNull();
            assertSourcedFrom(source, edge.getType().equals("IS_ON") ? CHAT_1 : CHAT_2);
        }
    }

    @Test
    void keepsTheScoresTheModelGaveEachFact() {

        assertScores(fact(PRO), 0.95, 0.8, 0.005);
        assertScores(fact(DANA), 0.9, 0.6, 0.01);
        assertScores(fact(BASIC), 0.9, 0.8, 0.005);
    }

    @Test
    void storesTheProAndTheBasicClaimsSideBySide() {

        // This pipeline has no reviser, so the later Basic claim does not replace the Pro one.
        // Both stay current. The corrections example adds a reviser and shows revision.
        assertThat(facts()).extracting(Proposition::getText).containsExactlyInAnyOrder(PRO, DANA, BASIC);
        assertThat(fact(PRO).getStatus()).isEqualTo(fact(BASIC).getStatus());
    }

    @Test
    void sendsEachChatToTheScriptedModelOnce() {

        // Every model call went to the scripted model, one per chat.
        assertThat(model.promptsSeen()).hasSize(2);
        assertThat(model.promptsSeen().get(0)).contains("ten more seats");
        assertThat(model.promptsSeen().get(1)).contains("this is Dana");
    }

    /**
     * Everything stored under the Acme account.
     *
     * @return the stored facts
     */
    private List<Proposition> facts() {
        return propositions.findByContextIdValue(SupportDesk.ACCOUNT);
    }

    /**
     * Finds one stored fact by its text.
     *
     * @param text the fact's exact text
     * @return the fact
     */
    private Proposition fact(String text) {
        return facts().stream()
                .filter(fact -> fact.getText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Finds one mention on a stored fact.
     *
     * @param text the fact's exact text
     * @param span the mention's exact text
     * @return the mention
     */
    private EntityMention mention(String text, String span) {
        return fact(text).getMentions().stream()
                .filter(mention -> mention.getSpan().equals(span))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The mention types on the facts read from one chat.
     *
     * @param chatId the chat
     * @return each type once
     */
    private List<String> mentionTypes(String chatId) {
        return facts().stream()
                .filter(fact -> fact.getGrounding().contains(chatId))
                .flatMap(fact -> fact.getMentions().stream())
                .map(EntityMention::getType)
                .distinct()
                .toList();
    }

    /**
     * Checks that a fact came from one chat, and only that chat.
     *
     * @param fact   the fact
     * @param chatId the chat it should name
     */
    private void assertSourcedFrom(Proposition fact, String chatId) {
        assertThat(fact.getProvenanceEntries())
                .extracting(ProvenanceEntry::getLocator)
                .containsOnly(new ConnectorRef("chat", chatId));
        assertThat(fact.getGrounding()).containsExactly(chatId);
    }

    /**
     * Checks a fact kept the scores in its recorded reply.
     */
    private void assertScores(Proposition fact, double confidence, double importance, double decay) {
        assertThat(fact.getConfidence()).isEqualTo(confidence);
        assertThat(fact.getImportance()).isEqualTo(importance);
        assertThat(fact.getDecay()).isEqualTo(decay);
    }

    /**
     * Logs one fact with its scores and how confident DICE is in it now and a month from now.
     */
    private void print(Proposition fact, Instant now, Instant later) {
        var types = fact.getMentions().stream()
                .map(mention -> mention.getSpan() + ":" + mention.getType())
                .collect(Collectors.joining(", "));
        logger.info("\"{}\" [{}] confidence={} importance={} decay={} effective now={} in 30 days={}",
                fact.getText(), types, fact.getConfidence(), fact.getImportance(), fact.getDecay(),
                String.format("%.3f", fact.effectiveConfidenceAt(now)),
                String.format("%.3f", fact.effectiveConfidenceAt(later)));
    }
}
