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
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.proposition.DecaySweepConfig
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant

/**
 * Integration tests for the graph storage stack against a Neo4j testcontainer (provided by Drivine's
 * test support). Not `@Transactional`: dedup commits via its own [org.springframework.transaction.support.TransactionTemplate],
 * so isolation is by explicit `clearAll()` per test rather than rollback.
 */
@SpringBootTest(classes = [TestApplication::class])
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
    ): Proposition = Proposition(
        contextId = ContextId(context),
        text = text,
        mentions = entityId?.let { listOf(EntityMention("span", "Person", it, MentionRole.SUBJECT)) } ?: emptyList(),
        confidence = confidence,
        decay = decay,
        contentRevised = contentRevised,
        status = status,
        pinned = pinned,
        level = level,
        sourceIds = sourceIds,
        grounding = grounding,
        reinforceCount = reinforceCount,
        metadata = metadata,
        temporal = temporal,
        provenanceEntries = provenance,
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
    fun `chunk history records, dedups, and bookmarks`() {
        assertTrue(!chunkHistoryStore.isProcessed("hash-1"))
        chunkHistoryStore.recordProcessed(ProcessedChunkRecord("hash-1", "src-1", 0, 100, Instant.now()))
        chunkHistoryStore.recordProcessed(ProcessedChunkRecord("hash-2", "src-1", 100, 250, Instant.now().plusSeconds(1)))
        assertTrue(chunkHistoryStore.isProcessed("hash-1"))
        assertEquals(250, chunkHistoryStore.getLastBookmark("src-1")?.endIndex)
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

    /** #1: re-saving a proposition loaded via a lean path (no provenance projected) must not wipe its provenance. */
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

    /** #1b: the append-only save default — saving a lean-loaded copy with a NEW entry must add, not replace. */
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

    /** #2: the query-filtered vector search must honour the query's date/time predicates, not just status/level/entity. */
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

    /** #3: findClusters must query the configured vector index, not a hard-coded name. */
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

    /** #4: a null (never-materialised) effectiveConfidence must sort last under EFFECTIVE_CONFIDENCE_DESC, not first. */
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

    /** #5: `allEntityIds` is conjunctive — a proposition must mention *every* listed entity to match. */
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
     * #6: with a non-default `decayK`/`asOf` the materialised column no longer applies, so the repo must
     * compute effective confidence live over the filtered set — matching the in-memory formula exactly.
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

    /** #7: provenance is lean by default and only loaded when `withProvenance = true` (or via [findById]). */
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

    /** #8: bulk clear is cascade-aware — no orphaned `:Mention` or `:Source` nodes are left behind. */
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

    /** #9: `findByGrounding` selects propositions whose `grounding` list contains the chunk (one query, via `hasItem`). */
    @Test
    fun `findByGrounding returns propositions grounded in the chunk`() {
        val grounded = repository.save(prop("grounded fact", grounding = listOf("chunk-1", "chunk-2")))
        repository.save(prop("other fact", grounding = listOf("chunk-3")))

        assertEquals(listOf(grounded.id), repository.findByGrounding("chunk-1").map { it.id })
        assertTrue(repository.findByGrounding("chunk-x").isEmpty(), "no proposition is grounded in an unknown chunk")
    }
}
