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
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.GraphQueryCapable
import com.embabel.dice.proposition.GraphTraversalCapable
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.query.graph.GraphNeighborhood
import com.embabel.dice.query.graph.GraphQuery
import com.embabel.dice.temporal.TemporalMetadata
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.drivine.manager.PersistenceManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

/**
 * Parity harness for the native [GraphQueryCapable] on [DrivinePropositionRepository]: the Cypher
 * traversal must agree with the portable [GraphQuery] oracle running over an in-memory store seeded
 * with the SAME propositions.
 *
 * The strict facts (neighbour id set, each neighbour's distance, path length and endpoints,
 * whyExplain fields) are asserted equal to the oracle. Where more than one equally-valid answer
 * exists (which propositions land on a `via` or on a shortest path when parallel edges / ties are
 * present), we assert each returned edge is *valid* rather than object-identical — both engines are
 * free to pick a different but correct edge.
 *
 * Runs against [Neo4jTestContainer], not Drivine's own built-in testcontainer -- see that class
 * for why.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineGraphQueryParityIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    /** Portable oracle: in-memory store + facade, seeded identically to the durable repository. */
    private val oracle = InMemoryPropositionStore()

    private val ctx = ContextId("parity")

    // The seeded entity graph (active edges): A=B (parallel), A-E, B-C, C-D. A-D is SUPERSEDED so it
    // must never appear as an edge. So from A: B,E at distance 1; C at distance 2; D at distance 3.
    private lateinit var src: Proposition
    private lateinit var eAb: Proposition
    private lateinit var eAb2: Proposition
    private lateinit var eAe: Proposition
    private lateinit var eBc: Proposition
    private lateinit var eCd: Proposition
    private lateinit var eAdSuperseded: Proposition
    private lateinit var eBcStale: Proposition

    @BeforeEach
    fun seed() {
        repository.clearAll()
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (s:Source) DETACH DELETE s"))

        // A source proposition so eBc.sourceIds resolves to a real abstraction source.
        src = edge("Sydney is in Australia", listOf("sydney", "australia"))
        eAb = edge("A knows B", listOf("A", "B"))
        eAb2 = edge("A also knows B", listOf("A", "B")) // parallel A-B edge
        eAe = edge("A knows E", listOf("A", "E"))
        eBc = Proposition(
            contextId = ctx,
            text = "B knows C",
            mentions = mentions(listOf("B", "C")),
            confidence = 0.9,
            grounding = listOf("chunk-1", "chunk-2"),
            sourceIds = listOf(src.id),
            reinforceCount = 3,
            temporal = TemporalMetadata(validFrom = Instant.parse("2020-01-01T00:00:00Z")),
            provenanceEntries = listOf(ProvenanceEntry(locator = UriLocator("https://example.com/bc"), chunkId = "ck-bc")),
        )
        eCd = edge("C knows D", listOf("C", "D"))
        eAdSuperseded = edge("A once knew D", listOf("A", "D"), status = PropositionStatus.SUPERSEDED)
        eBcStale = edge("B vaguely knew C", listOf("B", "C"), status = PropositionStatus.STALE)

        listOf(src, eAb, eAb2, eAe, eBc, eCd, eAdSuperseded, eBcStale).forEach {
            repository.save(it)
            oracle.save(it)
        }
    }

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (s:Source) DETACH DELETE s"))
    }

    @Test
    fun `neighborhood at depth 1 matches the portable oracle`() {
        assertNeighborhoodParity(origin = "A", depth = 1)
    }

    @Test
    fun `neighborhood at depth 2 matches the portable oracle`() {
        assertNeighborhoodParity(origin = "A", depth = 2)
    }

    @Test
    fun `pathBetween matches the portable oracle for a multi-hop path`() {
        assertPathParity("A", "D") // A-B-C-D, length 3
    }

    @Test
    fun `pathBetween matches the portable oracle for a direct edge`() {
        assertPathParity("A", "E") // length 1
    }

    @Test
    fun `pathBetween agrees there is no path to an unknown entity`() {
        assertTrue(repository.pathBetween("A", "ZZZ").isEmpty())
        assertTrue(portable().pathBetween("A", "ZZZ").isEmpty())
    }

    @Test
    fun `pathBetween returns the trivial single-node path for identical endpoints`() {
        val native = repository.pathBetween("A", "A")
        val oracleResult = portable().pathBetween("A", "A")
        assertEquals(listOf(listOf("A")), native.map { it.entityIds })
        assertEquals(oracleResult.map { it.entityIds }, native.map { it.entityIds })
        assertTrue(native.single().edges.isEmpty())
    }

    @Test
    fun `whyExplain matches the portable oracle on every field`() {
        val native = repository.whyExplain(eBc.id)
        val oracleResult = portable().whyExplain(eBc.id)
        assertNotNull(native)
        assertNotNull(oracleResult)
        native!!
        oracleResult!!

        assertEquals(oracleResult.proposition.id, native.proposition.id)
        assertEquals(oracleResult.provenanceEntries, native.provenanceEntries)
        assertEquals(oracleResult.groundingChunkIds, native.groundingChunkIds)
        assertEquals(oracleResult.sources.map { it.id }.toSet(), native.sources.map { it.id }.toSet())
        assertEquals(oracleResult.reinforceCount, native.reinforceCount)
        assertEquals(oracleResult.status, native.status)
        assertEquals(oracleResult.temporal, native.temporal)

        // And the durable fields carry the seeded lineage, not empties.
        assertEquals(listOf("chunk-1", "chunk-2"), native.groundingChunkIds)
        assertEquals(setOf(src.id), native.sources.map { it.id }.toSet())
        assertEquals(3, native.reinforceCount)
        assertEquals("ck-bc", native.provenanceEntries.single().chunkId)
    }

    @Test
    fun `whyExplain returns null for a missing proposition, like the oracle`() {
        assertNull(repository.whyExplain("no-such-id"))
        assertNull(portable().whyExplain("no-such-id"))
    }

    @Test
    fun `scoped native routing confines the walk to its context and matches the scoped oracle`() {
        val ctxA = ContextId("ctx-A")
        val ctxB = ContextId("ctx-B")
        // The shared entity Y bridges the two contexts, but each edge lives in exactly one context, so
        // a ctxA-scoped walk from X must reach Y (its own context) yet never Z (only Y->Z, in ctxB).
        val xy = Proposition(contextId = ctxA, text = "X knows Y", mentions = mentions(listOf("X", "Y")), confidence = 0.9)
        val yz = Proposition(contextId = ctxB, text = "Y knows Z", mentions = mentions(listOf("Y", "Z")), confidence = 0.9)
        listOf(xy, yz).forEach { repository.save(it); oracle.save(it) }

        // honorsContextFilter is true, so the facade routes the scoped query straight to native Cypher.
        val nativeA = GraphQuery(repository, ctxA)
        val oracleA = GraphQuery(oracle, ctxA)

        val nborN = nativeA.neighborhood("X", 2)
        val nborO = oracleA.neighborhood("X", 2)
        assertEquals(setOf("Y"), idSet(nborN), "ctxA walk reaches Y but not the ctxB-only Z")
        assertEquals(idSet(nborO), idSet(nborN), "scoped native neighbourhood equals the scoped oracle")
        assertEquals(distances(nborO), distances(nborN))

        // The unscoped walk DOES reach Z, proving it's the context predicate that removes it (not that
        // Z was simply unreachable).
        assertTrue(
            repository.neighborhood("X", 2).neighbours.any { it.entityId == "Z" },
            "the unscoped walk reaches Z across the context boundary — scoping is what removes it",
        )

        // A cross-context path can't leak: X->Z is empty under ctxA, X->Y is the single scoped hop.
        assertTrue(nativeA.pathBetween("X", "Z").isEmpty(), "no ctxA path to the foreign-context Z")
        assertTrue(oracleA.pathBetween("X", "Z").isEmpty())
        assertEquals(
            oracleA.pathBetween("X", "Y").map { it.entityIds },
            nativeA.pathBetween("X", "Y").map { it.entityIds },
            "scoped native path equals the scoped oracle",
        )
    }

    @Test
    fun `hub entity on many propositions matches the oracle (exercises the per-branch dedup)`() {
        // One entity on many parallel edges: many simple paths through the intermediate hops share the
        // same (entity, distance, via) triple, which the branch-level RETURN DISTINCT collapses. The
        // result must be unchanged — every spoke, reached at distance 1 by both of its parallel edges.
        val hub = "HUB"
        (1..12).forEach { i ->
            repository.save(edge("$hub relates to spoke-$i (a)", listOf(hub, "spoke-$i"))).also { oracle.save(it) }
            repository.save(edge("$hub relates to spoke-$i (b)", listOf(hub, "spoke-$i"))).also { oracle.save(it) }
        }
        assertNeighborhoodParity(origin = hub, depth = 2)
    }

    // ---- parity helpers ----

    private fun portable(): GraphQuery = GraphQuery(oracle)

    private fun assertNeighborhoodParity(origin: String, depth: Int) {
        val native = repository.neighborhood(origin, depth)
        val expected = portable().neighborhood(origin, depth)

        // Strict: neighbour id set and each neighbour's distance.
        assertEquals(idSet(expected), idSet(native), "neighbour id sets differ at depth $depth")
        assertEquals(distances(expected), distances(native), "neighbour distances differ at depth $depth")

        // Validity of each via edge: ACTIVE, mentions the neighbour and a node one hop closer.
        val distanceOf = distances(native)
        native.neighbours.forEach { n ->
            assertTrue(n.via.isNotEmpty(), "${n.entityId} at distance ${n.distance} has no via edge")
            n.via.forEach { edge ->
                assertEquals(PropositionStatus.ACTIVE, edge.status, "via edge ${edge.id} must be ACTIVE")
                val ids = edge.mentions.mapNotNull { it.resolvedId }.toSet()
                assertTrue(n.entityId in ids, "via edge ${edge.id} must mention ${n.entityId}")
                val closer = if (n.distance == 1) setOf(origin) else distanceOf.filterValues { it == n.distance - 1 }.keys
                assertTrue(ids.any { it in closer }, "via edge ${edge.id} must mention a node one hop closer")
            }
        }
    }

    private fun assertPathParity(a: String, b: String) {
        val native = repository.pathBetween(a, b)
        val expected = portable().pathBetween(a, b)

        assertTrue(native.isNotEmpty(), "native found no path $a->$b")
        assertTrue(expected.isNotEmpty(), "oracle found no path $a->$b")
        val nativePath = native.single()
        val oraclePath = expected.single()

        // Strict: length and endpoints.
        assertEquals(oraclePath.entityIds.size, nativePath.entityIds.size, "path lengths differ")
        assertEquals(a, nativePath.entityIds.first())
        assertEquals(b, nativePath.entityIds.last())

        // Validity: each edge is ACTIVE and mentions its two consecutive entities.
        assertEquals(nativePath.entityIds.size - 1, nativePath.edges.size)
        nativePath.entityIds.zipWithNext().forEachIndexed { i, (from, to) ->
            val edge = nativePath.edges[i]
            assertEquals(PropositionStatus.ACTIVE, edge.status)
            val ids = edge.mentions.mapNotNull { it.resolvedId }.toSet()
            assertTrue(from in ids && to in ids, "edge ${edge.id} must mention $from and $to")
        }
    }

    private fun idSet(n: GraphNeighborhood): Set<String> = n.neighbours.map { it.entityId }.toSet()

    private fun distances(n: GraphNeighborhood): Map<String, Int> = n.neighbours.associate { it.entityId to it.distance }

    private fun mentions(entityIds: List<String>): List<EntityMention> =
        entityIds.mapIndexed { i, id ->
            EntityMention("span-$id", "Entity", id, if (i == 0) MentionRole.SUBJECT else MentionRole.OBJECT)
        }

    private fun edge(
        text: String,
        entityIds: List<String>,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ): Proposition = Proposition(
        contextId = ctx,
        text = text,
        mentions = mentions(entityIds),
        confidence = 0.9,
        status = status,
    )

    /** Minimal in-memory store + abstraction traversal — the portable oracle's backing store. */
    private class InMemoryPropositionStore : PropositionStore, GraphTraversalCapable {
        private val byId = linkedMapOf<String, Proposition>()

        override fun save(proposition: Proposition): Proposition {
            byId[proposition.id] = proposition
            return proposition
        }

        override fun findById(id: String): Proposition? = byId[id]
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> =
            byId.values.filter { p -> p.mentions.any { it.resolvedId == entityIdentifier.id } }

        override fun findByStatus(status: PropositionStatus): List<Proposition> =
            byId.values.filter { it.status == status }

        override fun findByGrounding(chunkId: String): List<Proposition> =
            byId.values.filter { chunkId in it.grounding }

        override fun findByMinLevel(minLevel: Int): List<Proposition> = byId.values.filter { it.level >= minLevel }
        override fun findAll(): List<Proposition> = byId.values.toList()
        override fun delete(id: String): Boolean = byId.remove(id) != null
        override fun count(): Int = byId.size
    }
}
