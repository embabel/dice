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
package com.embabel.dice.storage.sqlite

import org.springframework.test.context.DynamicPropertyRegistry
import java.nio.file.Files

/**
 * Points Drivine's `neo` datasource at a fresh SQLite file instead of the Neo4j testcontainer, so a
 * store suite written for Neo4j runs again on Drivine's SQLite backend. SQLite needs no container:
 * each test class gets its own file in the temp directory.
 */
object SqliteTestStore {

    /**
     * Overrides every datasource property the Neo4j setup registers, so a SQLite test class never
     * starts the container.
     */
    fun registerProperties(registry: DynamicPropertyRegistry) {
        val file = Files.createTempFile("dice-sqlite-", ".db").also { Files.delete(it) }
        file.toFile().deleteOnExit()
        registry.add("database.datasources.neo.type") { "SQLITE" }
        registry.add("database.datasources.neo.database-name") { file.toString() }
        registry.add("database.datasources.neo.host") { "" }
        registry.add("database.datasources.neo.port") { "0" }
    }
}
