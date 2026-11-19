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

import java.sql.SQLException
import javax.sql.DataSource

/**
 * The check that a SQLite store's connections carry the SQL functions Drivine's statements call.
 *
 * Drivine registers those functions on each connection its own SQLite pool opens, and nowhere
 * else, so the pool `JdbcConnectionProvider.sqlite(...)` makes is the only data source that has
 * them. A pool a host builds itself over the same file opens connections without them, and
 * Drivine's first statement on one fails. A transaction-aware proxy over Drivine's own pool keeps
 * them, since its connections are still the pool's.
 */
internal object SqliteDrivineFunctions {

    /** One of Drivine's functions: it lowercases text and reads nothing else, so calling it is cheap. */
    private const val PROBE = "drivine_lower"

    /**
     * Refuses a SQLite data source whose connections lack Drivine's functions.
     *
     * @param datasourceName the Drivine datasource name, for the message
     * @throws IllegalStateException when a connection has no such function
     */
    fun requirePresent(dataSource: DataSource, datasourceName: String) {
        val missing = try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.executeQuery("SELECT $PROBE('A')").use { rows -> rows.next() } }
            }
            false
        } catch (e: SQLException) {
            if (e.message.orEmpty().contains("no such function")) true else throw e
        }
        check(!missing) {
            "SQLite datasource '$datasourceName' gives connections without the SQL functions Drivine's " +
                "statements call, which Drivine registers only on the connections of its own SQLite pool. " +
                "Open the file with JdbcConnectionProvider.sqlite(...). To share it with a host transaction " +
                "manager, give the manager that pool's data source, and Drivine a TransactionAwareDataSourceProxy over it."
        }
    }
}
