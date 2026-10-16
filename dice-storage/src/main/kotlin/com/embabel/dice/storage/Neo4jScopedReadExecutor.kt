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

import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import java.time.Duration
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.PersistenceManager
import org.neo4j.driver.AccessMode
import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.Transaction
import org.neo4j.driver.TransactionConfig
import org.neo4j.driver.exceptions.Neo4jException

/**
 * Runs scoped reads in their own read transactions on the driver Drivine already configured.
 * The server stops a transaction that runs past its timeout. A read sees committed data only, so it
 * does not see writes still open in the caller's transaction. Closing a read never closes the driver.
 */
class Neo4jScopedReadExecutor private constructor(
    private val driver: Driver,
    private val databaseName: String?,
) {
    internal fun <T> read(timeout: Duration, action: (Transaction) -> T): T =
        read(timeout, ScopedReadFailure.SCOPED_READ_FAILED, action)

    /**
     * Runs [action] in a read transaction the server stops after [timeout].
     * Any driver failure comes back as a [ScopedReadException] that keeps the driver's exception as its cause:
     * a server timeout fails with [timeoutFailure], everything else with SCOPED_READ_FAILED.
     * Exceptions the action throws itself, scoped read failures included, pass through untouched.
     */
    internal fun <T> read(timeout: Duration, timeoutFailure: ScopedReadFailure, action: (Transaction) -> T): T {
        require(!timeout.isNegative && !timeout.isZero) { "Read timeout must be positive" }
        val config = SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).apply {
            databaseName?.let { withDatabase(it) }
        }.build()
        return try {
            driver.session(config).use { session ->
                session.beginTransaction(TransactionConfig.builder().withTimeout(timeout).build()).use { tx ->
                    action(tx).also { tx.commit() }
                }
            }
        } catch (e: Neo4jException) {
            throw ScopedReadException(
                if (e.code() in TIMED_OUT) timeoutFailure else ScopedReadFailure.SCOPED_READ_FAILED, e,
            )
        }
    }

    companion object {
        /** The codes the server uses when it stops a transaction for running past its timeout. */
        private val TIMED_OUT = setOf(
            "Neo.ClientError.Transaction.TransactionTimedOutClientConfiguration",
            "Neo.ClientError.Transaction.TransactionTimedOut",
        )

        /**
         * Builds an executor on the Neo4j driver behind [manager]'s database.
         *
         * The manager's name goes through the registry first, so `"default"` finds the data source
         * of the provider Drivine actually hands out for it. Fails with IllegalArgumentException
         * when that database is not Neo4j or has no data source.
         */
        @JvmStatic
        fun from(
            manager: PersistenceManager,
            registry: DatabaseRegistry,
            sources: DataSourceMap,
        ): Neo4jScopedReadExecutor {
            // A name the registry doesn't know stays as it is and finds no provider below.
            val name = registry.resolveDatabaseName(manager.database) ?: manager.database
            val provider = registry.connectionProvider(name) as? Neo4jConnectionProvider
                ?: throw IllegalArgumentException(
                    "Native scoped reads need a Neo4j database, but '${manager.database}' is ${manager.type}",
                )
            val source = requireNotNull(sources.dataSources[name]) {
                "Native scoped reads found no Drivine data source named '$name'"
            }
            return Neo4jScopedReadExecutor(provider.nativeDriver, source.databaseName)
        }
    }
}
