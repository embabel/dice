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

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Neo4j in Docker, shared by every test in this module's test run.
 *
 * <p>A Java copy of {@code dice-storage/src/test/kotlin/com/embabel/dice/storage/Neo4jTestContainer.kt},
 * on Testcontainers 2, where {@code Neo4jContainer} lives in {@code org.testcontainers.neo4j}
 * and is no longer generic. It runs the same image, {@code neo4j:2026.05-community}, with the
 * APOC plugin. The original also explains why that version and not Drivine's default.
 *
 * <p>The container starts the first time {@link #registerProperties} supplies a value, and
 * stays up for the rest of the run. The original gets the remaining datasource settings from
 * its test {@code application.yml}; this example has none, so they are set here too.
 */
final class Neo4jTestContainer {

    static final String PASSWORD = "test-password";

    private Neo4jTestContainer() {
    }

    /**
     * Points Drivine's {@code neo} datasource at the container, starting it if needed.
     *
     * @param registry where Spring collects the test's dynamic properties
     */
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("test.neo4j.use-local", () -> "true");
        registry.add("database.datasources.neo.type", () -> "NEO4J");
        registry.add("database.datasources.neo.host", () -> Holder.INSTANCE.getHost());
        registry.add("database.datasources.neo.port", () -> Holder.INSTANCE.getMappedPort(7687));
        registry.add("database.datasources.neo.username", () -> "neo4j");
        registry.add("database.datasources.neo.password", () -> PASSWORD);
        registry.add("database.datasources.neo.database-name", () -> "neo4j");
    }

    /** Holds the container, so the JVM starts it only when a property first asks for it. */
    private static final class Holder {

        static final Neo4jContainer INSTANCE = start();

        private static Neo4jContainer start() {
            var image = DockerImageName.parse("neo4j:2026.05-community")
                    .asCompatibleSubstituteFor(DockerImageName.parse("neo4j"));
            var container = new Neo4jContainer(image)
                    .withAdminPassword(PASSWORD)
                    .withPlugins("apoc")
                    .withNeo4jConfig("dbms.security.procedures.unrestricted", "apoc.*")
                    .withNeo4jConfig("dbms.security.procedures.allowlist", "apoc.*");
            container.start();
            return container;
        }
    }
}
