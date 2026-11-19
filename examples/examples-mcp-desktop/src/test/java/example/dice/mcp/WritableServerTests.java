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
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Talks to the server with writes turned on, the way a desktop client would: a real MCP client,
 * over HTTP, on a random local port.
 *
 * <p>With {@code embabel.dice.mcp.writes-enabled=true} the client can store a fact and then
 * find it again.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "embabel.dice.mcp.writes-enabled=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WritableServerTests {

    // A context of its own, so storing here leaves the three demo facts as they are.
    private static final String CONTEXT = "desk-notes";

    private static final Pattern STORED = Pattern.compile("^Stored proposition (\\S+): (.*)$");

    @Value("${local.server.port}")
    int port;

    McpSyncClient client;

    McpSchema.InitializeResult handshake;

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
    void offersStoreAlongsideTheReadTools() {

        var names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

        assertThat(names).contains("dice_store", "dice_recall", "dice_list", "dice_get");
    }

    @Test
    void aStoredFactComesBackFromRecallAndGet() {

        var stored = call("dice_store", Map.of(
                "contextId", CONTEXT,
                "text", "Acme renews its contract in March",
                "confidence", 0.9));
        var matcher = STORED.matcher(stored);
        assertThat(matcher.matches()).as("a store confirmation: %s", stored).isTrue();
        var id = matcher.group(1);
        assertThat(matcher.group(2)).isEqualTo("Acme renews its contract in March");

        var recalled = call("dice_recall", Map.of("contextId", CONTEXT, "query", "contract renewal in March"));
        assertThat(recalled).contains("id=" + id + " | confidence=0.90 | Acme renews its contract in March");

        var fetched = call("dice_get", Map.of("contextId", CONTEXT, "propositionId", id));
        assertThat(fetched).startsWith("id=" + id + " | status=ACTIVE |");
        assertThat(fetched).contains("Acme renews its contract in March");
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
