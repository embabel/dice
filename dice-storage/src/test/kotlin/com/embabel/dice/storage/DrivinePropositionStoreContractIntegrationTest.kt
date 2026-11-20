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

import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.conformance.AbstractPropositionStoreContractTest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.storage.model.PropositionView
import org.drivine.StaleObjectException
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.load
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Runs the [AbstractPropositionStoreContractTest] suite against
 * [DrivinePropositionRepository] on a real graph store: Neo4j here, SQLite in the `sqlite` package.
 * This is the half that catches a graph backend
 * silently disagreeing with the in-memory contract — substitutability enforced, not assumed.
 */
@SpringBootTest(classes = [TestApplication::class])
abstract class DrivinePropositionStoreContractCases : AbstractPropositionStoreContractTest() {

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var graphObjectManager: StatelessGraphObjectManager

    @Autowired
    private lateinit var embeddingService: EmbeddingService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    override fun store(): PropositionStore = repository

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
        persistenceManager.execute(cypherQuery("MATCH (s:Source) DETACH DELETE s"))
    }

    /** The same text in two contexts is not deduplicated, so the two share an embedding and cluster. */
    @Test
    fun `findClusters pairs propositions with the same embedding`() {
        fun ghost(text: String, context: String) = Proposition(
            contextId = ContextId(context),
            text = text,
            mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )
        val first = repository.save(ghost("The ghost hums in the attic", "cluster-a"))
        val second = repository.save(ghost("The ghost hums in the attic", "cluster-b"))
        repository.save(ghost("A lonely distinct fact about the cellar", "cluster-a"))

        val clusters = repository.findClusters(similarityThreshold = 0.95, topK = 10, query = PropositionQuery())

        assertEquals(1, clusters.size)
        assertEquals(setOf(first.id, second.id), setOf(clusters.single().anchor.id) + clusters.single().similar.map { it.match.id })
    }

    /** Spring keeps candidate selection and similarity in the same read transaction. */
    @Test
    fun `findClusters opens one read-only transaction`() {
        fun ghost(text: String, context: String) = Proposition(
            contextId = ContextId(context),
            text = text,
            mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )
        repository.save(ghost("The ghost hums in the cellar", "opened-a"))
        repository.save(ghost("The ghost hums in the cellar", "opened-b"))
        val recording = OpenedTransactionRecorder(transactionManager)
        val clustering = ProxyFactory(
            DrivinePropositionRepository(graphObjectManager, persistenceManager, embeddingService, recording),
        ).apply {
            isProxyTargetClass = true
            addAdvice(TransactionInterceptor(recording, AnnotationTransactionAttributeSource()))
        }.proxy as DrivinePropositionRepository

        val clusters = clustering.findClusters(similarityThreshold = 0.95, topK = 10, query = PropositionQuery())

        assertEquals(1, clusters.size)
        assertEquals(listOf(true), recording.opened)
    }

    @Test
    fun `a directly constructed repository keeps an access update in the caller's transaction`() {
        val saved = repository.save(
            Proposition(
                contextId = ContextId("direct-touch"),
                text = "The ghost haunts the stairs",
                mentions = emptyList(),
                confidence = 0.9,
                lastAccessed = Instant.EPOCH,
            ),
        )
        val recording = OpenedTransactionRecorder(transactionManager)
        val direct = DrivinePropositionRepository(graphObjectManager, persistenceManager, embeddingService, recording)

        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            direct.touchAccessed(listOf(saved.id))
            status.setRollbackOnly()
        }

        assertEquals(emptyList<Boolean>(), recording.opened)
        assertEquals(Instant.EPOCH, repository.findById(saved.id)!!.lastAccessed)
    }

    @Test
    fun `a save drops the metadata keys the map no longer carries`() {
        val saved = repository.save(
            Proposition(
                contextId = ContextId("metadata-ctx"),
                text = "The ghost haunts the manor",
                mentions = listOf(
                    EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT),
                ),
                confidence = 0.9,
                metadata = mapOf("keep" to "a", "drop" to "b"),
            ),
        )

        repository.save(saved.copy(metadata = mapOf("keep" to "a")))

        val keys = persistenceManager.query(
            cypherQuery("MATCH (p:Proposition {id: ${'$'}id}) UNWIND keys(p) AS key RETURN key")
                .bind(mapOf("id" to saved.id))
                .transform(String::class.java),
        )
        assertTrue("metadata.keep" in keys, "keys on the node: $keys")
        assertFalse("metadata.drop" in keys, "keys on the node: $keys")
        assertEquals(mapOf("keep" to "a"), repository.findById(saved.id)!!.metadata)
    }

    /**
     * The second save holds the proposition exactly as stored, less one metadata key. The node write
     * changes nothing, so the mark is the key removal's.
     */
    @Test
    fun `a save of a loaded proposition is refused after a metadata key is removed from it`() {
        val saved = repository.save(
            Proposition(
                contextId = ContextId("metadata-ctx"),
                text = "The ghost haunts the manor",
                mentions = listOf(
                    EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT),
                ),
                confidence = 0.9,
                metadata = mapOf("source" to "wiki"),
            ),
        )
        val loaded = requireNotNull(graphObjectManager.load<PropositionView>(saved.id))

        repository.save(saved.copy(metadata = emptyMap()))

        assertThrows<StaleObjectException> {
            graphObjectManager.save(loaded, DrivinePropositionRepository.REPLACE_MENTIONS)
        }
    }
}

/** Runs the [DrivinePropositionStoreContractCases] on the shared Neo4j testcontainer. */
class DrivinePropositionStoreContractIntegrationTest : DrivinePropositionStoreContractCases() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }
}

/** Passes every call on, and records whether each transaction that actually began was read-only. */
private class OpenedTransactionRecorder(private val delegate: PlatformTransactionManager) : PlatformTransactionManager {
    val opened = mutableListOf<Boolean>()

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
        delegate.getTransaction(definition).also { if (it.isNewTransaction) opened += definition?.isReadOnly == true }

    override fun commit(status: TransactionStatus) = delegate.commit(status)

    override fun rollback(status: TransactionStatus) = delegate.rollback(status)
}
