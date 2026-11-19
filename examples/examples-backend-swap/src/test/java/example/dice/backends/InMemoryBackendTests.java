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

import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the shared assertions on DICE's in-memory store. Needs nothing outside the JVM.
 */
@SpringBootTest(properties = {
        "embabel.dice.store.type=in-memory",
        "embabel.dice.store.decay.enabled=false",
        "embabel.models.default-llm=scripted",
})
class InMemoryBackendTests extends SameCodeEveryBackend {

    @Test
    void usesTheInMemoryStore() {

        assertThat(repository).isInstanceOf(InMemoryPropositionRepository.class);
    }
}
