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

import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.UniquenessConstraintSpec
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy

/**
 * Test wiring for this module's Neo4j-backed tests: Drivine's test support spins a Neo4j
 * testcontainer and hands us a [PersistenceManager] wired against it. Mirrors
 * `dice-storage`'s own `TestApplication`, trimmed to just what [DrivineObservedSchemaSource]
 * needs -- no embedding service or vector index, since it only ever runs plain Cypher.
 */
@Configuration
@EnableDrivine
@EnableDrivineTestConfig
@EnableAspectJAutoProxy(proxyTargetClass = true)
open class TestApplication {

    @Bean
    open fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("neo")

    @Bean
    open fun propositionSchema(): SchemaCatalog = SchemaCatalog.of(
        UniquenessConstraintSpec(label = "Proposition", property = "id"),
    )
}
