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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the application as configured, on the in-memory store, with the placeholder model.
 */
@SpringBootTest(properties = "embabel.models.default-llm=scripted")
@Import(ScriptedModel.class)
class StartupTests {

    @Autowired
    PropositionRepository propositions;

    @Autowired
    MemoryDesk desk;

    @Test
    void startsOnTheInMemoryStore() {

        assertThat(propositions).isNotNull();
        assertThat(desk).isNotNull();
    }
}
