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
import org.drivine.connection.DatabaseType;
import org.drivine.manager.PersistenceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "embabel.dice.store.decay.enabled=false",
        "embabel.models.default-llm=scripted",
        "database.datasources.local.type=SQLITE",
})
@DirtiesContext
class SqliteBackendTests extends SameCodeEveryBackend {

    @TempDir
    static Path temporaryDirectory;

    @Autowired
    PersistenceManager persistenceManager;

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry) {
        registry.add("database.datasources.local.database-name", () -> database().toString());
    }

    @Test
    void usesTheSqliteStore() {

        assertThat(repository).isInstanceOf(DrivinePropositionRepository.class);
        assertThat(persistenceManager.getType()).isEqualTo(DatabaseType.SQLITE);
        assertThat(database()).isRegularFile();
    }

    private static Path database() {
        return temporaryDirectory.resolve("dice.db");
    }
}
