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
package example.dice.mcp;

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;

/**
 * The memory the MCP tools read: an in-memory store with three facts about a customer called
 * Acme, all in the context {@code demo}.
 *
 * <p>The facts are written when the store is created, so they are there before any client
 * connects. Each has confidence 0.9 and does not decay, so the tools always show
 * {@code confidence=0.90}. Restarting the app starts over from these three.
 */
@Configuration(proxyBeanMethods = false)
class DemoMemory {

    /** The context a client passes as {@code contextId} to see the facts. */
    static final String CONTEXT = "demo";

    private static final double CONFIDENCE = 0.9;

    private static final double NO_DECAY = 0.0;

    /**
     * Creates the store and saves the three demo facts into it.
     *
     * @return the store DICE's MCP tools read from
     */
    @Bean
    InMemoryPropositionRepository demoPropositionRepository() {
        var repository = new InMemoryPropositionRepository();
        var now = Instant.now();
        repository.save(fact("demo-1", "Acme is on the Pro plan", now));
        repository.save(fact("demo-2", "Dana is Acme's admin", now));
        repository.save(fact("demo-3", "Acme prefers email over phone", now));
        return repository;
    }

    private static Proposition fact(String id, String text, Instant now) {
        return Proposition.create(
                id,
                CONTEXT,
                text,
                List.of(),
                CONFIDENCE,
                NO_DECAY,
                null,
                List.of(),
                now,
                now,
                PropositionStatus.ACTIVE);
    }
}
