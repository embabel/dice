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

import com.embabel.dice.proposition.extraction.ExtractionRunKey
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import com.embabel.dice.storage.model.PropositionView
import org.drivine.StaleObjectException
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.load
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * The Drivine store against the same cross-backend contract the in-memory reference passes.
 *
 * A real graph is where the tenant guard has to hold: the run store and the proposition repository
 * are separate aggregates, and the link is the one write in this train that touches both.
 *
 * **The endpoint nodes are seeded once and only the links are cleared between tests.** Deleting a
 * `:Proposition` with raw Cypher and then re-saving the same id through `DrivinePropositionRepository`
 * does not produce the node again as written: the second save comes back with a null `contextId`,
 * because the repository's object manager still holds the node this class deleted behind its back.
 * Seeding per test would therefore leave every test after the first with untenanted propositions,
 * and every scoped read would correctly return nothing. Links are relationships and have no such
 * problem, so those are what each test starts from a clean slate on.
 */
@SpringBootTest(classes = [TestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DrivinePropositionRunLinkStoreContractIntegrationTest : AbstractPropositionRunLinkStoreContractTest() {

    @Autowired
    private lateinit var linkStore: DrivinePropositionRunLinkStore

    @Autowired
    private lateinit var runStore: DrivineExtractionRunStore

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var graphObjectManager: StatelessGraphObjectManager

    @BeforeAll
    fun seed() {
        wipe()
        listOf(tenant, neighbour).forEach { context ->
            fixtureRunIds.forEach { runStore.save(run(it, context)) }
        }
        (fixturePropositionIds + disposablePropositionId).forEach {
            repository.save(proposition(it, tenant))
        }
        neighbourPropositionIds.forEach { repository.save(proposition(it, neighbour)) }
    }

    @BeforeEach
    fun clearLinks() {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH ()-[r:${ExtractionRunSchema.PRODUCED_BY_RUN_REL}]->() DELETE r",
            ),
        )
    }

    @AfterAll
    fun cleanUp() {
        wipe()
    }

    private fun wipe() {
        (ExtractionRunSchema.LABELS + listOf("Proposition", "Mention", "Source")).forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }

    override fun deleteProposition(id: String) {
        repository.delete(id)
    }

    override fun store(): PropositionRunLinkStore = linkStore

    /**
     * Linking the same claim to the same run twice leaves one edge in the graph.
     *
     * The contract suite already says a replay reports the same count, but a count is what the
     * *store* believes. This counts the relationships themselves, because the failure being guarded
     * against is precisely one where both answers look right and the graph holds two edges: every
     * read here returns run refs and ids, so a duplicate edge is invisible to all of them and shows
     * up later as inflated audit answers.
     *
     * The write is a `MERGE` between two already-matched nodes, which is what makes this hold under
     * concurrent writers as well — Neo4j locks the endpoints when the merge decides to create.
     */
    @Test
    fun `linking the same pair twice leaves exactly one edge`() {
        val key = ExtractionRunKey(tenant, ExtractionRunRef(fixtureRunIds.first()))
        val propositionId = fixturePropositionIds.first()

        val first = linkStore.link(key, listOf(propositionId))
        val second = linkStore.link(key, listOf(propositionId))

        assertEquals(first, second, "a replay reports what the first write did")
        assertEquals(
            1L,
            edgeCount(propositionId, key.runRef.runId),
            "the graph holds exactly one PRODUCED_BY_RUN edge for the pair",
        )

        // The same claim named twice inside a single batch is also one edge.
        linkStore.link(key, listOf(propositionId, propositionId))
        assertEquals(
            1L,
            edgeCount(propositionId, key.runRef.runId),
            "a duplicate inside one batch is still one edge",
        )
    }

    /**
     * A link is a relationship of the proposition, written by Cypher. It marks the proposition's
     * stamp, so a save that replaces a relationship list of a view loaded before the link is refused.
     */
    @Test
    fun `a save of a loaded proposition is refused after it is linked to a run`() {
        val propositionId = fixturePropositionIds.first()
        val loaded = requireNotNull(graphObjectManager.load<PropositionView>(propositionId))

        linkStore.link(ExtractionRunKey(tenant, ExtractionRunRef(fixtureRunIds.first())), listOf(propositionId))

        assertThrows<StaleObjectException> {
            graphObjectManager.save(loaded, DrivinePropositionRepository.REPLACE_MENTIONS)
        }
    }

    /** How many `PRODUCED_BY_RUN` edges join this claim to this run, counted in the graph itself. */
    private fun edgeCount(propositionId: String, runId: String): Long = persistenceManager.getOne(
        QuerySpecification.withStatement(
            """
            MATCH (p:Proposition {id: ${'$'}propositionId})
                  -[r:${ExtractionRunSchema.PRODUCED_BY_RUN_REL}]->
                  (n:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
            RETURN count(r) AS c
            """.trimIndent(),
        ).bind(mapOf("propositionId" to propositionId, "contextId" to tenant.value, "runId" to runId))
            .transform(Long::class.java),
    )
}
