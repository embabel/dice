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

import com.embabel.dice.common.DiceMetadataKeys;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.spi.AuthorityTier;
import com.embabel.dice.spi.AuthorityWeightedTrustScorer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static example.dice.corrections.RevisionStoryTests.ANNUAL;
import static example.dice.corrections.RevisionStoryTests.BASIC;
import static example.dice.corrections.RevisionStoryTests.MONTHLY;
import static example.dice.corrections.RevisionStoryTests.PAYING;
import static example.dice.corrections.RevisionStoryTests.PAYS_FOR_PRO;
import static example.dice.corrections.RevisionStoryTests.PRO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tells the desk the same seven things about Acme, then reads the fact store the way an app
 * would: what default recall sees, what a history query sees, and how far each fact is trusted.
 *
 * <p>The story is told once, before any test runs, and the tests only read. The fact store is
 * a singleton bean, so the class asks for a fresh context rather than sharing one that
 * already holds the story.
 */
@SpringBootTest
@Import(ScriptedModel.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class HistoryAndTrustTests {

    @Autowired CorrectionsDesk desk;
    @Autowired InMemoryPropositionRepository facts;

    @BeforeAll
    void tellTheStory() {
        desk.tell(SourceAuthority.CHAT, "chat-1", PRO);
        desk.tell(SourceAuthority.CHAT, "chat-2", PRO);
        desk.tell(SourceAuthority.BILLING, "invoice-3", PAYS_FOR_PRO);
        desk.tell(SourceAuthority.BILLING, "invoice-4", PAYING);
        desk.tell(SourceAuthority.CHAT, "chat-5", ANNUAL);
        desk.tell(SourceAuthority.CHAT, "chat-6", MONTHLY);
        desk.tell(SourceAuthority.BILLING, "invoice-7", BASIC);
    }

    @Test
    void defaultRecallLeavesOutTheContradictedClaims() {

        // The status filter DICE's Memory puts on every recall, so corrected facts never
        // reach the model's context.
        var recalled = facts.query(PropositionQuery.againstContext(CorrectionsDesk.ACCOUNT)
                .withStatuses(Set.of(PropositionStatus.ACTIVE)));

        assertThat(recalled).extracting(Proposition::getText)
                .containsExactlyInAnyOrder(MONTHLY, BASIC, PAYING);
    }

    @Test
    void aHistoryQueryAlsoReturnsTheContradictedClaims() {

        // No status filter, so every status comes back.
        Map<String, PropositionStatus> history = facts.query(PropositionQuery.againstContext(CorrectionsDesk.ACCOUNT))
                .stream()
                .collect(Collectors.toMap(Proposition::getText, Proposition::getStatus));

        assertThat(history).containsOnly(
                Map.entry(MONTHLY, PropositionStatus.ACTIVE),
                Map.entry(BASIC, PropositionStatus.ACTIVE),
                Map.entry(PAYING, PropositionStatus.ACTIVE),
                Map.entry(PRO, PropositionStatus.CONTRADICTED),
                Map.entry(ANNUAL, PropositionStatus.CONTRADICTED));
    }

    @Test
    void aFactOnlyChatSaidIsTrustedAsChat() {

        var annual = stored(ANNUAL);

        assertThat(sourcesOf(annual)).containsExactly(SourceAuthority.CHAT);
        assertThat(trustOf(annual)).isEqualTo(weightOf(AuthorityTier.DERIVED)).isEqualTo(0.6);
    }

    @Test
    void aFactOnlyBillingSaidIsTrustedAsBilling() {

        var paying = stored(PAYING);

        assertThat(sourcesOf(paying)).containsExactly(SourceAuthority.BILLING);
        assertThat(trustOf(paying)).isEqualTo(weightOf(AuthorityTier.PRIMARY)).isEqualTo(0.9);
    }

    @Test
    void aChatFactThatBillingBackedUpIsTrustedAsBilling() {

        var pro = stored(PRO);

        // First said in chat, twice, then reinforced by an invoice.
        assertThat(sourcesOf(pro)).contains(SourceAuthority.CHAT, SourceAuthority.BILLING);
        assertThat(trustOf(pro)).isEqualTo(weightOf(AuthorityTier.PRIMARY)).isEqualTo(0.9);
    }

    @Test
    void trustRanksTwoFactsAndTheAppPicksOne() {

        // Both claims started in chat and both were later contradicted, but billing backed up
        // the Pro claim, so it ranks higher.
        assertThat(moreTrusted(stored(ANNUAL), stored(PRO)).getText()).isEqualTo(PRO);
    }

    /**
     * Picks the fact with the higher trust score. DICE only scores facts. Deciding which one
     * to believe is the app's call, so this choice lives here and not in DICE.
     *
     * @param first  one fact
     * @param second another fact
     * @return whichever has the higher trust score, the first when they tie
     */
    private static Proposition moreTrusted(Proposition first, Proposition second) {
        return trustOf(second) > trustOf(first) ? second : first;
    }

    /**
     * @param text a fact's exact wording
     * @return the one stored fact with that wording, whatever its status
     */
    private Proposition stored(String text) {
        List<Proposition> matching = facts.query(PropositionQuery.againstContext(CorrectionsDesk.ACCOUNT)).stream()
                .filter(fact -> fact.getText().equals(text))
                .toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    /**
     * @param fact a stored fact
     * @return the trust score the reviser cached on it
     */
    private static double trustOf(Proposition fact) {
        var score = fact.getMetadata().get(DiceMetadataKeys.TRUST_SCORE);
        assertThat(score).as("trust score on \"%s\"", fact.getText()).isInstanceOf(Number.class);
        return ((Number) score).doubleValue();
    }

    /**
     * @param tier an authority tier
     * @return the score DICE's default authority weights give that tier
     */
    private static double weightOf(AuthorityTier tier) {
        return AuthorityWeightedTrustScorer.Companion.getDEFAULT_WEIGHTS().get(tier);
    }

    /**
     * @param fact a stored fact
     * @return the connector id of each source in its provenance, without repeats
     */
    private static Set<String> sourcesOf(Proposition fact) {
        return fact.getProvenanceEntries().stream()
                .map(entry -> entry.getLocator())
                .filter(ConnectorRef.class::isInstance)
                .map(locator -> ((ConnectorRef) locator).getConnectorId())
                .collect(Collectors.toSet());
    }
}
