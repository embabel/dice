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
package com.embabel.dice.eval

import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.storage.falkordb.FalkorDbPropositionRepository
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.AfterEach
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/** Runs the shared ingestion, projection, query, collector and report contract against FalkorDB. */
@Testcontainers
class FalkorDbCanonicalFlowTest : AbstractCanonicalFlowTest() {
    private val providers = mutableListOf<FalkorDbConnectionProvider>()

    override fun newStore(): PropositionRepository {
        val provider = FalkorDbConnectionProvider(
            "canonical-flow", server.host, server.getMappedPort(6379), null,
            "canonical_${UUID.randomUUID().toString().replace("-", "")}",
            FalkorDbTransactionMode.WARN, SubtypeRegistry(), CypherDialect.FALKORDB,
        )
        providers += provider
        return FalkorDbPropositionRepository(provider, embeddingService = newEmbeddingService())
    }

    @AfterEach
    fun closeProviders() = providers.forEach { it.end() }

    class Server : GenericContainer<Server>(DockerImageName.parse(
        "falkordb/falkordb@sha256:0d793d4b249a9cf0837faa9f30fea1b86fb50086fc8aa21e9447078a07f995bc",
    )) {
        init {
            withExposedPorts(6379)
            withCreateContainerCmdModifier {
                it.withEntrypoint("redis-server")
                it.hostConfig!!.withMemory(1024L * 1024 * 1024).withNanoCPUs(2_000_000_000)
                    .withPortBindings(PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort(6379)))
            }
            withCommand("--loadmodule", "/var/lib/falkordb/bin/falkordb.so", "THREAD_COUNT", "2",
                "--save", "", "--appendonly", "no", "--maxmemory", "512mb", "--maxmemory-policy", "noeviction")
            waitingFor(Wait.forListeningPort())
        }
    }

    companion object {
        @Container
        @JvmField
        val server = Server()
    }
}
