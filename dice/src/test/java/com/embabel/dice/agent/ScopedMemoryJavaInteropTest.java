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
package com.embabel.dice.agent;

import com.embabel.agent.api.tool.Tool;
import com.embabel.dice.proposition.ContextReadScope;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.ScopedPropositionReader;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds scoped memories the way a Java caller would, over a store holding rows in
 * contexts A, B and C, and checks that only A and B ever come back.
 */
class ScopedMemoryJavaInteropTest {

    private final ContextReadScope scope = ContextReadScope.ofValues(List.of("A", "B"));
    private InMemoryPropositionRepository store;

    @BeforeEach
    void setUp() {
        store = new InMemoryPropositionRepository();
        store.save(row("a1", "A"));
        store.save(row("b1", "B"));
        store.save(row("c1", "C"));
    }

    @Test
    void configuringChainContributesOneEagerRowAndNeverAnOutOfScopeContext() {
        ScopedMemory.Configuring chain = ScopedMemory.configuring(ScopedPropositionReader.open(store, scope))
                .withTopic("topic")
                .withNarrowedBy(q -> q.withMinLevel(0))
                .withEagerQuery(q -> q.withLimit(1));
        ScopedMemory memory = chain.memory();

        String contribution = memory.contribution();

        assertEquals(1, rowLines(contribution).size(), contribution);
        assertFalse(contribution.contains("[C/"), contribution);
        assertTrue(contribution.contains("2 memories available"), contribution);
        assertEquals("Memories about topic", memory.getDescription());
        assertNotSame(memory, chain.memory());
    }

    @Test
    void forContextsListsOnlyTheAuthorizedContexts() {
        ScopedMemory memory = Memory.forContexts(ContextReadScope.ofValues(List.of("A", "B")), store);

        Tool.Result result = memory.call("{\"limit\":5}");

        Tool.Result.Text text = assertInstanceOf(Tool.Result.Text.class, result);
        assertEquals(List.of("- [A/a1] fact a1", "- [B/b1] fact b1"),
                rowLines(text.getContent()).stream().sorted().toList());
    }

    private static List<String> rowLines(String text) {
        return text.lines().filter(line -> line.startsWith("- [")).toList();
    }

    private static Proposition row(String id, String context) {
        Instant now = Instant.now();
        return Proposition.create(
                id,
                context,
                "fact " + id,
                List.of(),
                0.9,
                0.0,
                0.5,
                null,
                List.of(),
                now,
                now,
                now,
                PropositionStatus.ACTIVE
        );
    }
}
