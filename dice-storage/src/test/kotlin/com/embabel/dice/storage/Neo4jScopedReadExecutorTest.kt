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

import org.assertj.core.api.Assertions.assertThat
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import kotlin.test.assertFailsWith

/**
 * Checks that building an executor by hand on the wrong kind of database fails with a message
 * that says what went wrong, instead of a bare cast or lookup error.
 */
class Neo4jScopedReadExecutorTest {

    private fun manager(type: DatabaseType): PersistenceManager = mock(PersistenceManager::class.java).also {
        `when`(it.database).thenReturn("graph")
        `when`(it.type).thenReturn(type)
    }

    @Test
    fun `E1 a database other than Neo4j is rejected with its name and type`() {
        val provider = mock(ConnectionProvider::class.java)
        val registry = mock(DatabaseRegistry::class.java)
        `when`(registry.connectionProvider("graph")).thenReturn(provider)

        val error = assertFailsWith<IllegalArgumentException> {
            Neo4jScopedReadExecutor.from(manager(DatabaseType.FALKORDB), registry, DataSourceMap(emptyMap()))
        }

        assertThat(error.message).contains("'graph'").contains("FALKORDB")
        verifyNoInteractions(provider)
    }

    @Test
    fun `E2 a missing data source is rejected with its name`() {
        val provider = mock(Neo4jConnectionProvider::class.java)
        val registry = mock(DatabaseRegistry::class.java)
        `when`(registry.connectionProvider("graph")).thenReturn(provider)

        val error = assertFailsWith<IllegalArgumentException> {
            Neo4jScopedReadExecutor.from(manager(DatabaseType.NEO4J), registry, DataSourceMap(emptyMap()))
        }

        assertThat(error.message).contains("'graph'")
        verifyNoInteractions(provider)
    }
}
