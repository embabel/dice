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

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Delegates every call to [delegate] unchanged, and records whether a Spring-managed transaction
 * was active at the moment each [query] call ran. This is the probe the test class below uses to
 * tell whether [DrivineObservedSchemaSource]'s several queries genuinely share one transaction or
 * each opens its own: [TransactionSynchronizationManager.isActualTransactionActive] is `true` only
 * while a `@Transactional` method's advice has a transaction open on the calling thread.
 */
class TransactionRecordingPersistenceManager(private val delegate: PersistenceManager) : PersistenceManager by delegate {

    val transactionActiveDuringQuery: MutableList<Boolean> = CopyOnWriteArrayList()

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        transactionActiveDuringQuery += TransactionSynchronizationManager.isActualTransactionActive()
        return delegate.query(spec)
    }
}

/**
 * Marks [TransactionRecordingPersistenceManager] as the [Primary] `PersistenceManager` in this
 * test class's own Spring context, wrapping the real one [TestApplication] supplies. Every bean
 * that asks for a plain [PersistenceManager] — including [DrivineObservedSchemaSource] itself —
 * receives the recording wrapper instead, so its query calls are the ones under test.
 *
 * A distinct `@SpringBootTest(classes = [...])` combination gets its own cached Spring context, so
 * this substitution cannot leak into the other Drivine integration tests, which use
 * `TestApplication` alone.
 */
@TestConfiguration
open class RecordingPersistenceManagerConfig {

    @Bean
    @Primary
    open fun recordingPersistenceManager(
        @Qualifier("persistenceManager") real: PersistenceManager,
    ): TransactionRecordingPersistenceManager = TransactionRecordingPersistenceManager(real)
}

/**
 * Proves that [DrivineObservedSchemaSource]'s no-argument, whole-graph `observe()` entry point
 * runs its queries inside one transaction, the same way the context-scoped overload already does.
 *
 * The no-argument overload is [com.embabel.dice.metamodel.ObservedSchemaSource.observe]'s default
 * body, `= observe(null)`, a self-invocation on `this`. Annotating only `observe(contextId)` with
 * `@Transactional` leaves that self-invocation unadvised — Spring's proxy never sees it — so a
 * caller reaching this class through the no-argument overload alone would still run every one of
 * [DrivineObservedSchemaSource]'s whole-graph queries as a separate implicit transaction, exactly
 * the bug annotating the two-argument overload was meant to close. This is why
 * [DrivineObservedSchemaSource] carries its own `@Transactional`-annotated override of the
 * no-argument `observe()`.
 */
@SpringBootTest(classes = [TestApplication::class, RecordingPersistenceManagerConfig::class])
class DrivineObservedSchemaSourceTransactionIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var source: DrivineObservedSchemaSource

    @Autowired
    private lateinit var recordingPersistenceManager: TransactionRecordingPersistenceManager

    @Test
    fun `the no-argument observe() runs its whole-graph queries inside one active transaction`() {
        // Schema setup and other beans' own startup queries run before this point; only what
        // observe() itself issues is relevant.
        recordingPersistenceManager.transactionActiveDuringQuery.clear()

        source.observe()

        val recorded = recordingPersistenceManager.transactionActiveDuringQuery
        assertTrue(recorded.isNotEmpty(), "expected observe() to issue at least one query")
        assertTrue(
            recorded.all { it },
            "every query the no-argument observe() issues must run inside an active transaction, " +
                "but saw $recorded",
        )
    }
}
