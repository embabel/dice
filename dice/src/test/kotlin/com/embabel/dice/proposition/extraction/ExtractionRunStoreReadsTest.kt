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

import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.CONTEXT
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.OTHER_CONTEXT
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.STARTED_AT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The read side: bounded tenant-scoped pages, the two lineage lookups and the chain walk.
 *
 * The scope-before-limit cases are the ones worth reading twice. A page that limited first and
 * scoped afterwards passes every single-tenant test and returns an empty page in production the
 * first time a busy neighbour occupies the head of the index.
 */
class ExtractionRunStoreReadsTest {

    private val store = InMemoryExtractionRunStore()

    private fun run(runId: String, contextId: com.embabel.agent.core.ContextId, atSeconds: Long) =
        ExtractionRunFixtures.runningRun(runId, contextId, STARTED_AT.plusSeconds(atSeconds))

    // ---- scope before limit ----

    @Test
    fun `a page scopes to the tenant before it limits`() {
        // The neighbour holds the six newest runs in the store. A page that took the newest six
        // and then filtered by tenant would return nothing for CONTEXT, which reads exactly like a
        // tenant with no runs.
        (1..6).forEach { store.save(run("neighbour-$it", OTHER_CONTEXT, 100L + it)) }
        (1..4).forEach { store.save(run("mine-$it", CONTEXT, it.toLong())) }

        val page = store.runsInContext(CONTEXT, limit = 3, since = null)

        assertThat(page).hasSize(3)
        assertThat(page.map { it.ref.runId }).containsExactly("mine-4", "mine-3", "mine-2")
        assertThat(page).allSatisfy { assertThat(it.contextId).isEqualTo(CONTEXT) }
    }

    @Test
    fun `the lineage reads scope before they limit too`() {
        val root = ExtractionRunRef("root-1")
        (1..6).forEach {
            store.save(
                ExtractionRunFixtures.runningRun(
                    contextId = OTHER_CONTEXT,
                    lineage = ExtractionRunLineage.childOf(
                        runRef = ExtractionRunRef("neighbour-child-$it"),
                        parent = ExtractionRunLineage.root(root),
                    ),
                    startedAt = STARTED_AT.plusSeconds(100L + it),
                ),
            )
        }
        (1..3).forEach {
            store.save(
                ExtractionRunFixtures.runningRun(
                    contextId = CONTEXT,
                    lineage = ExtractionRunLineage.childOf(
                        runRef = ExtractionRunRef("my-child-$it"),
                        parent = ExtractionRunLineage.root(root),
                    ),
                    startedAt = STARTED_AT.plusSeconds(it.toLong()),
                ),
            )
        }

        assertThat(store.childrenOf(CONTEXT, root, limit = 2).map { it.ref.runId })
            .containsExactly("my-child-3", "my-child-2")
        assertThat(store.runsOfRoot(CONTEXT, root, limit = 2, since = null).map { it.ref.runId })
            .containsExactly("my-child-3", "my-child-2")
    }

    // ---- bounds and windows ----

    @Test
    fun `every page takes a positive limit`() {
        val root = ExtractionRunRef("root-limit")
        listOf(0, -1).forEach { limit ->
            assertThatIllegalArgumentException()
                .describedAs("limit %s", limit)
                .isThrownBy { store.runsInContext(CONTEXT, limit, null) }
            assertThatIllegalArgumentException()
                .isThrownBy { store.childrenOf(CONTEXT, root, limit) }
            assertThatIllegalArgumentException()
                .isThrownBy { store.runsOfRoot(CONTEXT, root, limit, null) }
            assertThatIllegalArgumentException()
                .isThrownBy { store.ancestorsOf(ExtractionRunFixtures.keyOf("run-1"), limit) }
        }
    }

    @Test
    fun `since bounds the window at or after the instant given`() {
        (0..4).forEach { store.save(run("run-$it", CONTEXT, it * 10L)) }
        val cutoff = STARTED_AT.plusSeconds(20)

        val windowed = store.runsInContext(CONTEXT, limit = 10, since = cutoff)

        assertThat(windowed.map { it.ref.runId }).containsExactly("run-4", "run-3", "run-2")
        assertThat(store.runsInContext(CONTEXT, limit = 10, since = null)).hasSize(5)
    }

    @Test
    fun `a page is repeatable when two runs share a start instant`() {
        listOf("run-c", "run-a", "run-b").forEach { store.save(run(it, CONTEXT, 0L)) }

        // Ordering by start alone would leave these three in whatever order the backend liked, and
        // a caller paging through would see one twice or none.
        repeat(3) {
            assertThat(store.runsInContext(CONTEXT, limit = 2, since = null).map { r -> r.ref.runId })
                .containsExactly("run-a", "run-b")
        }
    }

    // ---- lineage ----

    @Test
    fun `children are one hop down the parent axis and supersession is not walked`() {
        val parent = ExtractionRunLineage.root(ExtractionRunRef("run-parent"))
        val child = ExtractionRunLineage.childOf(ExtractionRunRef("run-child"), parent)
        val grandchild = ExtractionRunLineage.childOf(ExtractionRunRef("run-grandchild"), child)
        val replacement = ExtractionRunLineage.root(
            runRef = ExtractionRunRef("run-replacement"),
            supersedesRunRef = parent.runRef,
        )
        listOf(parent, child, grandchild, replacement).forEach {
            store.save(ExtractionRunFixtures.runningRun(lineage = it))
        }

        val children = store.childrenOf(CONTEXT, parent.runRef, limit = 10)

        assertThat(children.map { it.ref.runId }).containsExactly("run-child")
    }

    @Test
    fun `the whole lineage comes back from the root reference in one read`() {
        val root = ExtractionRunLineage.root(ExtractionRunRef("run-root"))
        val pass1 = ExtractionRunLineage.childOf(ExtractionRunRef("run-pass-1"), root)
        val pass2 = ExtractionRunLineage.childOf(ExtractionRunRef("run-pass-2"), pass1)
        val pass3 = ExtractionRunLineage.childOf(ExtractionRunRef("run-pass-3"), pass2)
        val unrelated = ExtractionRunLineage.root(ExtractionRunRef("run-unrelated"))
        listOf(root, pass1, pass2, pass3, unrelated).forEachIndexed { index, lineage ->
            store.save(
                ExtractionRunFixtures.runningRun(
                    lineage = lineage,
                    startedAt = STARTED_AT.plusSeconds(index.toLong()),
                ),
            )
        }

        val lineage = store.runsOfRoot(CONTEXT, root.runRef, limit = 10, since = null)

        // Four hops deep, one read, and the root itself is in it.
        assertThat(lineage.map { it.ref.runId })
            .containsExactly("run-pass-3", "run-pass-2", "run-pass-1", "run-root")
    }

    @Test
    fun `the chain walk climbs to the root, excludes the run itself, and stops at the limit`() {
        val root = ExtractionRunLineage.root(ExtractionRunRef("run-root"))
        val one = ExtractionRunLineage.childOf(ExtractionRunRef("run-1"), root)
        val two = ExtractionRunLineage.childOf(ExtractionRunRef("run-2"), one)
        val three = ExtractionRunLineage.childOf(ExtractionRunRef("run-3"), two)
        listOf(root, one, two, three).forEach {
            store.save(ExtractionRunFixtures.runningRun(lineage = it))
        }

        val all = store.ancestorsOf(ExtractionRunFixtures.keyOf("run-3"), limit = 10)
        assertThat(all.map { it.ref.runId }).containsExactly("run-2", "run-1", "run-root")

        val bounded = store.ancestorsOf(ExtractionRunFixtures.keyOf("run-3"), limit = 2)
        assertThat(bounded.map { it.ref.runId }).containsExactly("run-2", "run-1")

        assertThat(store.ancestorsOf(ExtractionRunFixtures.keyOf("run-root"), limit = 10)).isEmpty()
        assertThat(store.ancestorsOf(ExtractionRunFixtures.keyOf("run-absent"), limit = 10)).isEmpty()
    }

    @Test
    fun `the chain walk stops at a parent the store does not hold`() {
        val missing = ExtractionRunLineage.root(ExtractionRunRef("run-missing"))
        val orphan = ExtractionRunLineage.childOf(ExtractionRunRef("run-orphan"), missing)
        store.save(ExtractionRunFixtures.runningRun(lineage = orphan))

        assertThat(store.ancestorsOf(ExtractionRunFixtures.keyOf("run-orphan"), limit = 10)).isEmpty()
    }

    @Test
    fun `the chain walk terminates on a cycle`() {
        // A value type rejects a run that is its own parent; a two-hop cycle needs the other run to
        // see, so only the store can catch it. A corrupt store is a real possibility. A store that
        // hangs on one is worse than a corrupt store.
        // root() and childOf() cannot mint a cycle. fromStoredFields can, which is the point of
        // this test: a cycle is something a store reads back, never something an application mints.
        val root = ExtractionRunRef("run-cycle-root")
        val a = ExtractionRunLineage.fromStoredFields(
            runRef = ExtractionRunRef("run-a"),
            rootRunRef = root,
            parentRunRef = ExtractionRunRef("run-b"),
        )
        val b = ExtractionRunLineage.fromStoredFields(
            runRef = ExtractionRunRef("run-b"),
            rootRunRef = root,
            parentRunRef = ExtractionRunRef("run-a"),
        )
        store.save(ExtractionRunFixtures.runningRun(lineage = a))
        store.save(ExtractionRunFixtures.runningRun(lineage = b))

        val walked = store.ancestorsOf(ExtractionRunFixtures.keyOf("run-a"), limit = 1_000)

        assertThat(walked.map { it.ref.runId }).containsExactly("run-b")
    }

    // ---- tenants ----

    @Test
    fun `every read fails closed across tenants`() {
        val root = ExtractionRunLineage.root(ExtractionRunRef("run-root"))
        val child = ExtractionRunLineage.childOf(ExtractionRunRef("run-child"), root)
        listOf(root, child).forEach {
            store.save(ExtractionRunFixtures.runningRun(contextId = OTHER_CONTEXT, lineage = it))
        }

        assertThat(store.findRun(ExtractionRunFixtures.keyOf("run-child", CONTEXT))).isNull()
        assertThat(store.findRun(ExtractionRunFixtures.keyOf("run-child", OTHER_CONTEXT))).isNotNull()
        assertThat(store.invocationsOf(ExtractionRunFixtures.keyOf("run-child", CONTEXT))).isEmpty()
        assertThat(store.runsInContext(CONTEXT, 10, null)).isEmpty()
        assertThat(store.childrenOf(CONTEXT, root.runRef, 10)).isEmpty()
        assertThat(store.runsOfRoot(CONTEXT, root.runRef, 10, null)).isEmpty()
        assertThat(store.ancestorsOf(ExtractionRunFixtures.keyOf("run-child", CONTEXT), 10)).isEmpty()
    }

    @Test
    fun `a chain walk stops rather than crossing into another tenant`() {
        val parent = ExtractionRunLineage.root(ExtractionRunRef("run-parent"))
        val child = ExtractionRunLineage.childOf(ExtractionRunRef("run-child"), parent)
        // The parent exists, in the neighbour's tenant. The child is mine.
        store.save(ExtractionRunFixtures.runningRun(contextId = OTHER_CONTEXT, lineage = parent))
        store.save(ExtractionRunFixtures.runningRun(contextId = CONTEXT, lineage = child))

        assertThat(store.ancestorsOf(ExtractionRunFixtures.keyOf("run-child", CONTEXT), 10)).isEmpty()
        assertThat(store.ancestorsOf(ExtractionRunFixtures.keyOf("run-child", OTHER_CONTEXT), 10)).isEmpty()
    }

    @Test
    fun `two tenants may hold the same run id without colliding`() {
        val mine = ExtractionRunFixtures.runningRun("run-shared", CONTEXT, STARTED_AT)
        val theirs = ExtractionRunFixtures.runningRun("run-shared", OTHER_CONTEXT, STARTED_AT.plusSeconds(60))
        store.save(mine)
        store.save(theirs)

        assertThat(store.findRun(mine.key())?.startedAt).isEqualTo(STARTED_AT)
        assertThat(store.findRun(theirs.key())?.startedAt).isEqualTo(STARTED_AT.plusSeconds(60))
        assertThat(store.runsInContext(CONTEXT, 10, null)).containsExactly(mine)
        assertThat(store.runsInContext(OTHER_CONTEXT, 10, null)).containsExactly(theirs)
    }

    // ---- invocation records ----

    @Test
    fun `a run records zero, one or many invocations`() {
        val zero = ExtractionRunFixtures.runningRun("run-zero")
        store.save(zero)
        assertThat(store.invocationsOf(zero.key())).isEmpty()

        val one = ExtractionRunFixtures.runningRun("run-one")
        store.save(one)
        store.recordInvocation(one.key(), ExtractionInvocationRecord.planned(0))
        assertThat(store.invocationsOf(one.key())).hasSize(1)

        val many = ExtractionRunFixtures.runningRun("run-many")
        store.save(many)
        ExtractionInvocationRecord.plan(4).forEach { store.recordInvocation(many.key(), it) }
        assertThat(store.invocationsOf(many.key()).map { it.invocationIndex })
            .containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `records come back in plan order however they arrived`() {
        val run = ExtractionRunFixtures.runningRun("run-order")
        store.save(run)
        // Completion order, which is not plan order.
        listOf(2, 0, 3, 1).forEach {
            store.recordInvocation(
                run.key(),
                ExtractionInvocationRecord(
                    id = ExtractionInvocationId.planned(it),
                    outcome = ExtractionInvocationOutcome.SUCCEEDED,
                ),
            )
        }

        assertThat(store.invocationsOf(run.key()).map { it.invocationIndex })
            .containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `an in-flight record updates in place, and a terminal one accepts only an identical replay`() {
        val run = ExtractionRunFixtures.runningRun("run-retry")
        store.save(run)
        val id = ExtractionInvocationId.planned(0)

        // While the attempt is in flight, dispatch details fill in as they're known — same
        // identity, more known about it, one record updated.
        store.recordInvocation(run.key(), ExtractionInvocationRecord(id = id, configuredService = "service-alpha"))
        store.recordInvocation(run.key(), ExtractionInvocationRecord(id = id, configuredService = "service-beta"))
        assertThat(store.invocationsOf(run.key())).hasSize(1)
        assertThat(store.invocationsOf(run.key()).single().configuredService).isEqualTo("service-beta")

        val terminal = ExtractionInvocationRecord(
            id = id,
            outcome = ExtractionInvocationOutcome.FAILED,
            configuredService = "service-beta",
        )
        store.recordInvocation(run.key(), terminal)

        store.recordInvocation(run.key(), terminal)
        assertThat(store.invocationsOf(run.key())).containsExactly(terminal)

        assertThatThrownBy {
            store.recordInvocation(run.key(), terminal.copy(configuredService = "service-gamma"))
        }.isInstanceOf(ExtractionRunConflictException::class.java)
        assertThat(store.invocationsOf(run.key())).containsExactly(terminal)

        // The next attempt at the same call: a second record, not a replacement.
        store.recordInvocation(run.key(), ExtractionInvocationRecord(id = id.nextAttempt()))
        assertThat(store.invocationsOf(run.key()).map { it.id })
            .containsExactly(ExtractionInvocationId(0, 1), ExtractionInvocationId(0, 2))
    }

    @Test
    fun `recording against a run nobody started is rejected`() {
        assertThatThrownBy {
            store.recordInvocation(
                ExtractionRunFixtures.keyOf("run-absent"),
                ExtractionInvocationRecord.planned(0),
            )
        }.isInstanceOf(ExtractionRunNotFoundException::class.java)
    }

    // ---- Java reachability ----

    @Test
    fun `the tenant-scoped reads are reachable without a value-class argument`() {
        val run = ExtractionRunFixtures.runningRun("run-java")
        store.save(run)

        // ContextId is a Kotlin value class, so a method taking one has a mangled JVM name. The
        // String form is the override point and is what a Java caller reaches.
        assertThat(store.runsInContext(CONTEXT.value, 10, null)).containsExactly(run)
        assertThat(store.findRun(ExtractionRunKey.of(CONTEXT.value, "run-java"))).isEqualTo(run)
        assertThat(store.childrenOf(CONTEXT.value, "run-java", 10)).isEmpty()
        assertThat(store.runsOfRoot(CONTEXT.value, "run-java", 10, null)).containsExactly(run)
    }
}
