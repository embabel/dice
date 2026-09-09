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
package com.embabel.dice.proposition.extraction

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.PropositionStore
import org.jetbrains.annotations.ApiStatus

/**
 * Reference [PropositionRunLinkStore] that keeps the relation in a map.
 *
 * It is the executable statement of what the contract means, so a durable backend can be held to
 * the same suite, and it lets a host record run lineage before it has a database.
 *
 * **It needs both endpoint stores, and that is the honest shape.** The tenant guard is a question
 * about existence — is there a run under this key, is there a proposition under this id in this
 * tenant — and only the stores that hold them can answer. A durable backend asks the same two
 * questions in its own statements inside one transaction. A reference implementation that skipped
 * the check would accept
 * cross-tenant links every durable backend rejects, and the suite that holds the two together would
 * be asserting nothing.
 *
 * Every write and read runs inside one monitor, so a check and the write that depends on it cannot
 * interleave with another thread's. A durable store gets the same from its transaction.
 *
 * There is no unscoped read here, for the same reason [InMemoryExtractionRunStore] has none: one
 * instance holds every tenant's links.
 *
 * Memory is bounded only by what a read has seen. A link whose proposition is gone altogether is dropped the
 * next time a read resolves it, and a run with no links left is dropped with it, but nothing sweeps
 * a run nobody reads again. A long-running host wanting real retention uses the durable store.
 *
 * Nothing here survives the JVM, and two instances know nothing about each other.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @param runStore Where the run end of a link is resolved.
 * @param propositionStore Where the proposition end is resolved.
 */
@ApiStatus.Experimental
class InMemoryPropositionRunLinkStore(
    private val runStore: ExtractionRunStore,
    private val propositionStore: PropositionStore,
) : PropositionRunLinkStore {

    private val lock = Any()

    /** Run to the propositions it produced. A set, so a repeated link is one link. */
    private val byRun = HashMap<ExtractionRunKey, MutableSet<String>>()

    override fun link(key: ExtractionRunKey, propositionIds: Collection<String>): Int {
        val ids = propositionIds.distinct()
        if (ids.isEmpty()) return 0
        synchronized(lock) {
            runStore.findRun(key) ?: throw ExtractionRunNotFoundException(key)
            // Out of scope covers both "no such proposition" and "someone else's proposition":
            // from inside a tenant those are the same answer, and both mean this run did not
            // produce it. Collected in full before anything is written, so a rejected batch leaves
            // the relation exactly as it found it.
            val outOfScope = ids.filterNot { inContext(it, key.contextId) }
            if (outOfScope.isNotEmpty()) {
                throw PropositionRunLinkScopeException(key, outOfScope)
            }
            val linked = byRun.getOrPut(key) { LinkedHashSet() }
            linked.addAll(ids)
            return ids.count { it in linked }
        }
    }

    override fun runsOf(
        contextIdValue: String,
        propositionId: String,
        limit: Int,
    ): List<ExtractionRunRef> {
        requirePositiveLimit(limit)
        return synchronized(lock) {
            // The proposition is checked against the store as it is now, not as it was when the
            // link was written. See the note on [propositionsOf]. A read from the wrong tenant fails
            // closed and touches nothing; only a claim that is gone altogether is pruned from every
            // run that named it.
            if (!inContext(propositionId, ContextId(contextIdValue))) {
                if (propositionStore.findById(propositionId) == null) prune(propositionId)
                return emptyList()
            }
            byRun.entries
                .filter { (key, ids) -> key.contextId.value == contextIdValue && propositionId in ids }
                .map { (key, _) -> key.runRef }
                .sortedBy { it.runId }
                .take(limit)
        }
    }

    /**
     * The propositions this run produced that this tenant still holds.
     *
     * **Both reads resolve against the proposition store every time, rather than trusting the map.**
     * A link records that a run produced a claim; if the claim is deleted, or its id now belongs to
     * another tenant, there is nothing left for the link to be about. A graph gets this for free —
     * the edge is detached with the node — so a reference implementation answering from its own map
     * would keep reporting lineage for claims the store no longer has, and the two backends would
     * disagree. Answering from live endpoint state costs a lookup per id and is the only way this
     * store can be held to the same contract.
     *
     * Nothing here is told when a proposition is deleted, so the stale entries are pruned when a
     * read finds them: an id the proposition store no longer holds at all is dropped from the run's
     * set, and an emptied set is dropped with it. An id that exists but reads as another tenant's
     * is filtered and left alone, since a read from the wrong tenant must change nothing. The answer
     * is the same either way; what changes is that the map stops growing with claims that no longer
     * exist.
     */
    override fun propositionsOf(key: ExtractionRunKey, limit: Int): List<String> {
        requirePositiveLimit(limit)
        return synchronized(lock) {
            val ids = byRun[key] ?: return emptyList()
            val gone = ids.filter { propositionStore.findById(it) == null }
            if (gone.isNotEmpty()) {
                ids.removeAll(gone.toSet())
                if (ids.isEmpty()) byRun.remove(key)
            }
            ids.filter { inContext(it, key.contextId) }.sorted().take(limit)
        }
    }

    /** Drops a proposition nobody holds any more from every run that named it; a run left with no links is dropped too. */
    private fun prune(propositionId: String) {
        val emptied = byRun.entries.filter { (_, ids) -> ids.remove(propositionId) && ids.isEmpty() }.map { it.key }
        emptied.forEach { byRun.remove(it) }
    }

    /**
     * Whether the proposition is one this tenant holds.
     *
     * `findById` is not tenant-scoped — proposition ids are minted globally unique — so the tenant
     * is checked on the proposition that comes back rather than assumed from the lookup.
     */
    private fun inContext(propositionId: String, contextId: ContextId): Boolean =
        propositionStore.findById(propositionId)?.contextId == contextId

    private fun requirePositiveLimit(limit: Int) {
        require(limit > 0) { "limit must be positive, was $limit" }
    }
}
