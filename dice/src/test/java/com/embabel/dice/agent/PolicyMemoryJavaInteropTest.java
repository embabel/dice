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
import com.embabel.dice.proposition.ApprovedText;
import com.embabel.dice.proposition.ContextReadScope;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionKey;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.ScopedPropositionReader;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds policy memories the way a Java caller would. The store holds rows in contexts A, B
 * and C, the view reads A and B, and the policy grants a1 and b1 with approved text, so only
 * that approved text should ever come back.
 */
class PolicyMemoryJavaInteropTest {

    private InMemoryPropositionRepository store;
    private PolicyReadView view;

    @BeforeEach
    void setUp() {
        store = new InMemoryPropositionRepository();
        store.save(row("a1", "A"));
        store.save(row("b1", "B"));
        store.save(row("c1", "C"));
        RowPolicy policy = RowPolicy.of(
                new ReadPolicyStamp("principal", "purpose", "policy", "v1"),
                List.of(grant("a1", "A", "approved a1"), grant("b1", "B", "approved b1")));
        view = PolicyReadView.open(
                ScopedPropositionReader.open(store, ContextReadScope.ofValues(List.of("A", "B"))), policy);
    }

    @Test
    void configuringChainContributesOneApprovedEagerRow() {
        PolicyMemory memory = PolicyMemory.configuring(view)
                .withTopic("topic")
                .withSearchTiers(EnumSet.of(PolicySearchTier.KEYWORD))
                .withEagerQuery(PropositionQuery.againstContext("A").withLimit(1))
                .memory();

        String contribution = memory.contribution();

        assertEquals(1, rowLines(contribution).size(), contribution);
        assertTrue(contribution.contains("approved a1"), contribution);
        assertTrue(contribution.contains("2 memories available"), contribution);
        assertFalse(contribution.contains("fact "), contribution);
        assertEquals("Memories about topic", memory.getDescription());
    }

    @Test
    void fromListsTheApprovedRowsInGrantOrder() {
        Tool.Result result = PolicyMemory.from(view).call("{}");

        Tool.Result.Text text = assertInstanceOf(Tool.Result.Text.class, result);
        List<String> rows = rowLines(text.getContent());
        assertEquals(2, rows.size(), text.getContent());
        assertTrue(rows.get(0).contains("approved a1"), text.getContent());
        assertTrue(rows.get(1).contains("approved b1"), text.getContent());
        assertFalse(text.getContent().contains("fact "), text.getContent());
    }

    // The eager query names a context, so a strict policy must approve the owner field.
    private static RowGrant grant(String id, String context, String approvedText) {
        RowProjection projection = RowProjection.text(new ApprovedText.Literal(approvedText))
                .withFields(Set.of(MemoryField.OWNER));
        return RowGrant.of(new PropositionKey(context, id), projection, false);
    }

    private static List<String> rowLines(String text) {
        return text.lines().filter(line -> line.startsWith("- ")).toList();
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
