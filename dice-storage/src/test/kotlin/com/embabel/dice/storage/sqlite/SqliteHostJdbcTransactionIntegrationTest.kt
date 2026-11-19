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

import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionReembedIncompleteException
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.FakeEmbeddingService
import com.embabel.dice.storage.GraphDecayManager
import com.embabel.dice.storage.TestApplication
import com.embabel.dice.storage.cypherQuery
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.TransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.nio.file.Files
import java.time.Instant
import javax.sql.DataSource

/**
 * The one wiring DICE supports where a host's own JDBC transaction manager and Drivine share a
 * SQLite file: Drivine reads it through a transaction-aware proxy over the host's data source, so
 * inside a host transaction Drivine's statements run on the host's connection and are the host's
 * to commit or roll back.
 */
@SpringBootTest(classes = [TestApplication::class, SqliteHostJdbcTransactionIntegrationTest.ProxiedDrivine::class])
class SqliteHostJdbcTransactionIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) = SqliteTestStore.registerProperties(registry)

        /** The host's data source: a pool over the SQLite file, opened as Drivine opens its own. */
        val hostDataSource: DataSource by lazy {
            val file = Files.createTempFile("dice-sqlite-host-", ".db").also { Files.delete(it) }
            file.toFile().deleteOnExit()
            JdbcConnectionProvider.sqlite(name = "host", path = file.toString()).nativeDriver
        }
    }

    /** Drivine's `neo` datasource, read through a transaction-aware proxy over the host's pool. */
    @TestConfiguration
    open class ProxiedDrivine {
        @Bean
        open fun proxiedProvider(): ConnectionProvider =
            JdbcConnectionProvider("neo", DatabaseType.SQLITE, TransactionAwareDataSourceProxy(hostDataSource))
    }

    @Autowired
    private lateinit var graphObjectManager: StatelessGraphObjectManager

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private val hostManager = DataSourceTransactionManager(hostDataSource)

    private val host = JdbcTemplate(TransactionAwareDataSourceProxy(hostDataSource))

    private fun repository(embedding: EmbeddingService = FakeEmbeddingService()) =
        DrivinePropositionRepository(graphObjectManager, persistenceManager, embedding, hostManager)

    @AfterEach
    fun cleanUp() {
        repository().clearAll()
        host.execute("DROP TABLE IF EXISTS host_rows")
    }

    @Test
    fun `reembedAll inside a host transaction that rolls back leaves the host's work and the vectors as they were`() {
        val saved = repository().save(ghost("The ghost hums in the attic"))
        val before = embeddingOf(saved.id)
        host.execute("CREATE TABLE host_rows (x INTEGER)")

        TransactionTemplate(hostManager).executeWithoutResult { status ->
            host.update("INSERT INTO host_rows (x) VALUES (1)")
            repository(ShiftedEmbeddingService(FakeEmbeddingService())).reembedAll()
            status.setRollbackOnly()
        }

        assertEquals(0, host.queryForObject("SELECT count(*) FROM host_rows", Int::class.java))
        assertEquals(before, embeddingOf(saved.id))
    }

    /** A read-only host transaction whose connection takes writes gets the touch with its own commit. */
    @Test
    fun `touchAccessed inside a read-only host transaction records the access`() {
        val saved = repository().save(ghost("The ghost dusts the piano"))
        val touchedAt = Instant.now()

        TransactionTemplate(hostManager).apply { isReadOnly = true }.executeWithoutResult {
            repository().touchAccessed(listOf(saved.id))
        }

        val after = Instant.parse(lastAccessedOf(saved.id))
        assertFalse(after.isBefore(touchedAt), "lastAccessed $after predates the touch at $touchedAt")
    }

    /**
     * A connection that enforces read-only refuses the touch. The access time is a hint, so the
     * touch is left out and the host's transaction goes on.
     */
    @Test
    fun `touchAccessed on a connection that refuses writes leaves the host's read-only transaction going`() {
        val saved = repository().save(ghost("The ghost winds the clock"))
        val before = lastAccessedOf(saved.id)
        val refusing = DrivinePropositionRepository(
            graphObjectManager,
            ReadOnlyRefusingPersistenceManager(persistenceManager),
            FakeEmbeddingService(),
            hostManager,
        )
        var readAfter: Int? = null

        TransactionTemplate(hostManager).apply { isReadOnly = true }.executeWithoutResult {
            refusing.touchAccessed(listOf(saved.id))
            readAfter = host.queryForObject("SELECT 1", Int::class.java)
        }

        assertEquals(1, readAfter)
        assertEquals(before, lastAccessedOf(saved.id))
    }

    /**
     * Through Spring's own transaction advice. A host that catches a partial re-embed and commits
     * keeps what was re-embedded and its own work: the failure is reported, not a reason to throw
     * the host's transaction away.
     */
    @Test
    fun `a host that catches a partial reembedAll still commits the rewrites and its own work`() {
        val kept = repository().save(ghost("The ghost hums a tune"))
        repository().save(ghost("The ghost breaks the mirror"))
        val before = embeddingOf(kept.id)
        host.execute("CREATE TABLE host_rows (x INTEGER)")
        val reembedding = ProxyFactory(repository(BreakingEmbeddingService("breaks"))).apply {
            isProxyTargetClass = true
            addAdvice(TransactionInterceptor(hostManager as TransactionManager, AnnotationTransactionAttributeSource()))
        }.proxy as DrivinePropositionRepository

        TransactionTemplate(hostManager).executeWithoutResult {
            host.update("INSERT INTO host_rows (x) VALUES (1)")
            assertThrows<PropositionReembedIncompleteException> { reembedding.reembedAll() }
        }

        assertEquals(1, host.queryForObject("SELECT count(*) FROM host_rows", Int::class.java))
        assertNotEquals(before, embeddingOf(kept.id))
    }

    /**
     * A decay sweep inside a host transaction that rolls back leaves nothing behind: the pages join
     * the host's transaction instead of committing it with transactions of their own.
     */
    @Test
    fun `a decay sweep inside a host transaction that rolls back leaves the host's work and the decay as they were`() {
        val saved = repository().save(ghost("The ghost fades slowly"))
        host.execute("CREATE TABLE host_rows (x INTEGER)")

        TransactionTemplate(hostManager).executeWithoutResult { status ->
            host.update("INSERT INTO host_rows (x) VALUES (1)")
            GraphDecayManager(repository(), persistenceManager).materializeAll()
            status.setRollbackOnly()
        }

        assertEquals(0, host.queryForObject("SELECT count(*) FROM host_rows", Int::class.java))
        assertEquals(
            emptyList<String>(),
            persistenceManager.query(
                cypherQuery("MATCH (p:Proposition {id: ${'$'}id}) WHERE p.decayUpdatedAt IS NOT NULL RETURN p.id")
                    .bind(mapOf("id" to saved.id))
                    .transform(String::class.java),
            ),
        )
    }

    private fun embeddingOf(id: String): List<*>? = persistenceManager.maybeGetOne(
        cypherQuery("MATCH (p:Proposition {id: ${'$'}id}) RETURN p.embedding").bind(mapOf("id" to id)).transform(List::class.java),
    )

    private fun lastAccessedOf(id: String): String? = persistenceManager.maybeGetOne(
        cypherQuery("MATCH (p:Proposition {id: ${'$'}id}) RETURN p.lastAccessed").bind(mapOf("id" to id)).transform(String::class.java),
    )

    private fun ghost(text: String) = Proposition(
        contextId = ContextId("host-jdbc-ctx"),
        text = text,
        mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
        confidence = 0.9,
    )
}

/** The same width as [delegate], with different values, so a rewrite shows. */
private class ShiftedEmbeddingService(private val delegate: EmbeddingService) : EmbeddingService by delegate {
    override fun embed(text: String): FloatArray = delegate.embed("shifted $text")

    override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)
}

/** Refuses every write the way SQLite does on a connection that enforces read-only. */
private class ReadOnlyRefusingPersistenceManager(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    override fun execute(spec: QuerySpecification<*>) {
        throw IllegalStateException(
            "write refused",
            SQLiteException("[SQLITE_READONLY] Attempt to write a readonly database", SQLiteErrorCode.SQLITE_READONLY),
        )
    }
}

/** Embeds each text a little differently from [FakeEmbeddingService], and fails on any text containing [failOn]. */
private class BreakingEmbeddingService(private val failOn: String) : EmbeddingService by FakeEmbeddingService() {
    private val fake = FakeEmbeddingService()

    override fun embed(text: String): FloatArray {
        check(failOn !in text) { "embedding refused for this text" }
        return fake.embed("$text, again")
    }

    override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)
}
