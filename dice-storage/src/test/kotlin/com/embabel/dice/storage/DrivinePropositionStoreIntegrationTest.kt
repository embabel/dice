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
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.proposition.DecaySweepConfig
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.drivine.connection.DataSourceMap
import org.drivine.manager.CascadeType
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.summary.Plan
import org.springframework.aop.framework.Advised
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.interceptor.TransactionInterceptor
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the graph storage stack against a Neo4j testcontainer (provided by Drivine's
 * test support). Not `@Transactional`: dedup commits via its own [org.springframework.transaction.support.TransactionTemplate],
 * so isolation is by explicit `clearAll()` per test rather than rollback.
 */
@SpringBootTest(classes = [TestApplication::class, TransactionProxyTestConfiguration::class])
class DrivinePropositionStoreIntegrationTest {

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var chunkHistoryStore: DrivineChunkHistoryStore

    @Autowired
    private lateinit var decayManager: GraphDecayManager

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var graphObjectManager: GraphObjectManager

    @Autowired
    private lateinit var embeddingService: EmbeddingService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var dataSourceMap: DataSourceMap

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (s:Source) DETACH DELETE s"))
    }

    private fun prop(
        text: String,
        context: String = "ctx",
        confidence: Double = 0.9,
        decay: Double = 0.0,
        contentRevised: Instant = Instant.now(),
        // Defaults to contentRevised so "revised" queries (keyed on lastTouched, the later of the two
        // clocks) see the intended time instead of an always-now metadata clock.
        metadataRevised: Instant = contentRevised,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        entityId: String? = null,
        pinned: Boolean = false,
        level: Int = 0,
        sourceIds: List<String> = emptyList(),
        grounding: List<String> = emptyList(),
        reinforceCount: Int = 0,
        metadata: Map<String, Any> = emptyMap(),
        temporal: TemporalMetadata? = null,
        provenance: List<ProvenanceEntry> = emptyList(),
        // Defaults to contentRevised, matching Proposition's own default — an untouched fact hasn't
        // been accessed since its content was set.
        lastAccessed: Instant = contentRevised,
    ): Proposition = Proposition(
        contextId = ContextId(context),
        text = text,
        mentions = entityId?.let { listOf(EntityMention("span", "Person", it, MentionRole.SUBJECT)) } ?: emptyList(),
        confidence = confidence,
        decay = decay,
        contentRevised = contentRevised,
        metadataRevised = metadataRevised,
        status = status,
        pinned = pinned,
        level = level,
        sourceIds = sourceIds,
        grounding = grounding,
        reinforceCount = reinforceCount,
        metadata = metadata,
        temporal = temporal,
        provenanceEntries = provenance,
        lastAccessed = lastAccessed,
    )

    /** A proposition mentioning several entities (the single-`entityId` [prop] can't express this). */
    private fun propWithEntities(text: String, entityIds: List<String>, context: String = "ctx"): Proposition =
        Proposition(
            contextId = ContextId(context),
            text = text,
            mentions = entityIds.map { EntityMention("span-$it", "Person", it, MentionRole.SUBJECT) },
            confidence = 0.9,
        )

    private fun nodeCount(label: String): Long = persistenceManager.getOne(
        QuerySpecification.withStatement("MATCH (n:$label) RETURN count(n) AS c").transform(Long::class.java),
    )

    private fun revisionEvidence(
        locator: UriLocator,
        revision: String?,
        chunkId: String = "chunk",
        contentHash: String = "hash",
    ): ProvenanceEntry =
        ProvenanceEntry(
            locator = locator,
            chunkId = chunkId,
            startOffset = 1,
            endOffset = 4,
            contentHash = contentHash,
            sourceRevision = revision,
        )

    private fun edgeCount(propositionId: String): Long =
        persistenceManager.getOne(
            QuerySpecification
                .withStatement(
                    "MATCH (:Proposition {id: \$propositionId})-[r:DERIVED_FROM]->() RETURN count(r) AS c",
                )
                .bind(mapOf("propositionId" to propositionId))
                .transform(Long::class.java),
        )

    private fun seedLegacyEdge(propositionId: String, entry: ProvenanceEntry) {
        val locator = entry.locator as UriLocator
        persistenceManager.execute(
            QuerySpecification
                .withStatement(
                    """
                    MATCH (p:Proposition {id: ${'$'}propositionId})
                    MERGE (s:Source {key: ${'$'}sourceKey})
                    SET s.kind = 'uri', s.uri = ${'$'}sourceUri, s.display = ${'$'}sourceDisplay
                    CREATE (p)-[r:DERIVED_FROM]->(s)
                    SET r.chunkId = ${'$'}chunkId,
                        r.startOffset = ${'$'}startOffset,
                        r.endOffset = ${'$'}endOffset,
                        r.contentHash = ${'$'}contentHash
                    """.trimIndent(),
                )
                .bind(
                    mapOf(
                        "propositionId" to propositionId,
                        "sourceKey" to locator.key(),
                        "sourceUri" to locator.uri,
                        "sourceDisplay" to locator.display,
                        "chunkId" to entry.chunkId,
                        "startOffset" to entry.startOffset,
                        "endOffset" to entry.endOffset,
                        "contentHash" to entry.contentHash,
                    ),
                ),
        )
    }

    /**
     * A second repository target with the same transaction advisor as the actual Spring bean.
     * Separate targets model two application instances (and therefore two lock stripes), while both
     * calls still pass through the production annotation-driven transaction interceptor.
     */
    private fun newTransactionProxiedRepository(): DrivinePropositionRepository {
        val actualBean = repository as Advised
        val transactionAdvisor = actualBean.advisors.single { it.advice is TransactionInterceptor }
        val proxyFactory = ProxyFactory(
            DrivinePropositionRepository(
                graphObjectManager,
                persistenceManager,
                embeddingService,
                transactionManager,
            ),
        )
        proxyFactory.setProxyTargetClass(true)
        proxyFactory.addAdvisor(transactionAdvisor)
        return proxyFactory.proxy as DrivinePropositionRepository
    }

    private fun explainSourceQuery(
        name: String,
        statement: String,
        parameters: Map<String, Any>,
    ): String {
        val dataSource = dataSourceMap.dataSources.getValue("neo")
        val uri = "${dataSource.protocol ?: "bolt"}://${dataSource.host}:${dataSource.port ?: 7687}"
        val auth = dataSource.userName?.let { AuthTokens.basic(it, dataSource.password.orEmpty()) }
            ?: AuthTokens.none()
        return GraphDatabase.driver(uri, auth).use { driver ->
            val sessionConfig = dataSource.databaseName?.let(SessionConfig::forDatabase)
                ?: SessionConfig.defaultConfig()
            driver.session(sessionConfig).use { session ->
                val plan = session.run("EXPLAIN\n$statement", parameters).consume().plan()
                renderPlan(plan).also { println("SOURCE_QUERY_EXPLAIN[$name]\n$it") }
            }
        }
    }

    private fun renderPlan(plan: Plan, depth: Int = 0): String = buildString {
        append("  ".repeat(depth))
        append(plan.operatorType())
        if (plan.arguments().isNotEmpty()) {
            append(' ')
            append(plan.arguments())
        }
        appendLine()
        plan.children().forEach { append(renderPlan(it, depth + 1)) }
    }.trimEnd()

    @Test
    fun `save round-trips all persisted fields`() {
        val saved = repository.save(
            prop(
                text = "Rod visited Sydney",
                entityId = "rod",
                level = 2,
                sourceIds = listOf("p0", "p1"),
                reinforceCount = 5,
                metadata = mapOf("source" to "wiki"),
                temporal = TemporalMetadata(validFrom = Instant.parse("2020-01-01T00:00:00Z")),
                provenance = listOf(ProvenanceEntry(locator = UriLocator("https://example.com/doc"), chunkId = "ck1")),
            ),
        )

        val found = repository.findById(saved.id)
        assertNotNull(found)
        found!!
        assertEquals("Rod visited Sydney", found.text)
        assertEquals(2, found.level)
        assertEquals(5, found.reinforceCount)
        assertEquals("rod", found.mentions.single().resolvedId)
        assertEquals("wiki", found.metadata["source"])
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), found.temporal?.validFrom)
        assertEquals(listOf("p0", "p1"), found.sourceIds)
        val provenance = found.provenanceEntries.single()
        assertEquals("ck1", provenance.chunkId)
        assertEquals("https://example.com/doc", (provenance.locator as UriLocator).uri)
    }

    @Test
    fun `save dedups identical text in the same context`() {
        repository.save(prop("Rod visited Sydney"))
        repository.save(prop("Rod visited Sydney"))
        assertEquals(1, repository.count())
    }

    @Test
    fun `query pushes filters incl entity quantifier`() {
        repository.save(prop("a", entityId = "e1", status = PropositionStatus.ACTIVE))
        repository.save(prop("b", entityId = "e2", status = PropositionStatus.ACTIVE))
        repository.save(prop("c", entityId = "e1", status = PropositionStatus.SUPERSEDED))

        val activeMentioningE1 = repository.query(
            PropositionQuery(entityId = "e1", statuses = setOf(PropositionStatus.ACTIVE)),
        )
        assertEquals(listOf("a"), activeMentioningE1.map { it.text })
    }

    @Test
    fun `vector search ranks the exact-text match first`() {
        repository.save(prop("the cat sat on the mat"))
        repository.save(prop("quantum chromodynamics"))
        val target = repository.save(prop("a totally unrelated sentence"))

        val hits = repository.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "a totally unrelated sentence", topK = 10, similarityThreshold = 0.0),
        )
        assertEquals(target.id, hits.first().match.id)
        assertTrue(hits.first().score > 0.99, "exact text should be ~1.0, was ${hits.first().score}")
    }

    @Test
    fun `findClusters groups identical-embedding propositions in one statement`() {
        // identical text in different contexts → not deduped → identical embeddings → cluster
        repository.save(prop("shared fact", context = "ctx-a"))
        repository.save(prop("shared fact", context = "ctx-b"))
        repository.save(prop("a lonely distinct fact", context = "ctx-a"))

        val clusters = repository.findClusters(similarityThreshold = 0.95, topK = 10, query = PropositionQuery())
        assertEquals(1, clusters.size)
        assertEquals(1, clusters.single().similar.size)
    }

    @Test
    fun `findClusters reports raw cosine, not Neo4j's normalized score`() {
        // Score must be raw cosine (what the in-memory store returns), but a COSINE index reports
        // (1 + cosine) / 2 — if that leaked through, a cosine-0.5 pair would read as 0.75 and the
        // dedup scorer would over-merge distinct facts. Cosine 0.8 also keeps the pair each other's
        // nearest neighbour, so the index's global top-k can't truncate the edge.
        val v1 = FloatArray(embeddingService.dimensions).also { it[0] = 1f }
        val v2 = FloatArray(embeddingService.dimensions).also { it[0] = 0.8f; it[1] = 0.6f }
        seedRawNodeWithEmbedding(prop("alpha fact", context = "ctx-cos"), v1.toList())
        seedRawNodeWithEmbedding(prop("beta fact", context = "ctx-cos"), v2.toList())

        // Scope to this context so only the two seeded props are candidates — no leftover from a
        // sibling test can outscore the edge.
        val edge = repository.findClusters(
            similarityThreshold = 0.0,
            topK = 10,
            query = PropositionQuery.forContextId(ContextId("ctx-cos")),
        )
            .flatMap { it.similar }
            .maxByOrNull { it.score }
        assertNotNull(edge, "expected the two seeded propositions to cluster")
        assertEquals(
            0.8, edge!!.score, 0.02,
            "score must be raw cosine (0.8); Neo4j's normalized (1 + cos) / 2 = 0.9 must not leak through",
        )
    }

    @Test
    fun `findSimilarWithScores reports and gates on raw cosine, same scale as findClusters`() {
        // findSimilarWithScores always re-embeds the query text, so we can't seed the query vector.
        // Build the sibling as a rotation of the anchor's stored embedding: cosine 0.8 by
        // construction, whatever the embedder's coordinate system.
        val anchor = repository.save(prop("anchor fact", context = "ctx-sim-cos"))
        val anchorVector = embeddingService.embed(anchor.text).toList()
        val sibling = prop("sibling fact", context = "ctx-sim-cos")
        seedRawNodeWithEmbedding(sibling, rotate(anchorVector, cosTheta = 0.8, seedText = "orthogonal helper text"))

        val request = { threshold: Double ->
            TextSimilaritySearchRequest(query = anchor.text, topK = 10, similarityThreshold = threshold)
        }

        val hit = repository.findSimilarWithScores(request(0.0)).firstOrNull { it.match.id == sibling.id }
        assertNotNull(hit, "expected the constructed sibling to be found")
        assertEquals(
            0.8, hit!!.score, 0.02,
            "score must be raw cosine (0.8); Neo4j's normalized (1 + cos) / 2 = 0.9 must not leak through",
        )

        // A threshold just above the true cosine excludes the pair, even though the engine's own
        // normalized score (0.9) would still clear it if the threshold leaked through unconverted.
        val excluded = repository.findSimilarWithScores(request(0.85))
        assertTrue(
            excluded.none { it.match.id == sibling.id },
            "threshold must gate on raw cosine, not the engine's normalized score",
        )
        val included = repository.findSimilarWithScores(request(0.75))
        assertTrue(included.any { it.match.id == sibling.id }, "threshold at 0.75 should admit a true cosine-0.8 pair")

        // Same scale on the query-filtered overload.
        val filteredHit = repository.findSimilarWithScores(request(0.0), PropositionQuery.forContextId(ContextId("ctx-sim-cos")))
            .firstOrNull { it.match.id == sibling.id }
        assertNotNull(filteredHit, "expected the sibling to be found via the query-filtered overload")
        assertEquals(0.8, filteredHit!!.score, 0.02, "the query-filtered overload must report raw cosine too")
        val filteredExcluded = repository.findSimilarWithScores(request(0.85), PropositionQuery.forContextId(ContextId("ctx-sim-cos")))
        assertTrue(
            filteredExcluded.none { it.match.id == sibling.id },
            "the query-filtered overload must gate on raw cosine too",
        )
    }

    @Test
    fun `provenance sources are shared across propositions`() {
        val sharedSource = listOf(ProvenanceEntry(locator = UriLocator("https://example.com/shared")))
        repository.save(prop("fact one", context = "ctx-a", provenance = sharedSource))
        repository.save(prop("fact two", context = "ctx-b", provenance = sharedSource))

        // one :Source node, shared by both DERIVED_FROM edges (MERGE by locator key)
        val sourceCount = persistenceManager.getOne(
            QuerySpecification.withStatement("MATCH (s:Source) RETURN count(s) AS c").transform(Long::class.java),
        )
        assertEquals(1L, sourceCount)
    }

    @Test
    fun `parallel source revisions persist as distinct edges and hydrate through every full read`() {
        val locator = UriLocator("https://example.com/revisioned")
        val revisionOne = revisionEvidence(locator, "r1")
        val revisionTwo = revisionOne.copy(sourceRevision = "r2")
        val saved = repository.save(prop("revisioned fact", provenance = listOf(revisionOne, revisionTwo)))

        val sourceCount = persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (s:Source {key: \$sourceKey}) RETURN count(s) AS c")
                .bind(mapOf("sourceKey" to locator.key()))
                .transform(Long::class.java),
        )
        val edgeCount = persistenceManager.getOne(
            QuerySpecification
                .withStatement(
                    "MATCH (:Proposition {id: \$id})-[r:DERIVED_FROM]->(:Source {key: \$sourceKey}) " +
                        "RETURN count(r) AS c",
                )
                .bind(mapOf("id" to saved.id, "sourceKey" to locator.key()))
                .transform(Long::class.java),
        )

        assertEquals(1L, sourceCount, "parallel revisions share one Source node")
        assertEquals(2L, edgeCount, "parallel revisions require two DERIVED_FROM relationships")
        val expected = setOf(revisionOne, revisionTwo)
        assertEquals(expected, repository.findById(saved.id)!!.provenanceEntries.toSet())
        assertEquals(
            expected,
            repository.query(PropositionQuery.forContextId(ContextId("ctx")), withProvenance = true)
                .single { it.id == saved.id }.provenanceEntries.toSet(),
        )
        assertEquals(
            expected,
            repository.findAll(withProvenance = true).single { it.id == saved.id }.provenanceEntries.toSet(),
        )
    }

    @Test
    fun `repeated and concurrent writes of one revision remain one relationship`() {
        val locator = UriLocator("https://example.com/idempotent")
        val revisionOne = revisionEvidence(locator, "r1")
        val saved = repository.save(prop("idempotent evidence", provenance = listOf(revisionOne)))
        repository.save(saved)

        val firstRepository = DrivinePropositionRepository(
            graphObjectManager, persistenceManager, embeddingService, transactionManager,
        )
        val secondRepository = DrivinePropositionRepository(
            graphObjectManager, persistenceManager, embeddingService, transactionManager,
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf(firstRepository, secondRepository).map { candidate ->
                executor.submit {
                    start.await(10, TimeUnit.SECONDS)
                    candidate.save(saved)
                }
            }
            start.countDown()
            writes.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1L, edgeCount(saved.id))
        assertEquals(listOf(revisionOne), repository.findById(saved.id)!!.provenanceEntries)
    }

    @Test
    fun `ordinary exact-text dedup unions incoming source revisions into the winner`() {
        val locator = UriLocator("https://example.com/ordinary-dedup")
        val revisionOne = revisionEvidence(locator, "r1")
        val revisionTwo = revisionEvidence(locator, "r2")
        val winner = repository.save(prop("same extracted fact", provenance = listOf(revisionOne)))

        val deduplicated = repository.save(prop("same extracted fact", provenance = listOf(revisionTwo)))

        assertEquals(winner.id, deduplicated.id)
        assertEquals(1, repository.count())
        assertEquals(setOf(revisionOne, revisionTwo), repository.findById(winner.id)!!.provenanceEntries.toSet())
        assertEquals(2L, edgeCount(winner.id))
    }

    @Test
    fun `Spring proxied cross-instance uniqueness recovery commits evidence and is retry safe`() {
        val locator = UriLocator("https://example.com/race-dedup")
        val revisionOne = revisionEvidence(locator, "r1")
        val revisionTwo = revisionEvidence(locator, "r2")
        val first = prop("cross-instance fact", provenance = listOf(revisionOne))
        val second = prop("cross-instance fact", provenance = listOf(revisionTwo))
        val barrier = CountDownLatch(2)
        val awaitBothInserts = {
            barrier.countDown()
            assertTrue(barrier.await(10, TimeUnit.SECONDS), "both repositories must reach the insert race")
        }
        val firstRepository = repository
        val secondRepository = newTransactionProxiedRepository()
        assertTrue(AopUtils.isAopProxy(firstRepository), "the primary writer must be the actual Spring proxy")
        assertTrue(AopUtils.isAopProxy(secondRepository), "the sibling writer must carry the transaction advisor")
        firstRepository.beforeDedupInsert = awaitBothInserts
        secondRepository.beforeDedupInsert = awaitBothInserts
        val executor = Executors.newFixedThreadPool(2)
        try {
            val winners = listOf(
                executor.submit<Proposition> { firstRepository.save(first) },
                executor.submit<Proposition> { secondRepository.save(second) },
            ).map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, winners.map { it.id }.toSet().size, "both writers must return the database winner")
            val winnerId = winners.map { it.id }.distinct().single()
            assertEquals(setOf(revisionOne, revisionTwo), repository.findById(winnerId)!!.provenanceEntries.toSet())
            assertEquals(2L, edgeCount(winnerId), "the losing proxy's recovery transaction must commit evidence")

            firstRepository.beforeDedupInsert = null
            secondRepository.beforeDedupInsert = null
            secondRepository.save(second)
            assertEquals(2L, edgeCount(winnerId), "retrying recovered evidence must remain idempotent")
        } finally {
            firstRepository.beforeDedupInsert = null
            secondRepository.beforeDedupInsert = null
            executor.shutdownNow()
        }
    }

    @Test
    fun `legacy unkeyed revisionless evidence is adopted exactly while revisioned writes stay separate`() {
        val locator = UriLocator("https://example.com/legacy")
        val revisionless = revisionEvidence(locator, null)
        val revisionOne = revisionEvidence(locator, "r1")
        val adoptTarget = repository.save(prop("adopt legacy"))
        seedLegacyEdge(adoptTarget.id, revisionless)

        assertEquals(listOf(revisionless), repository.findById(adoptTarget.id)!!.provenanceEntries)
        repository.save(adoptTarget.withProvenanceEntries(listOf(revisionless)))
        assertEquals(1L, edgeCount(adoptTarget.id), "the exact revisionless legacy edge is adopted in place")
        val adoptedKeys = persistenceManager.getOne(
            QuerySpecification
                .withStatement(
                    "MATCH (:Proposition {id: \$id})-[r:DERIVED_FROM]->() " +
                        "RETURN count(r.entryKey) AS c",
                )
                .bind(mapOf("id" to adoptTarget.id))
                .transform(Long::class.java),
        )
        assertEquals(1L, adoptedKeys)

        val revisionedTarget = repository.save(prop("keep legacy separate"))
        seedLegacyEdge(revisionedTarget.id, revisionless)
        repository.save(revisionedTarget.withProvenanceEntries(listOf(revisionOne)))

        assertEquals(2L, edgeCount(revisionedTarget.id), "a revisioned write must not adopt an unkeyed edge")
        assertEquals(
            setOf(revisionless, revisionOne),
            repository.findById(revisionedTarget.id)!!.provenanceEntries.toSet(),
        )
    }

    @Test
    fun `authoritative provenance replace removes omitted edges and only globally orphaned sources`() {
        val sharedLocator = UriLocator("https://example.com/shared-replace")
        val exclusiveLocator = UriLocator("https://example.com/exclusive-replace")
        val keep = revisionEvidence(sharedLocator, "r1", chunkId = "keep")
        val omittedParallel = revisionEvidence(sharedLocator, "r2", chunkId = "omit-parallel")
        val omittedExclusive = revisionEvidence(exclusiveLocator, "r1", chunkId = "omit-exclusive")
        val subject = repository.save(
            prop("replace subject", provenance = listOf(keep, omittedParallel, omittedExclusive)),
        )
        val other = repository.save(
            prop("shared source remains", provenance = listOf(revisionEvidence(sharedLocator, "other"))),
        )

        repository.setProvenance(subject.id, listOf(keep))

        assertEquals(listOf(keep), repository.findById(subject.id)!!.provenanceEntries)
        assertEquals(1L, edgeCount(subject.id))
        assertEquals(1L, edgeCount(other.id), "replace must not disturb another proposition's edge")
        val sourceKeys = persistenceManager.query(
            QuerySpecification.withStatement("MATCH (s:Source) RETURN s.key AS key"),
        ).toSet()
        assertEquals(setOf(sharedLocator.key()), sourceKeys, "only the globally orphaned source is pruned")
    }

    @Test
    fun `source queries push down by context and distinguish exact and revisionless evidence`() {
        val locator = UriLocator("https://example.com/query-source")
        val revisionless = repository.save(
            prop("revisionless ctx-a", context = "ctx-a", provenance = listOf(revisionEvidence(locator, null))),
        )
        val revisionOne = repository.save(
            prop("r1 ctx-a", context = "ctx-a", provenance = listOf(revisionEvidence(locator, "r1"))),
        )
        val revisionTwo = repository.save(
            prop("r2 ctx-a", context = "ctx-a", provenance = listOf(revisionEvidence(locator, "r2"))),
        )
        repository.save(
            prop("r1 ctx-b", context = "ctx-b", provenance = listOf(revisionEvidence(locator, "r1"))),
        )
        val context = ContextId("ctx-a")

        assertEquals(
            setOf(revisionless.id, revisionOne.id, revisionTwo.id),
            repository.findBySourceKey(context, locator.key()).map { it.id }.toSet(),
        )
        assertEquals(
            listOf(revisionOne.id),
            repository.findBySourceRevision(context, SourceRevisionRef(locator.key(), "r1")).map { it.id },
        )
        assertEquals(
            listOf(revisionless.id),
            repository.findRevisionlessBySourceLocator(context, locator).map { it.id },
        )
    }

    @Test
    fun `source query shapes have parameterized index-backed Neo4j plans`() {
        val locator = UriLocator("https://example.com/explain-source")
        repository.save(
            prop(
                "explain revisionless",
                context = "ctx-explain",
                provenance = listOf(revisionEvidence(locator, null)),
            ),
        )
        repository.save(
            prop(
                "explain r1",
                context = "ctx-explain",
                provenance = listOf(revisionEvidence(locator, "r1")),
            ),
        )
        val commonParameters = mapOf<String, Any>(
            "contextId" to "ctx-explain",
            "sourceKey" to locator.key(),
        )
        val plans = mapOf(
            "source-key" to explainSourceQuery(
                "source-key",
                """
                MATCH (p:Proposition {contextId: ${'$'}contextId})-[r:DERIVED_FROM]->(s:Source {key: ${'$'}sourceKey})
                RETURN DISTINCT p.id AS id
                """.trimIndent(),
                commonParameters,
            ),
            "source-revision" to explainSourceQuery(
                "source-revision",
                """
                MATCH (p:Proposition {contextId: ${'$'}contextId})-[r:DERIVED_FROM]->(s:Source {key: ${'$'}sourceKey})
                WHERE r.sourceRevision = ${'$'}sourceRevision
                RETURN DISTINCT p.id AS id
                """.trimIndent(),
                commonParameters + ("sourceRevision" to "r1"),
            ),
            "revisionless-source" to explainSourceQuery(
                "revisionless-source",
                """
                MATCH (p:Proposition {contextId: ${'$'}contextId})-[r:DERIVED_FROM]->(s:Source {key: ${'$'}sourceKey})
                WHERE r.sourceRevision IS NULL
                RETURN DISTINCT p.id AS id
                """.trimIndent(),
                commonParameters,
            ),
        )

        plans.forEach { (name, plan) ->
            assertTrue(plan.isNotBlank(), "$name EXPLAIN must return a plan")
            assertTrue(
                plan.contains("IndexSeek"),
                "$name should use an existing node index rather than requiring relationship DDL:\n$plan",
            )
        }
    }

    @Test
    fun `chunk history records, dedups, and bookmarks`() {
        val contextId = ContextId("chunk-ctx")
        assertFalse(chunkHistoryStore.isProcessed(HashKey(contextId, "hash-1")))
        chunkHistoryStore.recordProcessed(
            ProcessedChunkRecord(
                bookmarkKey = BookmarkKey(contextId, "src-1"),
                hashKey = HashKey(contextId, "hash-1"),
                startIndex = 0,
                endIndex = 100,
                processedAt = Instant.now(),
            ),
        )
        chunkHistoryStore.recordProcessed(
            ProcessedChunkRecord(
                bookmarkKey = BookmarkKey(contextId, "src-1"),
                hashKey = HashKey(contextId, "hash-2"),
                startIndex = 100,
                endIndex = 250,
                processedAt = Instant.now().plusSeconds(1),
            ),
        )
        assertTrue(chunkHistoryStore.isProcessed(HashKey(contextId, "hash-1")))
        assertEquals(250, chunkHistoryStore.getLastBookmark(BookmarkKey(contextId, "src-1"))?.endIndex)
    }

    @Test
    fun `lifecycle sweep moves a decayed ACTIVE proposition to STALE`() {
        // high decay + a very old revision → effective confidence ~0 → below the staleness threshold
        val stale = repository.save(
            prop("ancient fact", decay = 0.9, contentRevised = Instant.now().minus(Duration.ofDays(3650))),
        )
        val fresh = repository.save(prop("current fact", decay = 0.0))

        val result = decayManager.sweepAll(DecaySweepConfig())
        assertTrue(result is com.embabel.dice.proposition.DecaySweepResult.Swept)

        assertEquals(PropositionStatus.STALE, repository.findById(stale.id)?.status)
        assertEquals(PropositionStatus.ACTIVE, repository.findById(fresh.id)?.status)
    }

    @Test
    fun `materializeAll runs and confidence ordering works`() {
        repository.save(prop("high", confidence = 0.9))
        repository.save(prop("low", confidence = 0.2))
        decayManager.materializeAll()

        val ordered = repository.query(
            PropositionQuery(orderBy = PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC),
        )
        assertEquals(listOf("high", "low"), ordered.map { it.text })
    }

    @Test
    fun `findAbstractionsOf pushes down and finds propositions citing a source`() {
        val source = repository.save(prop("raw observation"))
        val abstractionA = repository.save(prop("abstraction A", level = 1, sourceIds = listOf(source.id)))
        val abstractionB = repository.save(prop("abstraction B", level = 1, sourceIds = listOf(source.id, "other")))
        repository.save(prop("unrelated", level = 1, sourceIds = listOf("other")))

        val found = repository.findAbstractionsOf(source.id)

        assertEquals(setOf(abstractionA.id, abstractionB.id), found.map { it.id }.toSet())
    }

    /**
     * The Cypher decay sweep must reproduce [Proposition.effectiveConfidenceAt] for every branch of the
     * formula: fresh, decayed, retracted, and the three dated cases (in a closed window, open-ended, and
     * out of window). The materialised column is asserted equal to the Kotlin oracle within a tiny
     * epsilon. Anchors sit half a day off a day boundary so the small gap between the sweep's clock and
     * the oracle's can't change the floored day count.
     */
    @Test
    fun `materializeAll matches effectiveConfidenceAt across every decay branch`() {
        fun daysAgo(days: Long): Instant = Instant.now().minus(Duration.ofDays(days)).minus(Duration.ofHours(12))

        val seeds = listOf(
            repository.save(prop("fresh", confidence = 0.9, decay = 0.1)),
            repository.save(prop("decayed", confidence = 0.8, decay = 0.05, contentRevised = daysAgo(10))),
            repository.save(
                prop("retracted", confidence = 0.9, decay = 0.1,
                    temporal = TemporalMetadata(invalidatedAt = Instant.now().minus(Duration.ofDays(1)))),
            ),
            repository.save(
                prop("dated-closed", confidence = 0.7, decay = 0.2,
                    temporal = TemporalMetadata(validFrom = daysAgo(100), validTo = Instant.now().plus(Duration.ofDays(100)))),
            ),
            repository.save(
                prop("dated-open", confidence = 0.85, decay = 0.03,
                    temporal = TemporalMetadata(validFrom = daysAgo(20))),
            ),
            repository.save(
                prop("dated-future", confidence = 0.6, decay = 0.1,
                    temporal = TemporalMetadata(validFrom = Instant.now().plus(Duration.ofDays(5)))),
            ),
            repository.save(
                prop("dated-expired", confidence = 0.6, decay = 0.1,
                    temporal = TemporalMetadata(validFrom = daysAgo(50), validTo = Instant.now().minus(Duration.ofDays(10)))),
            ),
        )

        // Corrupt the compute-on-write seed so the assertion proves the sweep recomputed the column.
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (p:Proposition) SET p.effectiveConfidence = -999.0"))
        decayManager.materializeAll()

        seeds.forEach { seed ->
            val expected = seed.effectiveConfidenceAt(Instant.now())
            val actual = storedEffectiveConfidence(seed.id)
            assertNotNull(actual, "no effectiveConfidence materialised for '${seed.text}'")
            assertTrue(
                kotlin.math.abs(expected - actual!!) < 1e-6,
                "decay mismatch for '${seed.text}': cypher=$actual kotlin=$expected",
            )
        }
    }

    /**
     * A node written before the `decay`/`contentRevised` columns existed comes back with them absent.
     * The sweep must coalesce to the Kotlin defaults (decay = 0.0, contentRevised = created) rather
     * than let the arithmetic go NULL: `SET p.effectiveConfidence = NULL` would erase the value and
     * hide the node from every `minEffectiveConfidence`/`belowEffectiveConfidence` filter (NULL fails
     * every comparison).
     */
    @Test
    fun `materializeAll defaults a legacy node missing decay and contentRevised instead of nulling the column`() {
        val legacy = repository.save(
            prop("legacy fact", confidence = 0.7, decay = 0.4, contentRevised = Instant.now().minus(Duration.ofDays(100))),
        )
        // Strip the columns to mimic a node persisted before they existed.
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (p:Proposition {id: \$id}) REMOVE p.decay, p.contentRevised")
                .bind(mapOf("id" to legacy.id)),
        )
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (p:Proposition) SET p.effectiveConfidence = -999.0"))

        decayManager.materializeAll()

        val stored = storedEffectiveConfidence(legacy.id)
        assertNotNull(stored, "the sweep must not null out a legacy node's effectiveConfidence")
        // With decay defaulting to 0.0 there is no decay, so effective confidence equals raw confidence —
        // exactly effectiveConfidenceAt(now) for a proposition defaulted to decay=0.0, contentRevised=created.
        val expected = legacy.copy(decay = 0.0, contentRevised = legacy.created).effectiveConfidenceAt(Instant.now())
        assertTrue(
            kotlin.math.abs(expected - stored!!) < 1e-6,
            "legacy-node decay mismatch: cypher=$stored kotlin=$expected",
        )
        assertEquals(0.7, stored, 1e-6, "decay=0.0 default means the column equals raw confidence")
    }

    /**
     * The store holds temporal fields inconsistently: an older write path stored them as ISO-8601
     * strings rather than native Neo4j datetimes. The sweep normalises every temporal read through
     * `datetime(toString(x))` so `.epochSeconds` and the window comparisons work either way. Before
     * that, a string-typed `contentRevised` threw "expected a map but was String" and aborted the
     * whole sweep (caught in a live production database, not synthetic data).
     */
    @Test
    fun `materializeAll tolerates temporal properties stored as ISO strings`() {
        val p = repository.save(
            prop("string-dated fact", confidence = 0.8, decay = 0.5, contentRevised = Instant.now().minus(Duration.ofDays(30))),
        )
        // Rewrite the temporals as strings to mimic the older write path.
        persistenceManager.execute(
            QuerySpecification
                .withStatement(
                    "MATCH (p:Proposition {id: \$id}) " +
                        "SET p.contentRevised = toString(p.contentRevised), p.created = toString(p.created)",
                )
                .bind(mapOf("id" to p.id)),
        )
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (p:Proposition) SET p.effectiveConfidence = -999.0"))

        // Must not throw, and must compute the same value as the Kotlin definition.
        decayManager.materializeAll()

        val stored = storedEffectiveConfidence(p.id)
        assertNotNull(stored, "string-typed temporal must not break the sweep")
        val expected = p.effectiveConfidenceAt(Instant.now())
        assertTrue(
            kotlin.math.abs(expected - stored!!) < 1e-6,
            "string-temporal decay mismatch: cypher=$stored kotlin=$expected",
        )
    }

    @Test
    fun `materialize(context) only refreshes its own context`() {
        val a = repository.save(prop("in A", context = "ctx-a", confidence = 0.9))
        val b = repository.save(prop("in B", context = "ctx-b", confidence = 0.9))
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (p:Proposition) SET p.effectiveConfidence = -999.0"))

        decayManager.materialize(ContextId("ctx-a"))

        assertTrue(kotlin.math.abs(storedEffectiveConfidence(a.id)!! - a.effectiveConfidenceAt(Instant.now())) < 1e-6)
        assertEquals(-999.0, storedEffectiveConfidence(b.id), "ctx-b must be untouched")
    }

    private fun storedEffectiveConfidence(id: String): Double? = persistenceManager.maybeGetOne(
        QuerySpecification.withStatement("MATCH (p:Proposition {id: \$id}) RETURN p.effectiveConfidence AS ec")
            .bind(mapOf("id" to id)).transform(Double::class.java),
    )

    /** Re-saving a proposition loaded via a lean path (no provenance projected) must not wipe its provenance. */
    @Test
    fun `re-saving a proposition loaded without provenance preserves its provenance`() {
        val saved = repository.save(
            prop(
                "durable fact",
                provenance = listOf(ProvenanceEntry(locator = UriLocator("https://example.com/src"), chunkId = "ck1")),
            ),
        )

        // query() projects the lean view, so provenance is absent on the loaded copy.
        val lean = repository.query(PropositionQuery(contextId = ContextId("ctx"))).single { it.id == saved.id }
        assertTrue(lean.provenanceEntries.isEmpty(), "precondition: the lean load drops provenance")

        // The decay sweep re-saves lean-loaded propositions on a status transition.
        repository.save(lean.withStatus(PropositionStatus.STALE))

        val reloaded = repository.findById(saved.id)
        assertNotNull(reloaded)
        assertEquals(PropositionStatus.STALE, reloaded!!.status)
        assertEquals(1, reloaded.provenanceEntries.size, "a lean re-save must not delete provenance")
        assertEquals("ck1", reloaded.provenanceEntries.single().chunkId)
    }

    /** The append-only save default — saving a lean-loaded copy with a new entry must add, not replace. */
    @Test
    fun `saving a lean-loaded proposition with a new provenance entry appends rather than replaces`() {
        val saved = repository.save(
            prop(
                "evidenced fact",
                provenance = listOf(
                    ProvenanceEntry(locator = UriLocator("https://example.com/a")),
                    ProvenanceEntry(locator = UriLocator("https://example.com/b")),
                ),
            ),
        )
        val lean = repository.query(PropositionQuery(contextId = ContextId("ctx"))).single { it.id == saved.id }
        assertTrue(lean.provenanceEntries.isEmpty(), "precondition: lean load drops provenance")

        repository.save(lean.withProvenanceEntries(listOf(ProvenanceEntry(locator = UriLocator("https://example.com/c")))))

        val uris = repository.provenanceOf(saved.id).map { (it.locator as UriLocator).uri }.toSet()
        assertEquals(
            setOf("https://example.com/a", "https://example.com/b", "https://example.com/c"),
            uris,
            "the all-in-one save must append provenance, not drop entries it didn't load",
        )
    }

    /** Provenance management: addProvenance is additive and idempotent by shared source. */
    @Test
    fun `addProvenance appends and is idempotent`() {
        val saved = repository.save(prop("fact"))
        val a = ProvenanceEntry(locator = UriLocator("https://example.com/a"))
        val b = ProvenanceEntry(locator = UriLocator("https://example.com/b"))

        repository.addProvenance(saved.id, listOf(a))
        repository.addProvenance(saved.id, listOf(b))
        repository.addProvenance(saved.id, listOf(a)) // repeat — must not duplicate

        val uris = repository.provenanceOf(saved.id).map { (it.locator as UriLocator).uri }.toSet()
        assertEquals(setOf("https://example.com/a", "https://example.com/b"), uris)

        val sourceCount = persistenceManager.getOne(
            QuerySpecification.withStatement("MATCH (s:Source) RETURN count(s) AS c").transform(Long::class.java),
        )
        assertEquals(2L, sourceCount, "repeating a source must not create a duplicate :Source node")
    }

    /** Provenance management: setProvenance authoritatively replaces and orphans the dropped source. */
    @Test
    fun `setProvenance replaces authoritatively and removes orphaned sources`() {
        val saved = repository.save(
            prop("fact", provenance = listOf(ProvenanceEntry(locator = UriLocator("https://example.com/old")))),
        )

        repository.setProvenance(saved.id, listOf(ProvenanceEntry(locator = UriLocator("https://example.com/new"))))

        val uris = repository.provenanceOf(saved.id).map { (it.locator as UriLocator).uri }
        assertEquals(listOf("https://example.com/new"), uris)

        val sourceCount = persistenceManager.getOne(
            QuerySpecification.withStatement("MATCH (s:Source) RETURN count(s) AS c").transform(Long::class.java),
        )
        assertEquals(1L, sourceCount, "the replaced source should be orphan-deleted")
    }

    /** The query-filtered vector search must honour the query's date/time predicates, not just status/level/entity. */
    @Test
    fun `vector search with query honours revised-time filters`() {
        val old = repository.save(prop("alpha topic", contentRevised = Instant.now().minus(Duration.ofDays(30))))
        val recent = repository.save(prop("beta topic", contentRevised = Instant.now()))

        val hits = repository.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "alpha topic", topK = 10, similarityThreshold = 0.0),
            PropositionQuery(revisedAfter = Instant.now().minus(Duration.ofDays(7))),
        )
        val ids = hits.map { it.match.id }.toSet()

        assertTrue(recent.id in ids, "the recently-revised proposition should pass the filter")
        assertTrue(old.id !in ids, "a proposition revised before the cutoff must be filtered out")
    }

    /** findClusters must query the configured vector index, not a hard-coded name. */
    @Test
    fun `findClusters uses the configured vector index name`() {
        repository.save(prop("shared fact", context = "ctx-a"))
        repository.save(prop("shared fact", context = "ctx-b"))

        val bogusName = "nonexistent_vector_index"
        val repoWithBogusIndex = DrivinePropositionRepository(
            graphObjectManager, persistenceManager, embeddingService, transactionManager,
            vectorIndexName = bogusName,
        )

        val thrown = assertThrows<Exception> {
            repoWithBogusIndex.findClusters(similarityThreshold = 0.95, topK = 10, query = PropositionQuery())
        }
        val messages = generateSequence(thrown as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(messages.contains(bogusName), "findClusters should query the configured index; error chain was: $messages")
    }

    /** A null (never-materialised) effectiveConfidence must sort last under EFFECTIVE_CONFIDENCE_DESC, not first. */
    @Test
    fun `effective-confidence ordering ranks null effectiveConfidence last`() {
        val materialised = repository.save(prop("materialised fact", confidence = 0.5))
        val legacy = repository.save(prop("legacy fact", confidence = 0.9))
        // simulate legacy/externally-written data with no materialised ranking column
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (p:Proposition {id: \$id}) SET p.effectiveConfidence = null")
                .bind(mapOf("id" to legacy.id)),
        )

        val ordered = repository.query(
            PropositionQuery(orderBy = PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC),
        )
        assertEquals(
            listOf(materialised.id, legacy.id),
            ordered.map { it.id },
            "a null effectiveConfidence must sort last, not ahead of a confident proposition",
        )
    }

    /** `allEntityIds` is conjunctive — a proposition must mention *every* listed entity to match. */
    @Test
    fun `query allEntityIds matches only propositions mentioning every id`() {
        repository.save(propWithEntities("ab fact", listOf("a", "b")))
        repository.save(propWithEntities("a only", listOf("a")))
        repository.save(propWithEntities("abc fact", listOf("a", "b", "c")))

        val both = repository.query(PropositionQuery(allEntityIds = listOf("a", "b")))
        assertEquals(
            setOf("ab fact", "abc fact"),
            both.map { it.text }.toSet(),
            "allEntityIds must AND the mention quantifiers, not collapse to a single one",
        )
    }

    /**
     * With a non-default `decayK`/`asOf` the materialised column no longer applies, so the repo must
     * compute effective confidence live over the filtered set, matching the in-memory formula exactly.
     */
    @Test
    fun `query with non-default decay computes effective confidence live, matching in-memory`() {
        val old = Instant.now().minus(Duration.ofDays(365))
        val fast = repository.save(prop("decays fast", confidence = 0.9, decay = 0.9, contentRevised = old))
        val slow = repository.save(prop("decays slow", confidence = 0.6, decay = 0.0, contentRevised = old))
        decayManager.materializeAll() // materialise the column at the default k — deliberately stale for this query

        val asOf = Instant.now()
        val k = 10.0 // non-default → live fallback
        val graphOrder = repository.query(
            PropositionQuery(orderBy = PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, decayK = k, effectiveConfidenceAsOf = asOf),
        ).map { it.id }

        val expected = listOf(fast, slow).sortedByDescending { it.effectiveConfidenceAt(asOf, k) }.map { it.id }
        assertEquals(expected, graphOrder, "graph live-decay order must equal the in-memory effectiveConfidenceAt order")
    }

    /** Provenance is lean by default and only loaded when `withProvenance = true` (or via [findById]). */
    @Test
    fun `withProvenance loads entries only when requested`() {
        val saved = repository.save(
            prop("evidenced", provenance = listOf(ProvenanceEntry(locator = UriLocator("https://example.com/x"), chunkId = "ck"))),
        )
        val q = PropositionQuery(contextId = ContextId("ctx"))

        assertTrue(repository.query(q).single { it.id == saved.id }.provenanceEntries.isEmpty(), "plain query is lean")
        assertEquals("ck", repository.query(q, withProvenance = true).single { it.id == saved.id }.provenanceEntries.single().chunkId)
        assertEquals("ck", repository.findAll(withProvenance = true).single { it.id == saved.id }.provenanceEntries.single().chunkId)
        assertTrue(repository.findAll(withProvenance = false).single { it.id == saved.id }.provenanceEntries.isEmpty())
    }

    /** Bulk clear is cascade-aware — no orphaned `:Mention` or `:Source` nodes are left behind. */
    @Test
    fun `clearAll removes propositions, mentions, and orphaned sources`() {
        repository.save(
            prop("with mention", entityId = "e1", provenance = listOf(ProvenanceEntry(locator = UriLocator("https://example.com/o")))),
        )
        assertEquals(1L, nodeCount("Source"), "precondition: a source exists")

        repository.clearAll()

        assertEquals(0L, nodeCount("Proposition"))
        assertEquals(0L, nodeCount("Mention"), "mentions must not be orphaned")
        assertEquals(0L, nodeCount("Source"), "sources left with no DERIVED_FROM edge must be pruned")
    }

    /** `findByGrounding` selects propositions whose `grounding` list contains the chunk (one query, via `hasItem`). */
    @Test
    fun `findByGrounding returns propositions grounded in the chunk`() {
        val grounded = repository.save(prop("grounded fact", grounding = listOf("chunk-1", "chunk-2")))
        repository.save(prop("other fact", grounding = listOf("chunk-3")))

        assertEquals(listOf(grounded.id), repository.findByGrounding("chunk-1").map { it.id })
        assertTrue(repository.findByGrounding("chunk-x").isEmpty(), "no proposition is grounded in an unknown chunk")
    }

    /**
     * A node written before a field existed comes back with that property present-null, which used to
     * fail the load (`MissingKotlinParameterException`). The `@Default` annotations must let it load,
     * falling back to the declared defaults instead.
     */
    @Test
    fun `loads a legacy node missing the PR#47 revision columns, defaulting them to created`() {
        val saved = repository.save(prop(text = "legacy fact", contentRevised = Instant.parse("2021-06-01T00:00:00Z")))
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (p:Proposition {id: \$id}) REMOVE p.contentRevised, p.metadataRevised, p.lastTouched")
                .bind(mapOf("id" to saved.id)),
        )

        val found = repository.findById(saved.id)

        assertNotNull(found, "a node missing the revision columns must still load")
        found!!
        assertEquals(found.created, found.contentRevised, "missing contentRevised falls back to created")
        assertEquals(found.created, found.metadataRevised, "missing metadataRevised falls back to created")
    }

    /**
     * The same load-resilience for a stripped scalar and collection: a node missing `status` and
     * `grounding` must default to `"ACTIVE"` and an empty list rather than failing to load.
     */
    @Test
    fun `loads a legacy node missing status and grounding, applying defaults`() {
        val saved = repository.save(prop(text = "another legacy fact", grounding = listOf("chunk-1")))
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (p:Proposition {id: \$id}) REMOVE p.status, p.grounding")
                .bind(mapOf("id" to saved.id)),
        )

        val found = repository.findById(saved.id)

        assertNotNull(found, "a node missing status/grounding must still load")
        found!!
        assertEquals(PropositionStatus.ACTIVE, found.status, "missing status defaults to ACTIVE")
        assertTrue(found.grounding.isEmpty(), "missing grounding defaults to an empty list")
    }

    /** Insert a proposition node directly, bypassing [DrivinePropositionRepository]'s in-JVM dedup. */
    private fun seedRawNode(p: Proposition) {
        val embedding = embeddingService.embed(p.text).toList()
        graphObjectManager.save(PropositionGraphMapper.toView(p, embedding), CascadeType.DELETE_ORPHAN)
    }

    /** Like [seedRawNode] but with a caller-supplied embedding, so a pair's exact cosine is controlled. */
    private fun seedRawNodeWithEmbedding(p: Proposition, embedding: List<Float>) {
        graphObjectManager.save(PropositionGraphMapper.toView(p, embedding), CascadeType.DELETE_ORPHAN)
    }

    /**
     * A vector with exact cosine [cosTheta] to [vector], built by rotating [vector] toward an
     * arbitrary orthogonal direction (Gram-Schmidt against the unrelated embedding of [seedText]).
     * Lets a test pin the true cosine to a stored sibling even though `findSimilarWithScores` always
     * derives the query vector from query text and can't be seeded directly — whatever coordinate
     * system the embedder uses.
     */
    private fun rotate(vector: List<Float>, cosTheta: Double, seedText: String): List<Float> {
        fun dot(a: List<Float>, b: List<Float>) = a.indices.sumOf { (a[it] * b[it]).toDouble() }
        fun normalize(a: List<Float>): List<Float> {
            val n = kotlin.math.sqrt(dot(a, a))
            return a.map { (it / n).toFloat() }
        }
        val u = normalize(vector)
        val raw = embeddingService.embed(seedText).toList()
        val projection = dot(raw, u)
        val orthogonal = normalize(raw.indices.map { (raw[it] - projection * u[it]).toFloat() })
        val sinTheta = kotlin.math.sqrt(1.0 - cosTheta * cosTheta)
        return u.indices.map { (cosTheta * u[it] + sinTheta * orthogonal[it]).toFloat() }
    }

    /** Run [body] with the (contextId, text) uniqueness constraint dropped, then restore it. */
    private fun withoutContextTextConstraint(body: () -> Unit) {
        val name = persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement(
                    "SHOW CONSTRAINTS YIELD name, labelsOrTypes, properties " +
                        "WHERE 'Proposition' IN labelsOrTypes AND properties = ['contextId', 'text'] " +
                        "RETURN name",
                )
                .transform(String::class.java),
        )
        name?.let { persistenceManager.execute(QuerySpecification.withStatement("DROP CONSTRAINT $it IF EXISTS")) }
        try {
            body()
        } finally {
            // Duplicate rows seeded while the constraint was off would block recreating it, so clear
            // Proposition nodes first, then restore the constraint.
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (p:Proposition) DETACH DELETE p"))
            persistenceManager.execute(
                QuerySpecification.withStatement(
                    "CREATE CONSTRAINT proposition_context_text_unique IF NOT EXISTS " +
                        "FOR (p:Proposition) REQUIRE (p.contextId, p.text) IS UNIQUE",
                ),
            )
        }
    }

    /**
     * An in-place `save` of an already-stored proposition must update its own node, not redirect to a
     * same-text sibling and drop the update. The `(contextId, text)` constraint normally rules that
     * sibling out, but the schema is adopter-supplied, so this checks `save` stays correct without it.
     */
    @Test
    fun `save of an existing id updates its own node even when a same-text sibling exists`() {
        withoutContextTextConstraint {
            val survivor = repository.save(prop("Grace Hopper coined the term debugging", grounding = listOf("g-survivor")))
            // A STALE same-text twin with a different id — the kind of foreign sibling that can only
            // exist when the uniqueness constraint is absent.
            val twin = prop("Grace Hopper coined the term debugging", status = PropositionStatus.STALE)
            seedRawNode(twin)

            // What the dedup sweep does to the survivor: append the loser's grounding, bump reinforcement.
            repository.save(survivor.withGrounding(listOf("g-loser")).copy(reinforceCount = survivor.reinforceCount + 1))

            val reloaded = repository.findById(survivor.id)
            assertNotNull(reloaded)
            assertEquals(
                setOf("g-survivor", "g-loser"),
                reloaded!!.grounding.toSet(),
                "the survivor persists its merged grounding, not lost to the same-text twin",
            )
            assertEquals(1, reloaded.reinforceCount, "the reinforcement bump persists on the survivor")
            assertEquals(PropositionStatus.STALE, repository.findById(twin.id)?.status, "the twin is untouched")

            // And retiring a proposition to STALE lands on its own node, not the sibling.
            val retired = repository.save(reloaded.withStatus(PropositionStatus.STALE))
            assertEquals(survivor.id, retired.id, "save returns the updated node, not the same-text sibling")
            assertEquals(PropositionStatus.STALE, repository.findById(survivor.id)?.status)
        }
    }

    /**
     * An ACTIVE same-text sibling is a live duplicate, not history like the STALE twin above. Save
     * still must not redirect the update to it, but also must not silently merge the two — it writes
     * to its own id (logging a WARN naming both ids) and leaves the pair for the dedup sweep.
     */
    @Test
    fun `update of an existing id does not silently merge into an ACTIVE same-text sibling`() {
        withoutContextTextConstraint {
            val original = repository.save(prop("Ada Lovelace wrote the first algorithm", grounding = listOf("g-original")))
            // An ACTIVE same-text twin — only reachable without the (contextId, text) constraint.
            val twin = prop("Ada Lovelace wrote the first algorithm", status = PropositionStatus.ACTIVE)
            seedRawNode(twin)

            val updated = repository.save(original.withGrounding(listOf("g-original", "g-more")))

            assertEquals(original.id, updated.id, "save must update its own node, not redirect to the ACTIVE twin")
            assertEquals(
                setOf("g-original", "g-more"),
                repository.findById(original.id)!!.grounding.toSet(),
                "the update lands on the caller's own node",
            )
            assertEquals(
                PropositionStatus.ACTIVE, repository.findById(twin.id)?.status,
                "save does not touch the twin — merging is the dedup sweep's job, not save's",
            )
            assertEquals(
                2, repository.findAll().count { it.text == "Ada Lovelace wrote the first algorithm" },
                "both nodes persist — a deliberate, logged duplicate, not a silently dropped update",
            )
        }
    }

    // ========================================================================
    // D8: raw-confidence floor (membership) vs effective confidence (ranking)
    // ========================================================================

    /**
     * The eager memory path (Memory.baseQuery()) filters membership on RAW confidence and only ever
     * uses the decayed value for ranking. A stable, high-raw-confidence fact must stay in the eager
     * set even once age has decayed it well below the floor — genuinely low-raw-confidence facts are
     * still filtered.
     */
    @Test
    fun `raw confidence floor keeps a decayed but reliable fact, effective confidence only ranks it`() {
        // Stable ACTIVE fact: high raw confidence, but old + moderate decay drags effective confidence
        // below the default 0.5 floor.
        val stableButDecayed = repository.save(
            prop(
                "the subject is passionate about a stable hobby",
                confidence = 0.99,
                decay = 0.14,
                contentRevised = Instant.now().minus(Duration.ofDays(7)),
            ),
        )
        assertTrue(
            stableButDecayed.effectiveConfidenceAt(Instant.now()) < 0.5,
            "precondition: decay must have pushed effective confidence below the floor",
        )
        // A fresh, high-confidence fact — should always be included.
        val fresh = repository.save(prop("a fresh fact", confidence = 0.95, decay = 0.0))
        // A genuinely-unreliable fact — low RAW confidence must still be excluded by the floor.
        val unreliable = repository.save(prop("an unreliable guess", confidence = 0.3, decay = 0.0))

        // Mirrors Memory.baseQuery(): raw-confidence floor for membership, ACTIVE only.
        val eagerQuery = PropositionQuery
            .forContextId(ContextId("ctx"))
            .withMinConfidence(0.5)
            .withStatuses(setOf(PropositionStatus.ACTIVE))

        val included = repository.query(eagerQuery).map { it.id }.toSet()

        assertTrue(stableButDecayed.id in included, "a stable, high-raw-confidence fact must survive decay-driven exclusion")
        assertTrue(fresh.id in included, "a fresh high-confidence fact must be included")
        assertFalse(unreliable.id in included, "a genuinely low-raw-confidence fact must still be filtered")

        // Decay still ranks: ordered by effective confidence, the decayed fact must sort behind the
        // fresher one even though both are members.
        val ranked = repository.query(eagerQuery.orderedByEffectiveConfidence()).map { it.id }
        assertTrue(
            ranked.indexOf(fresh.id) < ranked.indexOf(stableButDecayed.id),
            "decay must demote the stable fact's rank even though it stays a member: ranked=$ranked",
        )

        // Sanity: the OLD behavior (filtering membership by decayed effective confidence) would have
        // wrongly excluded the stable fact — this is the bug this fix closes.
        val oldStyleQuery = PropositionQuery
            .forContextId(ContextId("ctx"))
            .withMinEffectiveConfidence(0.5)
            .withStatuses(setOf(PropositionStatus.ACTIVE))
        val oldStyleIncluded = repository.query(oldStyleQuery).map { it.id }.toSet()
        assertFalse(
            stableButDecayed.id in oldStyleIncluded,
            "sanity check: the old minEffectiveConfidence filter would have excluded the stable fact",
        )
    }

    // ========================================================================
    // D8: reinforce on access — touchAccessed refreshes the decay anchor
    // ========================================================================

    /** [DrivinePropositionRepository.touchAccessed] writes lastAccessed in a single batch statement. */
    @Test
    fun `touchAccessed refreshes lastAccessed for the given ids, ignoring unknown ones`() {
        val old = Instant.now().minus(Duration.ofDays(30))
        val a = repository.save(prop("fact a", contentRevised = old, lastAccessed = old))
        val b = repository.save(prop("fact b", contentRevised = old, lastAccessed = old))
        val untouched = repository.save(prop("fact c", contentRevised = old, lastAccessed = old))

        repository.touchAccessed(listOf(a.id, b.id, "no-such-id"))

        val reloadedA = repository.findById(a.id)!!
        val reloadedB = repository.findById(b.id)!!
        val reloadedC = repository.findById(untouched.id)!!

        assertTrue(reloadedA.lastAccessed.isAfter(old), "touched proposition a must have a refreshed lastAccessed")
        assertTrue(reloadedB.lastAccessed.isAfter(old), "touched proposition b must have a refreshed lastAccessed")
        assertEquals(old, reloadedC.lastAccessed, "an untouched proposition's lastAccessed must be unchanged")
    }

    /** Access refreshes decay: a touched DECAYING proposition's effective confidence recovers. */
    @Test
    fun `touchAccessed refreshes decay for a DECAYING proposition`() {
        val old = Instant.now().minus(Duration.ofDays(30))
        val saved = repository.save(
            prop("frequently used but old fact", confidence = 0.9, decay = 0.5, contentRevised = old, lastAccessed = old),
        )
        val staleEffective = saved.effectiveConfidenceAt(Instant.now())
        assertTrue(staleEffective < saved.confidence, "precondition: the proposition must have decayed")

        repository.touchAccessed(listOf(saved.id))

        val reloaded = repository.findById(saved.id)!!
        val refreshedEffective = reloaded.effectiveConfidenceAt(Instant.now())
        assertTrue(
            refreshedEffective > staleEffective,
            "a fresh access must raise effective confidence above the stale value: stale=$staleEffective refreshed=$refreshedEffective",
        )
    }

    @Test
    fun `touchAccessed with empty ids is a no-op`() {
        val old = Instant.now().minus(Duration.ofDays(30))
        val saved = repository.save(prop("fact", contentRevised = old, lastAccessed = old))

        repository.touchAccessed(emptyList())

        assertEquals(old, repository.findById(saved.id)!!.lastAccessed)
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
open class TransactionProxyTestConfiguration
