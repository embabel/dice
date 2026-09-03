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
import com.embabel.dice.proposition.extraction.ExtractionRunNotFoundException
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.proposition.extraction.PropositionRunLinkScopeException
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Cross-backend contract for [PropositionRunLinkStore]: the tenant guard on the write, idempotency,
 * and the two bounded reads. Each subclass supplies a store already holding the fixtures below, so a
 * backend that disagrees with the in-memory reference fails at authoring time.
 *
 * The cases here are the ones a backend gets wrong in a way a single-backend test would miss. A
 * write that MERGEs without resolving its endpoints accepts a cross-tenant link silently. A read
 * that scopes on the run id and forgets the tenant returns a neighbour's rows. A batch that writes
 * as it validates leaves half a link set behind when one id is rejected.
 */
abstract class AbstractPropositionRunLinkStoreContractTest {

    protected val tenant: ContextId = ContextId("link-tenant")
    protected val neighbour: ContextId = ContextId("link-neighbour")
    protected val startedAt: Instant = Instant.parse("2026-08-31T10:15:30Z")

    /** Runs the fixture stores under both tenants, by id. */
    protected val fixtureRunIds: List<String> = listOf("link-run-a", "link-run-b")

    /**
     * Propositions the fixture stores in [tenant].
     *
     * The neighbour's are separate ids rather than the same ones, because a proposition id is
     * globally unique — one id is one proposition in one tenant, and the graph carries a uniqueness
     * constraint saying so. Run ids are the opposite: host-minted and tenant-qualified, so both
     * tenants hold [fixtureRunIds], which is the arrangement that catches a query scoping on the run
     * id and forgetting the tenant.
     */
    protected val fixturePropositionIds: List<String> = listOf("prop-1", "prop-2", "prop-3")

    /** Propositions the fixture stores in [neighbour]. */
    protected val neighbourPropositionIds: List<String> = listOf("nb-prop-1", "nb-prop-2")

    /**
     * A proposition in [tenant] that exactly one test deletes.
     *
     * It gets its own id because a backend may not be able to put a deleted proposition back: on
     * Drivine, deleting a `:Proposition` with raw Cypher and re-saving the same id through the
     * repository returns a node with a null `contextId`. One disposable fixture is cheaper than
     * making every backend's seeding re-entrant.
     */
    protected val disposablePropositionId: String = "prop-disposable"

    /**
     * Removes a proposition from whatever store backs this store's proposition end, as a host
     * deleting a claim would.
     *
     * Deletion is not on [PropositionRunLinkStore] — a lineage store does not own propositions — so
     * the suite needs each backend to do it, and then holds both to the same answer about what the
     * reads say afterwards.
     */
    protected abstract fun deleteProposition(id: String)

    /**
     * A store whose backing graph already holds [fixtureRunIds] under both tenants,
     * [fixturePropositionIds] in [tenant], [neighbourPropositionIds] in [neighbour], and no links.
     */
    protected abstract fun store(): PropositionRunLinkStore

    protected fun run(runId: String, contextId: ContextId): ExtractionRun = ExtractionRun(
        contextId = contextId,
        lineage = ExtractionRunLineage.root(ExtractionRunRef(runId)),
        status = ExtractionRunStatus.RUNNING,
        startedAt = startedAt,
    )

    protected fun proposition(id: String, contextId: ContextId): Proposition = Proposition(
        id = id,
        contextId = contextId,
        text = "$id in ${contextId.value}",
        mentions = listOf(
            EntityMention(span = "Alice", type = "Person", resolvedId = "e-alice", role = MentionRole.SUBJECT),
        ),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
    )

    private fun key(runId: String, contextId: ContextId = tenant) =
        ExtractionRunKey(contextId, ExtractionRunRef(runId))

    // ---- the write ----

    @Test
    fun `a link is readable from both ends`() {
        val store = store()

        assertEquals(2, store.link(key("link-run-a"), listOf("prop-1", "prop-2")))

        assertEquals(listOf("prop-1", "prop-2"), store.propositionsOf(key("link-run-a"), 10))
        assertEquals(
            listOf(ExtractionRunRef("link-run-a")),
            store.runsOf(tenant, "prop-1", 10),
        )
    }

    @Test
    fun `linking is idempotent and counts the same on a replay`() {
        val store = store()

        val first = store.link(key("link-run-a"), listOf("prop-1", "prop-2"))
        val second = store.link(key("link-run-a"), listOf("prop-1", "prop-2"))

        assertEquals(first, second)
        assertEquals(listOf("prop-1", "prop-2"), store.propositionsOf(key("link-run-a"), 10))
    }

    @Test
    fun `a repeated id in one call is one link`() {
        val store = store()

        assertEquals(1, store.link(key("link-run-a"), listOf("prop-1", "prop-1", "prop-1")))
        assertEquals(listOf("prop-1"), store.propositionsOf(key("link-run-a"), 10))
    }

    @Test
    fun `one proposition links to many runs and one run links to many propositions`() {
        // The many-to-many claim, which is what makes re-extraction expressible at all.
        val store = store()

        store.link(key("link-run-a"), listOf("prop-1", "prop-2"))
        store.link(key("link-run-b"), listOf("prop-1", "prop-3"))

        assertEquals(
            listOf(ExtractionRunRef("link-run-a"), ExtractionRunRef("link-run-b")),
            store.runsOf(tenant, "prop-1", 10),
        )
        assertEquals(listOf("prop-1", "prop-2"), store.propositionsOf(key("link-run-a"), 10))
        assertEquals(listOf("prop-1", "prop-3"), store.propositionsOf(key("link-run-b"), 10))
    }

    @Test
    fun `an empty batch is a no-op`() {
        val store = store()

        assertEquals(0, store.link(key("link-run-a"), emptyList()))
        assertTrue(store.propositionsOf(key("link-run-a"), 10).isEmpty())
    }

    @Test
    fun `the single-proposition convenience writes the same link`() {
        val store = store()

        assertEquals(1, store.link(key("link-run-a"), "prop-2"))
        assertEquals(listOf("prop-2"), store.propositionsOf(key("link-run-a"), 10))
    }

    // ---- the tenant guard ----

    @Test
    fun `a link against a run this tenant does not hold is rejected`() {
        val store = store()

        assertThrows(ExtractionRunNotFoundException::class.java) {
            store.link(key("link-run-absent"), listOf("prop-1"))
        }
        assertTrue(store.runsOf(tenant, "prop-1", 10).isEmpty())
    }

    @Test
    fun `a run cannot claim a neighbour's proposition`() {
        // `nb-prop-1` exists — it is just someone else's. That is the case a backend that MERGEs
        // without resolving its endpoints gets wrong, and it is not the same as an id nobody holds.
        val store = store()

        val rejected = assertThrows(PropositionRunLinkScopeException::class.java) {
            store.link(key("link-run-a"), listOf("nb-prop-1"))
        }

        assertEquals(listOf("nb-prop-1"), rejected.propositionIds)
        assertTrue(store.propositionsOf(key("link-run-a"), 10).isEmpty())
        assertTrue(store.runsOf(tenant, "nb-prop-1", 10).isEmpty())
    }

    @Test
    fun `a link naming a proposition nobody holds is rejected and writes nothing`() {
        val store = store()

        val rejected = assertThrows(PropositionRunLinkScopeException::class.java) {
            store.link(key("link-run-a"), listOf("prop-1", "prop-nowhere"))
        }

        assertEquals(listOf("prop-nowhere"), rejected.propositionIds)
        // Nothing partial: the good id in the same batch was not written either.
        assertTrue(store.propositionsOf(key("link-run-a"), 10).isEmpty())
    }

    // ---- bounded, scoped reads ----

    @Test
    fun `both reads are bounded and ordered by id`() {
        val store = store()
        store.link(key("link-run-a"), listOf("prop-3", "prop-1", "prop-2"))
        store.link(key("link-run-b"), listOf("prop-1"))

        assertEquals(listOf("prop-1", "prop-2"), store.propositionsOf(key("link-run-a"), 2))
        assertEquals(
            listOf(ExtractionRunRef("link-run-a")),
            store.runsOf(tenant, "prop-1", 1),
        )
    }

    @Test
    fun `both reads reject a limit that is not positive`() {
        val store = store()

        listOf(0, -1).forEach { limit ->
            assertThrows(IllegalArgumentException::class.java) {
                store.propositionsOf(key("link-run-a"), limit)
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.runsOf(tenant, "prop-1", limit)
            }
        }
    }

    @Test
    fun `every read fails closed across tenants`() {
        // Both tenants hold a run called `link-run-a`. Only the neighbour's has links, and nothing
        // this tenant asks returns them.
        val store = store()
        store.link(key("link-run-a", neighbour), listOf("nb-prop-1", "nb-prop-2"))

        assertTrue(store.propositionsOf(key("link-run-a", tenant), 10).isEmpty())
        assertTrue(store.runsOf(tenant, "nb-prop-1", 10).isEmpty())
        assertEquals(
            listOf("nb-prop-1", "nb-prop-2"),
            store.propositionsOf(key("link-run-a", neighbour), 10),
        )
        assertEquals(
            listOf(ExtractionRunRef("link-run-a")),
            store.runsOf(neighbour, "nb-prop-1", 10),
        )
    }

    @Test
    fun `link reports exactly the ids it was given`() {
        // The number is the batch's own size, on the first write and on a replay. A backend that
        // returned "how many were new" would report 2 then 0 and invite a caller to read 0 as
        // failure; one that linked a subset and returned that subset's size would be reporting a
        // partial write as a success.
        val store = store()

        assertEquals(2, store.link(key("link-run-a"), listOf("prop-1", "prop-2")))
        assertEquals(2, store.link(key("link-run-a"), listOf("prop-1", "prop-2")))
        assertEquals(3, store.link(key("link-run-a"), listOf("prop-1", "prop-2", "prop-3")))
    }

    @Test
    fun `a link to a deleted proposition disappears from both reads`() {
        // A link is about a claim that exists. Delete the claim and the lineage goes with it: on a
        // graph because the edge is detached with the node, and a reference implementation has to
        // agree rather than keep answering from a map the deletion never reached. Otherwise
        // `runsOf` outlives its subject and the audit reports lineage for something the store no
        // longer holds.
        val store = store()
        store.link(key("link-run-a"), listOf(disposablePropositionId, "prop-1"))

        assertEquals(
            listOf(ExtractionRunRef("link-run-a")),
            store.runsOf(tenant, disposablePropositionId, 10),
        )
        assertTrue(disposablePropositionId in store.propositionsOf(key("link-run-a"), 10))

        deleteProposition(disposablePropositionId)

        assertTrue(
            store.runsOf(tenant, disposablePropositionId, 10).isEmpty(),
            "a deleted proposition has no runs",
        )
        assertEquals(
            listOf("prop-1"),
            store.propositionsOf(key("link-run-a"), 10),
            "the run keeps the claims that still exist and loses the one that does not",
        )
    }

    @Test
    fun `a read for an unknown proposition or run is empty rather than an error`() {
        val store = store()

        assertTrue(store.runsOf(tenant, "prop-nowhere", 10).isEmpty())
        assertTrue(store.propositionsOf(key("link-run-absent"), 10).isEmpty())
    }
}
