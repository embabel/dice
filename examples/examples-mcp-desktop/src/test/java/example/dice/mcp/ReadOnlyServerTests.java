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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Talks to the server the way a desktop client would, with the app's own settings: a real MCP
 * client, over HTTP, on a random local port.
 *
 * <p>Out of the box the tools only read, so the client sees the three demo facts and has no
 * way to add to them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadOnlyServerTests {

    private static final Pattern ID = Pattern.compile("id=([^ |]+)");

    @Value("${local.server.port}")
    int port;

    McpSyncClient client;

    McpSchema.InitializeResult handshake;

    // One session for the whole class: nothing here changes what the server holds.
    @BeforeAll
    void connect() {
        client = McpClient
                .sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port + "/mcp").build())
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        handshake = client.initialize();
    }

    @AfterAll
    void disconnect() {
        client.closeGracefully();
    }

    @Test
    void introducesItselfAsDiceMemory() {

        assertThat(handshake.serverInfo().name()).isEqualTo("dice-memory");
    }

    @Test
    void offersTheReadToolsButNotStore() {

        var names = toolNames();

        // The platform adds tools of its own, so check for ours rather than the whole list.
        assertThat(names).contains("dice_recall", "dice_list", "dice_get");
        assertThat(names).doesNotContain("dice_store");
    }

    @Test
    void recallsTheAcmeFactWhenAskedAboutTheProPlan() {

        var text = call("dice_recall", Map.of("contextId", "demo", "query", "Pro plan"));

        assertThat(text).contains("Acme is on the Pro plan");
    }

    @Test
    void listsAllThreeDemoFacts() {

        var text = call("dice_list", Map.of("contextId", "demo"));

        assertThat(text).startsWith("Found 3 memories in context 'demo':");
        assertThat(text.lines().filter(line -> line.matches("\\d+\\. id=.*")))
                .hasSize(3)
                .allMatch(line -> line.contains("| confidence=0.90 |"));
        assertThat(text).contains(
                "Acme is on the Pro plan",
                "Dana is Acme's admin",
                "Acme prefers email over phone");
    }

    @Test
    void fetchesAListedFactByItsId() {

        var listed = call("dice_list", Map.of("contextId", "demo"));
        var line = listed.lines()
                .filter(l -> l.contains("Acme is on the Pro plan"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The plan fact was not listed: " + listed));
        var matcher = ID.matcher(line);
        assertThat(matcher.find()).as("an id in: %s", line).isTrue();
        var id = matcher.group(1);

        var text = call("dice_get", Map.of("contextId", "demo", "propositionId", id));

        assertThat(text).startsWith("id=" + id + " | status=ACTIVE |");
        assertThat(text).contains("Acme is on the Pro plan");
    }

    @Test
    void seesNoMemoriesInAnotherContext() {

        assertThat(call("dice_list", Map.of("contextId", "elsewhere")))
                .isEqualTo("No memories in context 'elsewhere'.");
        assertThat(call("dice_recall", Map.of("contextId", "elsewhere", "query", "Pro plan")))
                .isEqualTo("No memories matched 'Pro plan'. Context 'elsewhere' is empty.");
    }

    private List<String> toolNames() {
        return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    private String call(String tool, Map<String, Object> arguments) {
        var result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        var text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .collect(Collectors.joining("\n"));
        assertThat(result.isError()).as("%s failed: %s", tool, text).isNotEqualTo(Boolean.TRUE);
        return text;
    }
}
