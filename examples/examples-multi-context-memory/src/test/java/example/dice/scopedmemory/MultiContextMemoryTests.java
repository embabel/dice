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
package example.dice.scopedmemory;

import com.embabel.agent.api.tool.Tool;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultiContextMemoryTests {

    private static final String SESSION = "maple-session";
    private static final String HANDBOOK = "support-handbook";
    private static final String OTHER_ACCOUNT = "cedar-session";
    private static final String COMMON_POLICY = "Route billing disputes to the renewals queue";
    private static final String MAPLE_REPORT = "Maple reported a billing dispute yesterday";
    private static final String CEDAR_REPORT = "Cedar has a confidential billing dispute about a duplicate charge";

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Fact(String contextId, String text) {}

    private static final SupportMemory.AuthenticatedPrincipal MAPLE_AGENT =
            new SupportMemory.AuthenticatedPrincipal("maple", "case-owner");
    private static final SupportMemory.AuthenticatedPrincipal CEDAR_AGENT =
            new SupportMemory.AuthenticatedPrincipal("cedar", "case-owner");
    private static final SupportMemory.AuthenticatedPrincipal HANDBOOK_READER =
            new SupportMemory.AuthenticatedPrincipal("maple", "handbook-reader");
    private static final SupportMemory.AuthenticatedPrincipal TRAINEE =
            new SupportMemory.AuthenticatedPrincipal("maple", "trainee");

    private final InMemoryPropositionRepository repository = new InMemoryPropositionRepository();

    private final SupportMemory support = new SupportMemory(repository, Map.of(
            MAPLE_AGENT, new SupportMemory.Grant(List.of(SESSION, HANDBOOK), 3),
            CEDAR_AGENT, new SupportMemory.Grant(List.of(OTHER_ACCOUNT), 2),
            HANDBOOK_READER, new SupportMemory.Grant(List.of(HANDBOOK), 3),
            TRAINEE, new SupportMemory.Grant(List.of(SESSION, HANDBOOK), 2)
    ));

    @BeforeEach
    void seedMemory() {
        save(SESSION, COMMON_POLICY);
        save(SESSION, MAPLE_REPORT);
        save(HANDBOOK, COMMON_POLICY);
        save(OTHER_ACCOUNT, CEDAR_REPORT);
    }

    @Test
    void theSameFactIsReturnedWithBothContextLabelsWhileAnotherAccountStaysOut() {
        var memory = support.forPrincipal(MAPLE_AGENT);
        Tool tool = memory.tools().getFirst();
        var reply = (Tool.Result.Text) tool.call("""
                {"query":"billing dispute","limit":3,"contextId":"cedar-session","tenantId":"cedar"}
                """);

        assertThat(facts(reply.getContent())).containsExactlyInAnyOrder(
                new Fact(SESSION, COMMON_POLICY),
                new Fact(HANDBOOK, COMMON_POLICY),
                new Fact(SESSION, MAPLE_REPORT)
        );
    }

    @Test
    void eagerPromptReadsUseTheSameScopeAsTheTool() {
        var memory = support.forPrincipal(HANDBOOK_READER);

        var reply = (Tool.Result.Text) memory.tools().getFirst().call("""
                {"query":"billing dispute"}
                """);
        for (String output : List.of(memory.contribution(), reply.getContent())) {
            assertThat(facts(output)).containsExactly(new Fact(HANDBOOK, COMMON_POLICY));
        }
    }

    @Test
    void grantsUseBothPartsOfTheAuthenticatedIdentity() {
        var cedar = support.forPrincipal(CEDAR_AGENT);
        var reply = (Tool.Result.Text) cedar.tools().getFirst().call("""
                {"query":"billing dispute"}
                """);
        assertThat(facts(reply.getContent())).containsExactly(new Fact(OTHER_ACCOUNT, CEDAR_REPORT));
        var absent = new SupportMemory.AuthenticatedPrincipal("cedar", "handbook-reader");
        assertThat(assertThrows(IllegalArgumentException.class, () -> support.forPrincipal(absent)))
                .hasMessage("No memory grant for the authenticated principal.");
    }

    @Test
    void theHostControlsTheLimitAndTheCountStaysHonest() {
        var memory = support.forPrincipal(TRAINEE);

        assertThat(memory.contribution()).contains("more than 2 memories").doesNotContain(OTHER_ACCOUNT);
        var error = assertInstanceOf(Tool.Result.Error.class, memory.tools().getFirst().call("""
                {"query":"billing","limit":3}
                """));
        assertThat(error.getMessage()).contains("budget allows 2");
    }

    private static List<Fact> facts(String output) {
        return output.lines().filter(line -> line.startsWith("{")).map(line -> {
            var row = assertDoesNotThrow(() -> JSON.readTree(line));
            return new Fact(row.get("contextId").asText(), row.get("text").asText());
        }).toList();
    }

    private void save(String context, String text) {
        repository.save(Proposition.draft(context, text, 0.9).toProposition());
    }
}
