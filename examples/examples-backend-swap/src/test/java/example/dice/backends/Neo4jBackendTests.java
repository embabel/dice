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

import com.embabel.dice.storage.DrivinePropositionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the shared assertions on DICE's Neo4j store, against a Neo4j started in Docker. Without
 * Docker the whole class is skipped rather than failed.
 */
@SpringBootTest(properties = {
        "embabel.dice.store.type=graph",
        "embabel.dice.store.decay.enabled=false",
        "embabel.models.default-llm=scripted",
})
@Testcontainers(disabledWithoutDocker = true)
class Neo4jBackendTests extends SameCodeEveryBackend {

    /**
     * Points the {@code neo} datasource at the test container. The container starts here, the
     * first time a context needs it, so a skipped class never starts one.
     */
    @DynamicPropertySource
    static void neo4j(DynamicPropertyRegistry registry) {
        Neo4jTestContainer.registerProperties(registry);
    }

    @Test
    void usesTheNeo4jStore() {

        // Spring wraps the repository in a proxy subclass, which still passes this check.
        assertThat(repository).isInstanceOf(DrivinePropositionRepository.class);
    }
}
