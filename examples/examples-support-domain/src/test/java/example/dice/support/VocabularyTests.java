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

import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import example.dice.common.ScriptedChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why the schema matters. Chat 1 goes through DICE again, this time with a schema that names
 * no types. The model is shown no Customer, Contact or Plan, so the mentions it gives back
 * carry none of them, where the typed run in {@link TwoChatTests} tags Acme as a Customer and
 * the Pro plan as a Plan.
 *
 * <p>The untyped run has its own Spring context, because it swaps the support schema and the
 * scripted model for its own.
 */
@SpringBootTest
@Import(UntypedScriptedModel.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class VocabularyTests {

    @Autowired SupportDesk desk;
    @Autowired InMemoryPropositionRepository propositions;
    @Autowired ScriptedChatModel model;

    @BeforeAll
    void runChatOneWithoutTypes() {

        desk.chat(TwoChatTests.CHAT_1, """
                Hello, I look after the account for Acme. Acme is on the Pro plan, and we would \
                like ten more seats.""");
    }

    @Test
    void theModelIsShownNoSupportTypes() {

        assertThat(model.promptsSeen()).hasSize(1);
        assertThat(model.promptsSeen().get(0))
                .contains("ten more seats")
                .doesNotContain("- Customer: Customer")
                .doesNotContain("- Contact: Contact")
                .doesNotContain("- Plan: Plan");
    }

    @Test
    void theFactIsStillStored() {

        assertThat(facts()).extracting(Proposition::getText).containsExactly(TwoChatTests.PRO);
    }

    @Test
    void theMentionsCarryNoneOfTheSupportTypes() {

        var types = facts().stream()
                .flatMap(fact -> fact.getMentions().stream())
                .map(EntityMention::getType)
                .toList();

        assertThat(types).isNotEmpty().doesNotContain("Customer", "Contact", "Plan");
    }

    /**
     * Everything stored under the Acme account.
     *
     * @return the stored facts
     */
    private List<Proposition> facts() {
        return propositions.findByContextIdValue(SupportDesk.ACCOUNT);
    }
}
