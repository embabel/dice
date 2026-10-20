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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The user guide includes the tagged parts of these tests, so its examples always compile and run. */
class ScopedReadGuideExamplesTest {

    private final InMemoryPropositionRepository repository = new InMemoryPropositionRepository();

    ScopedReadGuideExamplesTest() {
        var now = Instant.now();
        for (var context : List.of("alice", "team", "bob")) {
            repository.save(Proposition.create(context + "-tea", context, context + " drinks green tea",
                    List.of(), 0.9, 0.0, null, List.of(), now, now, PropositionStatus.ACTIVE));
        }
    }

    @Test
    void readerSeesOnlyTheContextsItWasGiven() {
        // tag::open-reader[]
        var scope = ContextReadScope.ofValues(List.of("alice", "team"));
        var reader = ScopedPropositionReader.open(repository, scope);
        var session = reader.snapshot(Instant.now(), 2.0);

        long visible = session.count();
        List<Proposition> newest = session.query(PropositionQuery.OrderBy.CREATED_DESC, 10);
        long aliceOnly = session.restrict(PropositionQuery.againstContext("alice")).count();
        // end::open-reader[]
        assertEquals(2, visible);
        assertEquals(2, newest.size());
        assertTrue(newest.stream().noneMatch(p -> p.getContextIdValue().equals("bob")));
        assertEquals(1, aliceOnly);
    }

    @Test
    void scopedMemoryLeavesEagerMemoriesOutOfTheToolCall() {
        var scope = ContextReadScope.ofValues(List.of("alice", "team"));
        // tag::scoped-memory[]
        var memory = ScopedMemory.configuring(ScopedPropositionReader.open(repository, scope))
                .withTopic("drinks")
                .withEagerQuery(query -> query.withLimit(1))
                .memory();

        String prompt = memory.contribution();
        Tool.Result recalled = memory.call("{\"query\": \"green tea\", \"limit\": 5}");
        // end::scoped-memory[]
        var listed = ((Tool.Result.Text) recalled).getContent();
        assertFalse(prompt.contains("bob"));
        assertFalse(listed.contains("bob"));
        assertEquals(1, listed.lines().filter(line -> line.contains("drinks green tea")).count());
    }

    @Test
    void policyViewShowsOnlyApprovedText() {
        var scope = ContextReadScope.ofValues(List.of("alice", "team"));
        // tag::policy-view[]
        var summary = RowProjection.text(new ApprovedText.Literal("Alice likes tea"));
        var grant = RowGrant.of(new PropositionKey("alice", "alice-tea"), summary, false);
        var stamp = new ReadPolicyStamp("agent-7", "recall", "tea-policy", "1");
        var policy = RowPolicy.of(stamp, List.of(grant));

        var view = PolicyReadView.open(ScopedPropositionReader.open(repository, scope), policy);
        List<ProjectedMemory> rows = view.snapshot(Instant.now(), 2.0).list(10);
        var policyMemory = PolicyMemory.from(view);
        // end::policy-view[]
        assertEquals(1, rows.size());
        assertEquals("Alice likes tea", rows.get(0).getText());
        assertNull(rows.get(0).getId());
        assertNull(rows.get(0).getConfidence());
        assertEquals(1, policyMemory.tools().size());
    }
}
