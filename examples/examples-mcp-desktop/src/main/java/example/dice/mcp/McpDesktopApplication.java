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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A small server that hands DICE memory to a desktop MCP client such as Claude Desktop.
 *
 * <p>It serves the memory as MCP tools over streamable HTTP. Out of the box the tools only
 * read: a client can recall, list and fetch facts, but cannot store new ones.
 *
 * <p>The other examples are test suites and never start on their own. This one is meant to be
 * left running while a client talks to it, so it has a {@code main} method.
 */
@SpringBootApplication
public class McpDesktopApplication {

    /**
     * Starts the server.
     *
     * @param args command line arguments, passed on to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(McpDesktopApplication.class, args);
    }
}
