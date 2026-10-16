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

import java.time.Duration
import java.util.concurrent.TimeoutException
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.PersistenceManager
import org.neo4j.driver.AccessMode
import org.neo4j.driver.Driver
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.Result
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.Transaction
import org.neo4j.driver.TransactionConfig
import org.neo4j.driver.Value

/**
 * Borrows Drivine's configured driver for independent, committed-data read transactions.
 * The database timeout excludes connection acquisition and any caller-side embedding work.
 * No managed retries restart the allowance, and closing a read never closes the borrowed driver.
 * Driver exceptions retain their Neo4j codes; local monotonic deadlines throw [TimeoutException].
 */
class Neo4jScopedReadExecutor private constructor(
    private val driver: Driver,
    private val databaseName: String?,
) {
    internal fun <T> read(timeout: Duration, action: (Transaction) -> T): T {
        require(!timeout.isNegative && !timeout.isZero) { "Read timeout must be positive" }
        val config = SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).apply {
            databaseName?.let { withDatabase(it) }
        }.build()
        return driver.session(config).use { session ->
            session.beginTransaction(TransactionConfig.builder().withTimeout(timeout).build()).use { tx ->
                val bounded = DeadlineTransaction(tx, timeout)
                val value = action(bounded)
                bounded.checkDeadline()
                tx.commit()
                value
            }
        }
    }

    private class DeadlineTransaction(
        private val transaction: Transaction,
        timeout: Duration,
    ) : Transaction by transaction {
        private val started = System.nanoTime()
        private val allowance = timeout.toNanos()

        fun checkDeadline() {
            if (System.nanoTime() - started >= allowance) throw TimeoutException("Native read deadline exceeded")
        }

        override fun run(query: String): Result {
            checkDeadline()
            return transaction.run(query)
        }

        override fun run(query: String, parameters: Map<String, Any?>): Result {
            checkDeadline()
            return transaction.run(query, parameters)
        }

        override fun run(query: String, parameters: Value): Result {
            checkDeadline()
            return transaction.run(query, parameters)
        }

        override fun run(query: String, parameters: Record): Result {
            checkDeadline()
            return transaction.run(query, parameters)
        }

        override fun run(query: Query): Result {
            checkDeadline()
            return transaction.run(query)
        }
    }

    companion object {
        @JvmStatic
        fun from(
            manager: PersistenceManager,
            registry: DatabaseRegistry,
            sources: DataSourceMap,
        ): Neo4jScopedReadExecutor {
            val provider = registry.connectionProvider(manager.database) as Neo4jConnectionProvider
            val source = sources.dataSources.getValue(manager.database)
            return Neo4jScopedReadExecutor(provider.nativeDriver, source.databaseName)
        }
    }
}
