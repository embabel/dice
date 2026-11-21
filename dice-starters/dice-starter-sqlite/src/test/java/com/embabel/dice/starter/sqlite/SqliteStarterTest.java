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
package com.embabel.dice.starter.sqlite;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.storage.DrivinePropositionRepository;
import org.drivine.connection.DatabaseType;
import org.drivine.manager.PersistenceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteStarterTest {

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withUserConfiguration(Host.class)
            .withPropertyValues("embabel.dice.store.decay.enabled=false");

    @TempDir
    Path temporaryDirectory;

    @Test
    void aConfiguredSqliteDatasourceActivatesDrivineStores() {
        Path database = temporaryDirectory.resolve("dice.db");
        application.withPropertyValues(
                "database.datasources.local.type=SQLITE",
                "database.datasources.local.database-name=" + database
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PersistenceManager.class);
            assertThat(context.getBean(PersistenceManager.class).getType()).isEqualTo(DatabaseType.SQLITE);
            var repository = context.getBean(PropositionRepository.class);
            assertThat(repository).isInstanceOf(DrivinePropositionRepository.class);
            assertThat(repository.count()).isZero();
            assertThat(database).isRegularFile();
        });
    }

    @Test
    void withoutADatasourceTheStarterKeepsInMemoryStores() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PersistenceManager.class);
            assertThat(context.getBean(PropositionRepository.class)).isInstanceOf(InMemoryPropositionRepository.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Host {
        @Bean
        Ai ai() {
            var ai = mock(Ai.class);
            var embeddings = mock(EmbeddingService.class);
            when(embeddings.getName()).thenReturn("test");
            when(embeddings.getDimensions()).thenReturn(3);
            when(ai.withDefaultEmbeddingService()).thenReturn(embeddings);
            return ai;
        }
    }
}
