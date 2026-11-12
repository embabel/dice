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
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunKey
import com.embabel.dice.proposition.extraction.ExtractionRunLineage
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.proposition.extraction.LineageNotRecordedException
import com.embabel.dice.proposition.extraction.PersistOutcome
import com.embabel.dice.proposition.extraction.PersistStep
import com.embabel.dice.proposition.extraction.PersistUnit
import com.embabel.dice.proposition.extraction.PersistUnitFailedException
import com.embabel.dice.proposition.extraction.PersistUnitWriter
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.interceptor.DefaultTransactionAttribute
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * A persist unit written by the sequential writer inside a caller's transaction is all or nothing
 * on the graph store.
 *
 * Each rollback test fails a step after the claim is saved, lets `persistedOrThrow()` throw the
 * cause out of the transaction, and counts what the database holds afterwards. The last test runs
 * the same failure with no transaction and finds the claim stored, so the zero counts come from the
 * rollback.
 *
 * One rollback test fails the step with a checked exception. What `persistedOrThrow()` throws for it
 * is unchecked and passes Spring's default rollback rule, which a `@Transactional` method with no
 * `rollbackFor` uses.
 */
@SpringBootTest(classes = [TestApplication::class])
class PersistUnitTransactionIntegrationTest {

    @Autowired
    private lateinit var linkStore: DrivinePropositionRunLinkStore

    @Autowired
    private lateinit var runStore: DrivineExtractionRunStore

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val tenant = ContextId("unit-tenant")
    private val startedAt: Instant = Instant.parse("2026-11-12T06:14:00Z")

    @AfterEach
    fun cleanUp() {
        (ExtractionRunSchema.LABELS + listOf("Proposition", "Mention", "Source")).forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }

    /**
     * A running extraction run for the test tenant.
     *
     * @param runId The run's id.
     * @return The run, not yet saved.
     */
    private fun run(runId: String) = ExtractionRun(
        contextId = tenant,
        lineage = ExtractionRunLineage.root(ExtractionRunRef(runId)),
        status = ExtractionRunStatus.RUNNING,
        startedAt = startedAt,
    )

    /**
     * One extracted claim grounded in one chunk, so storing it writes one structural relationship.
     *
     * Each test passes its own [subject], because ids and source keys are not reusable across tests
     * against this repository.
     *
     * @param id The proposition id.
     * @param subject The word that makes the text and the source unique to the test.
     * @return The proposition.
     */
    private fun extracted(id: String, subject: String) = Proposition(
        id = id,
        contextId = tenant,
        text = "The $subject team ships on Friday",
        mentions = listOf(EntityMention(span = "the team", type = "Person", role = MentionRole.SUBJECT)),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
        provenanceEntries = listOf(ProvenanceEntry(locator = locatorFor(subject), chunkId = "chunk-1")),
    )

    /**
     * The source a test's claim came from.
     *
     * @param subject The test's subject.
     * @return A locator unique to the subject.
     */
    private fun locatorFor(subject: String) = UriLocator("https://example.com/$subject.txt")

    /**
     * Runs a counting query.
     *
     * @param statement Cypher that returns one count.
     * @param bindings The query parameters.
     * @return The count, or zero when the query returns nothing.
     */
    private fun count(statement: String, bindings: Map<String, Any?> = emptyMap()): Long =
        persistenceManager.getOne(
            QuerySpecification.withStatement(statement).bind(bindings).transform(Long::class.java),
        ) ?: 0L

    /**
     * The tenant's propositions in the database.
     *
     * @return How many there are.
     */
    private fun propositionsForTenant(): Long =
        count("MATCH (p:Proposition {contextId: \$c}) RETURN count(p) AS c", mapOf("c" to tenant.value))

    /**
     * The run links from one proposition in the database.
     *
     * @param id The proposition id.
     * @return How many `PRODUCED_BY_RUN` edges leave it.
     */
    private fun runLinksOf(id: String): Long =
        count(
            "MATCH (:Proposition {id: \$id})-[r:PRODUCED_BY_RUN]->(:ExtractionRun) RETURN count(r) AS c",
            mapOf("id" to id),
        )

    /**
     * A sequential writer over the Drivine stores.
     *
     * @param entityRepository Where entities and structural relationships go.
     * @return The writer.
     */
    private fun writer(entityRepository: NamedEntityDataRepository): PersistUnitWriter =
        PersistUnitWriter.sequential(repository, entityRepository, linkStore)

    /**
     * A unit holding one claim and naming a run.
     *
     * @param id The proposition id.
     * @param subject The test's subject.
     * @param key The run the claim is linked to.
     * @return The unit.
     */
    private fun unit(id: String, subject: String, key: ExtractionRunKey): PersistUnit =
        PersistUnit.of(listOf(extracted(id, subject)), emptyList(), emptyList()).withRun(key)

    @Test
    fun `a unit that succeeds inside a transaction commits its claim and its run link`() {
        val theRun = run("unit-commit-run")
        runStore.save(theRun)
        val writer = writer(InMemoryNamedEntityDataRepository(DataDictionary.fromClasses("unit")))

        TransactionTemplate(transactionManager).execute {
            writer.write(unit("unit-commit-1", "unit-commit", theRun.key())).persistedOrThrow()
        }

        assertEquals(1L, propositionsForTenant())
        assertEquals(1L, runLinksOf("unit-commit-1"))
    }

    @Test
    fun `a failure after the run link rolls the claim and the run link back`() {
        val theRun = run("unit-rollback-run")
        runStore.save(theRun)
        val writer = writer(FailingLinks())
        val kept = AtomicReference<PersistOutcome>()

        assertThrows<IllegalStateException> {
            TransactionTemplate(transactionManager).execute {
                val outcome = writer.write(unit("unit-rollback-1", "unit-rollback", theRun.key()))
                kept.set(outcome)
                outcome.persistedOrThrow()
            }
        }

        val failed = assertInstanceOf(PersistOutcome.Failed::class.java, kept.get())
        assertEquals(PersistStep.STRUCTURAL_RELATIONSHIPS, failed.step)
        assertEquals(
            setOf(PersistStep.PROPOSITIONS, PersistStep.RUN_LINKS, PersistStep.STRUCTURAL_RELATIONSHIPS),
            failed.leftInStore,
            "the claim and the run link were written before the failure",
        )
        assertEquals(0L, propositionsForTenant())
        assertEquals(0L, runLinksOf("unit-rollback-1"))
        assertEquals(emptyList<String>(), linkStore.propositionsOf(theRun.key(), 10))
    }

    @Test
    fun `a failed run link rolls the claim back`() {
        val neverSaved = run("unit-missing-run")
        val writer = writer(InMemoryNamedEntityDataRepository(DataDictionary.fromClasses("unit")))
        val kept = AtomicReference<PersistOutcome>()

        assertThrows<LineageNotRecordedException> {
            TransactionTemplate(transactionManager).execute {
                val outcome = writer.write(unit("unit-missing-1", "unit-missing", neverSaved.key()))
                kept.set(outcome)
                outcome.persistedOrThrow()
            }
        }

        val failed = assertInstanceOf(PersistOutcome.Failed::class.java, kept.get())
        assertEquals(PersistStep.RUN_LINKS, failed.step)
        assertEquals(
            setOf(PersistStep.PROPOSITIONS, PersistStep.RUN_LINKS),
            failed.leftInStore,
            "the claim was written before the link failed",
        )
        assertEquals(0L, propositionsForTenant())
    }

    @Test
    fun `a checked failure reaches the transaction as an unchecked exception and rolls the claim back`() {
        val theRun = run("unit-checked-run")
        runStore.save(theRun)
        val writer = writer(FailingLinks(IOException("relationships are unreachable")))

        val thrown = assertThrows<PersistUnitFailedException> {
            TransactionTemplate(transactionManager).execute {
                writer.write(unit("unit-checked-1", "unit-checked", theRun.key())).persistedOrThrow()
            }
        }

        assertEquals(PersistStep.STRUCTURAL_RELATIONSHIPS, thrown.step)
        val cause = assertInstanceOf(IOException::class.java, thrown.cause)
        val defaultRule = DefaultTransactionAttribute()
        assertTrue(defaultRule.rollbackOn(thrown), "the default rule rolls back on what persistedOrThrow throws")
        assertFalse(defaultRule.rollbackOn(cause), "the checked cause on its own would commit")
        assertEquals(0L, propositionsForTenant())
        assertEquals(0L, runLinksOf("unit-checked-1"))
    }

    @Test
    fun `without a transaction the same failure leaves the claim and reports it`() {
        val theRun = run("unit-leftover-run")
        runStore.save(theRun)

        val outcome = writer(FailingLinks()).write(unit("unit-leftover-1", "unit-leftover", theRun.key()))

        val failed = assertInstanceOf(PersistOutcome.Failed::class.java, outcome)
        assertEquals(PersistStep.STRUCTURAL_RELATIONSHIPS, failed.step)
        assertTrue(PersistStep.PROPOSITIONS in failed.leftInStore, "leftInStore: ${failed.leftInStore}")
        assertTrue(PersistStep.RUN_LINKS in failed.leftInStore, "leftInStore: ${failed.leftInStore}")
        assertEquals(1L, propositionsForTenant())
    }

    /**
     * An entity repository whose relationship writes all throw.
     *
     * @param failure What each relationship write throws.
     */
    private class FailingLinks(
        private val failure: Exception = IllegalStateException("relationships are down"),
    ) : InMemoryNamedEntityDataRepository(DataDictionary.fromClasses("unit")) {

        /**
         * Throws for every relationship.
         *
         * @param a The start of the relationship.
         * @param b The end of the relationship.
         * @param relationship The relationship to write.
         */
        override fun createRelationship(a: RetrievableIdentifier, b: RetrievableIdentifier, relationship: RelationshipData) {
            throw failure
        }
    }
}
