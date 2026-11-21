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
import com.embabel.dice.agent.ScopedMemory
import com.embabel.dice.conformance.AbstractPropositionStoreContractTest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.model.PropositionView
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.store.ContextReadScope
import com.embabel.dice.store.ReadBudget
import com.embabel.dice.store.ReadBudgetExceededException
import com.embabel.dice.store.ReadCriteria
import com.embabel.dice.store.ReadKeywordQuery
import com.embabel.dice.store.ReadOrder
import com.embabel.dice.store.ReadQuery
import com.embabel.dice.store.ScopedReadSpec
import com.embabel.dice.store.StoreCapability
import com.embabel.dice.store.UnsupportedCapabilityException
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

    @Test
    fun `scoped queries rank filter and cap inside the graph database`() {
        fun save(id: String, context: String, text: String, status: PropositionStatus = PropositionStatus.ACTIVE) {
            repository.save(Proposition.draft(context, text, 0.9).withId(id).withStatus(status).toProposition())
        }
        save("1", "session", "alpha discount")
        save("2", "team", "alpha % discount")
        save("3", "outside", "alpha % private discount")
        save("4", "Session", "alpha % also private")
        save("5", "team", "alpha % past discount", PropositionStatus.SUPERSEDED)
        save("6", "team", "alpha % second discount")
        val reads = repository.scopedReads()
        val allowed = ScopedReadSpec.of(ContextReadScope.ofValues("session", "team")).narrow(
            ReadCriteria.empty().withStatus(PropositionStatus.ACTIVE),
        )
        val none = ReadCriteria.empty()
        val two = ReadBudget.ofRows(2)
        val three = ReadBudget.ofRows(3)

        assertEquals(listOf("1", "2", "6"), reads.query(allowed, ReadQuery.of(none), three).map { it.id })
        assertEquals(listOf("2", "6"), reads.matchKeywords(
            allowed, ReadKeywordQuery.forTokens("ALPHA", "%").withLimit(2), two,
        ).map { it.id })
        assertEquals(listOf("2", "6"), reads.matchKeywords(
            allowed, ReadKeywordQuery.forTokens("%"), two,
        ).map { it.id })
        assertEquals(3, reads.count(allowed, none.withMinConfidence(0.9), three))
        assertEquals(0, reads.count(allowed, none.withMinConfidence(0.95), three))
        assertEquals(3L, assertThrows<ReadBudgetExceededException> {
            reads.count(allowed, none, two)
        }.requested)
        assertThrows<ReadBudgetExceededException> { reads.query(allowed, ReadQuery.of(none), two) }
        assertThrows<ReadBudgetExceededException> { reads.matchKeywords(allowed, ReadKeywordQuery.forTokens("alpha"), two) }
        val denied = assertThrows<UnsupportedCapabilityException> {
            reads.query(allowed, ReadQuery.of(none.withMinEffectiveConfidence(0.5)).withLimit(3), two)
        }
        assertEquals(StoreCapability.SCOPED_READS, denied.capability)
        assertTrue(denied.message!!.contains("MIN_EFFECTIVE_CONFIDENCE"))
    }

    @Test
    fun `scoped filters and orders agree with the local view`() {
        val local = InMemoryPropositionRepository()
        val source = Proposition.draft("team", "policy observation", 0.9).withId("observed").toProposition()
        local.save(source)
        repository.save(source)

        fun save(id: String, level: Int, importance: Double, reinforced: Int, pinned: Boolean = true) {
            val fact = Proposition.draft("team", "policy $id", 0.9).withId(id)
                .withLevel(level).withSourceIds(listOf(source.id))
                .withImportance(importance).withReinforceCount(reinforced)
                .withPinned(pinned).toProposition()
            local.save(fact)
            repository.save(fact)
        }

        save("reinforced", 2, 0.7, 5)
        save("important", 3, 0.9, 4)
        save("low-level", 1, 1.0, 9)
        save("high-level", 4, 1.0, 9)
        save("low-importance", 2, 0.6, 9)
        save("low-reinforcement", 2, 1.0, 3)
        save("unpinned", 2, 1.0, 9, pinned = false)
        val scope = ScopedReadSpec.of(ContextReadScope.ofValues("team")).narrow(
            ReadCriteria.empty().withMinLevel(2).withMaxLevel(3).withPinned(true),
        )
        val criteria = ReadCriteria.empty().withMinImportance(0.7).withMinReinforceCount(4)
        val query = ReadQuery.of(criteria).withLimit(2)
        val budget = ReadBudget.ofRows(2)

        for (reads in listOf(local.scopedReads(), repository.scopedReads())) {
            assertEquals(2, reads.count(scope, criteria, budget))
            assertEquals(listOf("reinforced", "important"),
                reads.query(scope, query.orderedByReinforceCount(), budget).map { it.id })
            assertEquals(listOf("important", "reinforced"),
                reads.query(scope, query.orderedByImportance(), budget).map { it.id })
        }
    }

    @Test
    fun `scoped limits apply to propositions with multiple mentions`() {
        fun save(id: String, entities: List<String>) {
            val mentions = entities.map { entity ->
                EntityMention(span = entity, type = "Person", role = MentionRole.SUBJECT, resolvedId = entity)
            }
            repository.save(Proposition.draft("team", "policy $id", 0.9).withId(id).withMentions(mentions).toProposition())
        }

        save("1", listOf("alice", "beth"))
        save("2", listOf("charlie"))
        val reads = repository.scopedReads()
        val scope = ScopedReadSpec.of(ContextReadScope.ofValues("team"))
        val two = ReadBudget.ofRows(2)

        val rows = reads.query(scope, ReadQuery.of(ReadCriteria.empty()).withLimit(2), two)
        assertEquals(listOf("1", "2"), rows.map { it.id })
        assertEquals(setOf("alice", "beth"), rows.first().mentions.map { it.resolvedId }.toSet())
        val strongest = reads.matchKeywords(scope, ReadKeywordQuery.forTokens("policy").withLimit(1), two).single()
        assertEquals("1", strongest.id)
        assertEquals(2, strongest.mentions.size)
        assertEquals(2, reads.count(scope, ReadCriteria.empty(), two))
    }

    @Test
    fun `unsupported scoped prompt reads report the refusal without dropping the host filter`() {
        repository.save(Proposition.draft("team", "team policy", 0.9).toProposition())
        repository.save(Proposition.draft("other", "private policy", 0.9).toProposition())
        val reads = repository.scopedReads()
        val scope = ScopedReadSpec.of(ContextReadScope.ofValues("team"))
        val memory = ScopedMemory.forReads(reads, scope, ReadBudget.ofRows(2))
        val eagerOrder = ReadQuery.of(ReadCriteria.empty()).orderedByEffectiveConfidence()

        val eagerRefusal = memory.withEagerQuery(eagerOrder).contribution()
        assertTrue("1 memories available" in eagerRefusal)
        assertTrue("Key memories unavailable" in eagerRefusal)
        assertTrue("EFFECTIVE_CONFIDENCE_DESC" in eagerRefusal)
        assertFalse("team policy" in eagerRefusal)
        assertFalse("private policy" in eagerRefusal)

        val filterRefusal = memory.narrowedBy { it.withMinEffectiveConfidence(0.8) }
            .withEagerQuery(ReadQuery.of(ReadCriteria.empty()).withLimit(1)).contribution()
        assertTrue("Memory count unavailable" in filterRefusal)
        assertTrue("MIN_EFFECTIVE_CONFIDENCE" in filterRefusal)
        assertFalse("team policy" in filterRefusal)
        assertFalse("private policy" in filterRefusal)
    }

    @Test
    fun `scoped keyword matching lowercases non ASCII text inside the database`() {
        repository.save(Proposition.draft("team", "ÉCOLE handbook", 0.9).withId("allowed").toProposition())
        repository.save(Proposition.draft("other", "ÉCOLE confidential", 0.9).withId("other").toProposition())
        val reads = repository.scopedReads()
        val scope = ScopedReadSpec.of(ContextReadScope.ofValues("team"))

        val matches = reads.matchKeywords(scope, ReadKeywordQuery.forTokens("école"), ReadBudget.ofRows(1))
        assertEquals(listOf("allowed"), matches.map { it.id })
    }

    @Test
    fun `scoped creation order keeps fractional seconds before applying the limit`() {
        val stamps = mapOf(
            "older" to "2026-11-20T12:00:05Z",
            "middle" to "2026-11-20T12:00:05.500000001Z",
            "newest" to "2026-11-20T12:00:05.500000002Z",
        )
        stamps.forEach { (id, timestamp) ->
            repository.save(Proposition.draft("team", "alpha $id", 0.9).withId(id)
                .withCreated(Instant.parse(timestamp)).toProposition())
        }
        val scope = ScopedReadSpec.of(ContextReadScope.ofValues("team"))
        val reads = repository.scopedReads()
        val budget = ReadBudget.ofRows(3)
        assertEquals("newest", reads.query(
            scope, ReadQuery.of(ReadCriteria.empty()).orderedByCreated().withLimit(1), budget,
        ).single().id)
        assertEquals(listOf("newest", "middle", "older"), reads.matchKeywords(
            scope, ReadKeywordQuery.forTokens("alpha").withOrder(ReadOrder.CREATED_DESC), budget,
        ).map { it.id })
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
