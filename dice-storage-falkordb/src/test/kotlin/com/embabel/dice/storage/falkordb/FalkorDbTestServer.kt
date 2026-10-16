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
package com.embabel.dice.storage.falkordb

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.SchemaManager
import org.drivine.transaction.TransactionContextHolder
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/** The pinned FalkorDB server the FalkorDB tests run against, shared with other modules through this module's test jar. */
class FalkorDbTestServer : GenericContainer<FalkorDbTestServer>(
    DockerImageName.parse("falkordb/falkordb@sha256:0d793d4b249a9cf0837faa9f30fea1b86fb50086fc8aa21e9447078a07f995bc"),
) {
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

    /** A connection to [graph] wired through Drivine in STRICT mode, with the FalkorDB schema applied. */
    fun graph(graph: String = "dice_${UUID.randomUUID().toString().replace("-", "")}"): FalkorDbTestGraph =
        FalkorDbTestGraph(this, graph)
}

/** One connection to a graph: the object manager and persistence manager the stores take. */
class FalkorDbTestGraph(server: FalkorDbTestServer, val graph: String) : AutoCloseable {
    private val database = "falkor-${UUID.randomUUID()}"
    private val provider = FalkorDbConnectionProvider(
        database, server.host, server.getMappedPort(6379), null, graph,
        FalkorDbTransactionMode.STRICT, SubtypeRegistry(), CypherDialect.FALKORDB,
    )
    private val registry = DatabaseRegistry(DataSourceMap()).apply { register(provider) }
    private val managers = PersistenceManagerFactory(registry, TransactionContextHolder(registry))

    val persistence: PersistenceManager = managers.get(database)
    val objects: StatelessGraphObjectManager =
        GraphObjectManagerFactory(managers, Neo4jObjectMapper.instance, SubtypeRegistry()).stateless(database)

    init {
        SchemaManager(managers, registry, listOf(FalkorDbSchema.catalog(database))).enforce()
    }

    override fun close() = provider.end()
}
