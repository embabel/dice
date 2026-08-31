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
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunKey
import com.embabel.dice.proposition.extraction.ExtractionRunLineage
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.proposition.extraction.PropositionRunLinkScopeException
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * The invariant the whole slice exists for, measured on a real graph: **two extraction runs over
 * identical content leave one proposition, one source grounding, and two run links.**
 *
 * Every part of it is a separate way to get this wrong. Two propositions means dedup did not fire or
 * the second run's canonical id was not consumed. Two `DERIVED_FROM` edges means the run got folded
 * into source identity. One run link means the relation cannot hold both runs, and the second run's
 * contribution — that it confirmed a claim it did not create — is unrecorded.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineRunLineageIntegrationTest {

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

    private val tenant = ContextId("lineage-tenant")
    private val startedAt: Instant = Instant.parse("2026-08-31T10:15:30Z")

    @AfterEach
    fun cleanUp() {
        (ExtractionRunSchema.LABELS + listOf("Proposition", "Mention", "Source")).forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }

    private fun run(runId: String, contextId: ContextId = tenant) = ExtractionRun(
        contextId = contextId,
        lineage = ExtractionRunLineage.root(ExtractionRunRef(runId)),
        status = ExtractionRunStatus.RUNNING,
        startedAt = startedAt,
    )

    /**
     * What one extraction of one sentence produces.
     *
     * Every test names its own [subject], and so its own text and source, because ids and source
     * keys are not reusable across tests here: deleting a `:Proposition` with raw Cypher and then
     * re-saving the same id through `DrivinePropositionRepository` comes back with a null
     * `contextId`, since the repository's object manager still holds the node this class deleted
     * behind its back. Two calls with the same [subject] inside one test are the point — that is the
     * re-extraction the dedup rule collapses.
     */
    private fun extracted(id: String, subject: String) = Proposition(
        id = id,
        contextId = tenant,
        text = "The $subject team ships on Friday",
        mentions = listOf(
            EntityMention(span = "the team", type = "Person", role = MentionRole.SUBJECT),
        ),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
        provenanceEntries = listOf(ProvenanceEntry(locator = locatorFor(subject), chunkId = "chunk-1")),
    )

    private fun locatorFor(subject: String) = UriLocator("https://example.com/$subject.txt")

    private fun count(statement: String, bindings: Map<String, Any?> = emptyMap()): Long =
        persistenceManager.getOne(
            QuerySpecification.withStatement(statement).bind(bindings).transform(Long::class.java),
        ) ?: 0L

    @Test
    fun `two identical-content runs leave one proposition, one source grounding, and two run links`() {
        val first = run("lineage-run-1")
        val second = run("lineage-run-2")
        runStore.save(first)
        runStore.save(second)

        // Run 1 extracts the sentence and stores it. Run 2 extracts the same sentence, mints its own
        // id, and the repository answers with the proposition it already holds.
        val canonical = repository.save(extracted("minted-by-run-1", "proof"))
        val secondPass = repository.save(extracted("minted-by-run-2", "proof"))

        assertEquals(canonical.id, secondPass.id, "the second run's insert deduplicates onto the first")

        linkStore.link(first.key(), listOf(canonical.id))
        linkStore.link(second.key(), listOf(secondPass.id))

        assertEquals(
            1L,
            count("MATCH (p:Proposition {contextId: \$c}) RETURN count(p) AS c", mapOf("c" to tenant.value)),
            "one proposition",
        )
        assertEquals(
            1L,
            count(
                "MATCH (:Proposition {id: \$id})-[r:DERIVED_FROM]->(:Source {key: \$key}) RETURN count(r) AS c",
                mapOf("id" to canonical.id, "key" to locatorFor("proof").key()),
            ),
            "one source grounding — run identity never enters source provenance, so the second " +
                "run's identical evidence is the same evidence",
        )
        assertEquals(
            2L,
            count(
                "MATCH (:Proposition {id: \$id})-[r:PRODUCED_BY_RUN]->(:ExtractionRun) RETURN count(r) AS c",
                mapOf("id" to canonical.id),
            ),
            "two run links — both runs produced this claim, and the relation holds both",
        )

        // And the same three facts through the store's own reads.
        assertEquals(
            listOf(ExtractionRunRef("lineage-run-1"), ExtractionRunRef("lineage-run-2")),
            linkStore.runsOf(tenant, canonical.id, 10),
        )
        assertEquals(listOf(canonical.id), linkStore.propositionsOf(first.key(), 10))
        assertEquals(listOf(canonical.id), linkStore.propositionsOf(second.key(), 10))
    }

    @Test
    fun `the store carries the experimental marker its own compatibility note promises`() {
        // Annotations on an interface do not reach its implementations, so
        // PropositionRunLinkStore being marked says nothing about this class — and this class is
        // what a host declares as a bean and reads the KDoc of. The marker has class retention, so
        // this reads the class file rather than reflecting.
        val name = DrivinePropositionRunLinkStore::class.java.name.replace('.', '/') + ".class"
        val bytes = checkNotNull(
            DrivinePropositionRunLinkStore::class.java.classLoader.getResourceAsStream(name),
        ).use { it.readBytes() }

        assertTrue(
            String(bytes, Charsets.ISO_8859_1)
                .contains("Lorg/jetbrains/annotations/ApiStatus\$Experimental;"),
            "DrivinePropositionRunLinkStore is not marked experimental",
        )
    }

    @Test
    fun `the link is one edge however many times it is written`() {
        val theRun = run("lineage-idempotent")
        runStore.save(theRun)
        val stored = repository.save(extracted("minted-idempotent", "idempotent"))

        repeat(3) { linkStore.link(theRun.key(), listOf(stored.id)) }

        assertEquals(
            1L,
            count(
                "MATCH (:Proposition {id: \$id})-[r:PRODUCED_BY_RUN]->(:ExtractionRun) RETURN count(r) AS c",
                mapOf("id" to stored.id),
            ),
        )
    }

    @Test
    fun `the edge lands between the two nodes the schema names`() {
        val theRun = run("lineage-shape")
        runStore.save(theRun)
        val stored = repository.save(extracted("minted-shape", "shape"))

        linkStore.link(theRun.key(), listOf(stored.id))

        assertEquals(
            1L,
            count(
                """
                MATCH (p:Proposition {id: ${'$'}id, contextId: ${'$'}c})
                      -[:${ExtractionRunSchema.PRODUCED_BY_RUN_REL}]->
                      (n:ExtractionRun {contextId: ${'$'}c, runId: ${'$'}runId})
                RETURN count(*) AS c
                """.trimIndent(),
                mapOf("id" to stored.id, "c" to tenant.value, "runId" to "lineage-shape"),
            ),
        )
        // The edge carries nothing. A bare edge is what lets the write be a plain MERGE.
        assertEquals(
            0L,
            count(
                "MATCH (:Proposition {id: \$id})-[r:PRODUCED_BY_RUN]->() RETURN count(keys(r)[0]) AS c",
                mapOf("id" to stored.id),
            ),
        )
    }

    @Test
    fun `lineage inside a caller's transaction sees its writes and cannot condemn it`() {
        // Both halves of how `link` behaves when a host wraps extraction in its own transaction.
        //
        // **It participates.** The proposition saved a line earlier has not committed, and the link
        // still resolves it — so lineage and claims commit together or roll back together, which is
        // what a host wrapping extraction would want.
        //
        // **An application-level failure does not condemn the caller.** Spring marks a
        // participating transaction rollback-only when the inner method throws, and it does so
        // here — Drivine overrides `doSetRollbackOnly` and the shared transaction object's flag is
        // set. The commit survives because that flag is write-only: `DrivineTransactionObject` does
        // not implement `SmartTransactionObject`, so Spring's `isGlobalRollbackOnly` cannot see it,
        // and Drivine never reads it in `doCommit`. Propagated, then dropped.
        //
        // So `isRollbackOnly` below cannot fail today, and the teeth of this test are the
        // post-commit counts. Both are kept deliberately: the flag assertion trips if Drivine
        // implements `SmartTransactionObject`, and the counts trip if it starts reading the flag in
        // `doCommit`. Either way this goes red before a host discovers it as lost extractions.
        //
        // Only application-level failures are covered. See the server-side case below.
        val theRun = run("lineage-ambient")
        runStore.save(theRun)

        TransactionTemplate(transactionManager).execute { status ->
            val stored = repository.save(extracted("minted-ambient", "ambient"))

            assertEquals(
                1,
                linkStore.link(theRun.key(), listOf(stored.id)),
                "the link resolves a proposition this transaction has not committed yet",
            )

            // Exactly what recordRunLineage does: attempt, swallow, carry on.
            runCatching { linkStore.link(theRun.key(), listOf("prop-nobody-holds")) }

            assertFalse(
                status.isRollbackOnly,
                "a failed link must not have condemned the caller's transaction",
            )
        }

        assertEquals(
            1L,
            count(
                "MATCH (p:Proposition {id: \$id}) RETURN count(p) AS c",
                mapOf("id" to "minted-ambient"),
            ),
            "the caller's own write committed",
        )
        assertEquals(
            1L,
            count("MATCH ()-[r:PRODUCED_BY_RUN]->() RETURN count(r) AS c"),
            "and so did the lineage written alongside it",
        )
    }

    @Test
    fun `a server-side failure inside a caller's transaction is the window best-effort does not cover`() {
        // The limit of the non-condemnation guarantee, asserted rather than left to be discovered.
        //
        // Every failure `link` raises on its own — tenant guard, run-not-found, scope rejection,
        // batch-changed — is thrown from Kotlin *after* its statements succeeded, so the Bolt
        // transaction is healthy and the caller can carry on. A statement that fails at the server
        // is a different thing: it terminates the transaction underneath Spring, and no catch in
        // `recordRunLineage` can undo that. A deadlock between two runs linking overlapping
        // propositions is the realistic version.
        //
        // This test injects that failure on the caller's thread-bound transaction and shows what it costs, so the
        // shipped guarantee and this test agree. Slice 10's commit-claims-before-lineage is what
        // closes it; when that lands, this test is the one that should change.
        val theRun = run("lineage-poison")
        runStore.save(theRun)

        val poisoning = object : DrivinePropositionRunLinkStore(persistenceManager) {
            override fun propositionsInContext(contextIdValue: String, ids: List<String>): Set<String> {
                // Rejected by the server, on the transaction the caller is using.
                persistenceManager.execute(
                    QuerySpecification.withStatement("MATCH (n:Proposition) RETURN n.id +"),
                )
                return super.propositionsInContext(contextIdValue, ids)
            }
        }

        val stored = repository.save(extracted("minted-poison", "poison"))

        val outcome = runCatching {
            TransactionTemplate(transactionManager).execute {
                // Best-effort, exactly as recordRunLineage does it: attempt, swallow, carry on.
                runCatching { poisoning.link(theRun.key(), listOf(stored.id)) }
                // The caller believes it may keep working. It may not.
                repository.save(extracted("minted-after-poison", "after-poison"))
            }
        }

        assertTrue(
            outcome.isFailure,
            "a server-side failure is not survivable by catching: the caller's transaction is " +
                "already terminated, and this is the residual window the docs must name",
        )
        assertEquals(
            0L,
            count(
                "MATCH (p:Proposition {id: \$id}) RETURN count(p) AS c",
                mapOf("id" to "minted-after-poison"),
            ),
            "and the caller's later write is lost with it",
        )
    }

    @Test
    fun `a batch that changes under the write links nothing and says so`() {
        // The window the preflight cannot close on its own: validation and the MERGE are separate
        // statements, so under read-committed a proposition can be deleted between them. It passes
        // the check and is gone by the time the write runs.
        //
        // Standing in that window needs a seam, so this store overrides the preflight to delete one
        // proposition *after* reporting both as in scope — exactly what a concurrent writer would
        // have done, and deterministic. Without the count guard inside MERGE_LINKS the surviving
        // proposition would be linked and the partial batch would commit as a success.
        val theRun = run("lineage-toctou")
        runStore.save(theRun)
        val survives = repository.save(extracted("minted-survives", "survives"))
        val vanishes = repository.save(extracted("minted-vanishes", "vanishes"))

        val racing = object : DrivinePropositionRunLinkStore(persistenceManager) {
            override fun propositionsInContext(contextIdValue: String, ids: List<String>): Set<String> {
                val seen = super.propositionsInContext(contextIdValue, ids)
                // The interference, after the check has passed and before the write.
                persistenceManager.execute(
                    QuerySpecification
                        .withStatement("MATCH (p:Proposition {id: \$id}) DETACH DELETE p")
                        .bind(mapOf("id" to vanishes.id)),
                )
                return seen
            }
        }

        val rejected = assertThrows(PropositionRunLinkScopeException::class.java) {
            racing.link(theRun.key(), listOf(survives.id, vanishes.id))
        }

        assertEquals(listOf(vanishes.id), rejected.propositionIds, "the message names what vanished")
        assertEquals(
            0L,
            count("MATCH ()-[r:PRODUCED_BY_RUN]->() RETURN count(r) AS c"),
            "nothing partial commits: the proposition that survived is not linked either",
        )
    }

    @Test
    fun `deleting a proposition takes its run links with it and leaves the run standing`() {
        val theRun = run("lineage-delete")
        runStore.save(theRun)
        val stored = repository.save(extracted("minted-delete", "delete"))
        linkStore.link(theRun.key(), listOf(stored.id))

        repository.delete(stored.id)

        assertEquals(
            0L,
            count("MATCH ()-[r:PRODUCED_BY_RUN]->() RETURN count(r) AS c"),
            "no lineage edge may outlive its proposition",
        )
        assertEquals(
            ExtractionRunStatus.RUNNING,
            runStore.findRun(ExtractionRunKey(tenant, ExtractionRunRef("lineage-delete")))?.status,
            "the run is an audit row and survives the claim being deleted",
        )
    }
}
