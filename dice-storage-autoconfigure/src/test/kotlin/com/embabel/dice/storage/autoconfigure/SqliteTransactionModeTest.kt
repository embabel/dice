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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.drivine.connection.JdbcConnectionProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import javax.sql.DataSource

/**
 * DICE's stores read and then write in one transaction and count on SQLite to take the write lock
 * as that transaction begins. A SQLite file whose connections start transactions any other way is
 * refused at startup, before a save can fail on it.
 */
class SqliteTransactionModeTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `Drivine's own SQLite provider is accepted`() {
        val provider = JdbcConnectionProvider.sqlite(name = "neo", path = dir.resolve("drivine.db").toString())
        try {
            assertThatCode { SqliteTransactionMode.requireImmediate(provider.nativeDriver as DataSource, "neo") }
                .doesNotThrowAnyException()
        } finally {
            provider.end()
        }
    }

    @Test
    fun `a SQLite data source that starts transactions deferred is refused, naming the datasource`() {
        val deferred = dataSource(SQLiteConfig.TransactionMode.DEFERRED)

        assertThatThrownBy { SqliteTransactionMode.requireImmediate(deferred, "neo") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("neo")
            .hasMessageContaining("IMMEDIATE")
            .hasMessageContaining("DEFERRED")
    }

    @Test
    fun `a SQLite data source that starts transactions immediately is accepted`() {
        assertThatCode { SqliteTransactionMode.requireImmediate(dataSource(SQLiteConfig.TransactionMode.IMMEDIATE), "neo") }
            .doesNotThrowAnyException()
    }

    /**
     * A pool hands out a connection that has already been through a transaction and gone back to
     * auto-commit. The driver forgets the mode it last began with at that point, but the next
     * BEGIN still uses the configured one.
     */
    @Test
    fun `a pooled connection that has been through a transaction still shows a deferred mode`() {
        val pool = HikariDataSource(HikariConfig().apply {
            dataSource = dataSource(SQLiteConfig.TransactionMode.DEFERRED)
            maximumPoolSize = 1
        })
        try {
            pool.connection.use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS t (x INTEGER)") }
                connection.commit()
                connection.autoCommit = true
            }

            assertThatThrownBy { SqliteTransactionMode.requireImmediate(pool, "neo") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("DEFERRED")
        } finally {
            pool.close()
        }
    }

    @Test
    fun `the mode read from the data source is the one its connections use`() {
        assertThat(SqliteTransactionMode.of(dataSource(SQLiteConfig.TransactionMode.EXCLUSIVE))).isEqualTo("EXCLUSIVE")
    }

    private fun dataSource(mode: SQLiteConfig.TransactionMode): DataSource =
        SQLiteDataSource(SQLiteConfig().apply { setTransactionMode(mode) }).apply {
            url = "jdbc:sqlite:" + dir.resolve("store-" + mode.name + ".db")
        }
}
