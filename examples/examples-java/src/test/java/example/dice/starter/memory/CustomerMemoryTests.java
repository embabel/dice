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
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import example.dice.starter.customers.AccountId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMemoryTests {

    Instant recorded = Instant.parse("2026-11-01T00:00:00Z");
    InMemoryPropositionRepository repository = new InMemoryPropositionRepository();
    CustomerMemory memory = new CustomerMemory(repository);

    @Test
    void leavesOutFactsThatHaveBeenSuperseded() {

        var current = Proposition.draft("acme", "Acme is on the Team plan", 0.95)
                .withDecay(0.005)
                .withCreated(recorded)
                .toProposition();
        var replaced = Proposition.draft("acme", "Acme is on the Starter plan", 0.9)
                .withCreated(recorded)
                .withStatus(PropositionStatus.SUPERSEDED)
                .toProposition();
        repository.saveAll(List.of(current, replaced));

        assertThat(memory.currentFacts(new AccountId("acme"))).containsExactly(current);

        // The replaced fact is still stored. Only the query leaves it out.
        assertThat(repository.findAll()).extracting(Proposition::getText).contains("Acme is on the Starter plan");
    }

    @Test
    void knowsNothingAboutAnAccountWithNoFacts() {

        repository.save(Proposition.draft("acme", "Acme is on the Team plan", 0.95).toProposition());

        assertThat(memory.currentFacts(new AccountId("globex"))).isEmpty();
    }
}
