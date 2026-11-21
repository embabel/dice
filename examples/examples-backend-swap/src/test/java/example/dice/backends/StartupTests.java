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

import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the application with an unrelated Drivine datasource and a placeholder model.
 */
@SpringBootTest(properties = {
        "embabel.models.default-llm=scripted",
        "database.datasources.other.type=SQLITE",
})
@Import(ScriptedModel.class)
@DirtiesContext
class StartupTests {

    @TempDir
    static Path temporaryDirectory;

    @Autowired
    PropositionRepository propositions;

    @Autowired
    MemoryDesk desk;

    @DynamicPropertySource
    static void otherDatasource(DynamicPropertyRegistry registry) {
        registry.add("database.datasources.other.database-name", () -> temporaryDirectory.resolve("other.db").toString());
    }

    @Test
    void keepsTheDefaultInMemoryStoreWithAnUnrelatedDatasource() {

        assertThat(propositions).isInstanceOf(InMemoryPropositionRepository.class);
        assertThat(desk).isNotNull();
    }
}
