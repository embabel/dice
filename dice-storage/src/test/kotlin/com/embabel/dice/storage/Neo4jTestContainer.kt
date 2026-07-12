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
package com.embabel.dice.storage

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName

/**
 * One self-managed Neo4j testcontainer, shared by every IT in this module's test JVM, pinned to a
 * version we choose instead of the `neo4j:5.26.1-community` `@EnableDrivineTestConfig` hardcodes
 * (no override hook of its own, and 0.0.58 is drivine4j's newest release). `5.26.1-community` has
 * a confirmed upstream bug (https://github.com/neo4j/neo4j/issues/13597): a dynamic
 * relationship-type parameter gets baked into the query-plan cache on first execution and
 * silently reused for every later execution of the same query text with a different value.
 * Confirmed fixed on `neo4j:2026.05-community`, which is what this starts.
 *
 * Rather than let Drivine start its own container, each test class wires this one in via
 * `test.neo4j.use-local=true` -- the officially-supported switch that tells
 * `DrivineTestConfiguration` to use whatever's in the Spring `Environment` for the `neo` datasource
 * as-is, instead of overriding host/port/password with its own testcontainer. A `@DynamicPropertySource`
 * method in each test class (see [registerProperties]) supplies those values from *this* container,
 * started on first use and reused (via Kotlin `object`/`by lazy`) for the rest of the test JVM.
 *
 * Duplicated (not shared) with `dice-storage-autoconfigure`'s copy -- each module's tests run in
 * their own forked JVM/classpath.
 */
object Neo4jTestContainer {

    const val PASSWORD = "test-password"

    val instance: Neo4jContainer<*> by lazy {
        Neo4jContainer(DockerImageName.parse("neo4j:2026.05-community").asCompatibleSubstituteFor(DockerImageName.parse("neo4j")))
            .withAdminPassword(PASSWORD)
            .withPlugins("apoc")
            .withNeo4jConfig("dbms.security.procedures.unrestricted", "apoc.*")
            .withNeo4jConfig("dbms.security.procedures.allowlist", "apoc.*")
            .also { it.start() }
    }

    /** Points Drivine's `neo` datasource at [instance] instead of its own testcontainer. */
    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("test.neo4j.use-local") { "true" }
        registry.add("database.datasources.neo.host") { instance.host }
        registry.add("database.datasources.neo.port") { instance.getMappedPort(7687) }
        registry.add("database.datasources.neo.password") { PASSWORD }
    }
}
