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
package com.embabel.dice.storage.autoconfigure

import org.assertj.core.api.Assertions.assertThat
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files

/**
 * The proposition vector index on Drivine's SQLite backend: the spec the autoconfiguration
 * registers is one SQLite can make, and convergence makes it once a model arrives and leaves it
 * alone after that.
 */
@SpringBootTest(classes = [SqliteVectorIndexTestApplication::class])
class SqliteVectorIndexConvergenceIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) {
            val file = Files.createTempFile("dice-sqlite-vector-", ".db").also { Files.delete(it) }
            file.toFile().deleteOnExit()
            registry.add("database.datasources.neo.type") { "SQLITE" }
            registry.add("database.datasources.neo.database-name") { file.toString() }
            registry.add("database.datasources.neo.host") { "" }
            registry.add("database.datasources.neo.port") { "0" }
        }
    }

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private val config = DiceStorageAutoConfiguration()

    @Test
    fun `convergence makes the proposition vector index on SQLite once a model arrives, and only once`() {
        assertThat(persistenceManager.type).isEqualTo(DatabaseType.SQLITE)
        var dimensions: Int? = null
        val convergence = PropositionVectorIndexConvergence(persistenceManager, { dimensions }, config::propositionVectorIndexSpec)

        assertThat(convergence.ensure()).isFalse()
        assertThat(persistenceManager.indexes.find(config.propositionVectorIndexSpec(8))).isNull()

        dimensions = 8
        assertThat(convergence.ensure()).isTrue()
        assertThat(convergence.ensure()).isTrue()

        val made = persistenceManager.indexes.find(config.propositionVectorIndexSpec(8))
        assertThat(made).isNotNull
        assertThat(made!!.dimensions).isEqualTo(8)
        assertThat(made.similarity).isEqualTo(config.propositionVectorIndexSpec(8).similarity)

        // A model of another width does not reshape it: that is reembedAll's job.
        dimensions = 16
        convergence.ensure()
        assertThat(persistenceManager.indexes.find(config.propositionVectorIndexSpec(8))!!.dimensions).isEqualTo(8)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableDrivine
@EnableDrivineTestConfig
internal open class SqliteVectorIndexTestApplication {

    @Bean
    open fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("neo")
}
