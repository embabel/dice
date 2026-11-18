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

import javax.sql.DataSource

/**
 * How a SQLite store's connections start their transactions, and the check that it suits DICE.
 *
 * DICE's stores read and then write in one transaction: a save looks for the same text before it
 * inserts. That is safe on SQLite only when the transaction takes the file's write lock as it
 * begins (`IMMEDIATE`, or `EXCLUSIVE`). Started `DEFERRED`, two such transactions both read, and the
 * second to write is turned away with `SQLITE_BUSY`. Drivine's own SQLite provider starts them
 * `IMMEDIATE`; a provider a host builds itself might not.
 *
 * SQLite's driver is optional, so its connection is reached by name rather than by type.
 */
internal object SqliteTransactionMode {

    private const val SQLITE_CONNECTION = "org.sqlite.SQLiteConnection"

    private val LOCKING_MODES = setOf("IMMEDIATE", "EXCLUSIVE")

    /** The mode [dataSource]'s connections start transactions in, or null when they are not SQLite's. */
    fun of(dataSource: DataSource): String? {
        val sqliteConnection = runCatching { Class.forName(SQLITE_CONNECTION) }.getOrNull() ?: return null
        return dataSource.connection.use { connection ->
            if (!connection.isWrapperFor(sqliteConnection)) return null
            val sqlite = connection.unwrap(sqliteConnection)
            // The configured mode, which every BEGIN uses. The driver's "current" mode is cleared
            // whenever a connection goes back to auto-commit, so a pooled connection that has been
            // through a transaction would report none.
            val config = sqliteConnection.getMethod("getConnectionConfig").invoke(sqlite)
            (config.javaClass.getMethod("getTransactionMode").invoke(config) as? Enum<*>)?.name
        }
    }

    /**
     * Refuses a SQLite data source whose transactions do not take the write lock as they begin.
     *
     * @param datasourceName the Drivine datasource name, for the message
     * @throws IllegalStateException when the mode is not `IMMEDIATE` or `EXCLUSIVE`, or cannot be read
     */
    fun requireImmediate(dataSource: DataSource, datasourceName: String) {
        val mode = checkNotNull(of(dataSource)) {
            "SQLite datasource '$datasourceName' gives connections whose transaction mode cannot be read. DICE " +
                "needs its transactions to take the write lock as they begin: use Drivine's own SQLite provider, " +
                "or a data source whose connections unwrap to the SQLite driver's."
        }
        check(mode in LOCKING_MODES) {
            "SQLite datasource '$datasourceName' starts its transactions $mode. DICE reads and then writes in " +
                "one transaction, so SQLite must take the write lock as each one begins: set the data " +
                "source's transaction mode to IMMEDIATE, as Drivine's own SQLite provider does."
        }
    }
}
