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
package example.dice.backends;

import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The assertions every backend has to pass. Each subclass starts the application on one store
 * and checks that the store it got is the one it asked for; everything else is in here, so the
 * same {@link MemoryDesk} calls are checked the same way on every backend.
 *
 * <p>Each test starts from an empty context and remembers three facts about Acme and one about
 * Globex.
 */
@Import(ScriptedModel.class)
abstract class SameCodeEveryBackend {

    @Autowired
    MemoryDesk desk;

    @Autowired
    PropositionRepository repository;

    Proposition shipsWidgets;
    Proposition paysLate;
    Proposition openedOffice;
    Proposition globexMerger;

    @BeforeEach
    void rememberFacts() {
        repository.clearByContext(MemoryDesk.CONTEXT);

        shipsWidgets = desk.remember("Acme ships widgets to Globex every month.", "acme", "Acme", "Company", 0.9);
        paysLate = desk.remember("Acme pays its invoices late.", "acme", "Acme", "Company", 0.6);
        openedOffice = desk.remember("Acme opened an office in Lisbon.", "acme", "Acme", "Company", 0.8);
        globexMerger = desk.remember("Globex is merging with Initech.", "globex", "Globex", "Company", 0.7);
    }

    @Test
    void aboutReturnsExactlyTheFactsAboutThatEntity() {

        assertThat(desk.about("acme"))
                .extracting(Proposition::getId)
                .containsExactlyInAnyOrder(shipsWidgets.getId(), paysLate.getId(), openedOffice.getId());
        assertThat(desk.about("globex"))
                .extracting(Proposition::getId)
                .containsExactly(globexMerger.getId());
    }

    @Test
    void getReturnsTheFactAsItWasRemembered() {

        var fact = desk.get(paysLate.getId());

        assertThat(fact).isNotNull();
        assertThat(fact.getText()).isEqualTo("Acme pays its invoices late.");
        assertThat(fact.getConfidence()).isEqualTo(0.6);
        assertThat(fact.getStatus()).isEqualTo(PropositionStatus.ACTIVE);
        assertThat(fact.getMentions())
                .extracting(EntityMention::getSpan, EntityMention::getType, EntityMention::getResolvedId, EntityMention::getRole)
                .containsExactly(tuple("Acme", "Company", "acme", MentionRole.SUBJECT));
    }

    @Test
    void aCorrectedFactLeavesAboutButStaysStored() {

        desk.correct(paysLate.getId());

        assertThat(desk.about("acme"))
                .extracting(Proposition::getId)
                .containsExactlyInAnyOrder(shipsWidgets.getId(), openedOffice.getId());
        var corrected = desk.get(paysLate.getId());
        assertThat(corrected).isNotNull();
        assertThat(corrected.getStatus()).isEqualTo(PropositionStatus.CONTRADICTED);
        assertThat(corrected.getText()).isEqualTo("Acme pays its invoices late.");
    }
}
