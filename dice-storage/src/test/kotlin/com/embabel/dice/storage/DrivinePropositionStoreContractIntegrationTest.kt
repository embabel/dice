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
import com.embabel.dice.agent.ScopedMemory
import com.embabel.dice.conformance.AbstractPropositionStoreContractTest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStore
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
