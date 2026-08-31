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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * The root reference is denormalized, so it is only worth having if it cannot drift: a parentless
 * run is its own root, a child takes its parent's root, and the public API — `root()` and
 * `childOf()`, the only public mints, with `copy()` private alongside the constructor — has no way
 * to build one that says otherwise. (Reflection still can; see the test below that demonstrates it.)
 */
class ExtractionRunLineageTest {

    private val runA = ExtractionRunRef("run-a")
    private val runB = ExtractionRunRef("run-b")
    private val runC = ExtractionRunRef("run-c")
    private val runD = ExtractionRunRef("run-d")

    @Test
    fun `a parentless run is its own root`() {
        val lineage = ExtractionRunLineage.root(runA)

        assertThat(lineage.rootRunRef).isEqualTo(runA)
        assertThat(lineage.parentRunRef).isNull()
        assertThat(lineage.isRoot).isTrue()
        assertThat(lineage.passIndex).isZero()
    }

    @Test
    fun `a child takes its parent's root and the next pass index`() {
        val parent = ExtractionRunLineage.root(runA)

        val child = ExtractionRunLineage.childOf(runB, parent)

        assertThat(child.parentRunRef).isEqualTo(runA)
        assertThat(child.rootRunRef).isEqualTo(runA)
        assertThat(child.isRoot).isFalse()
        assertThat(child.passIndex).isEqualTo(1)
    }

    @Test
    fun `the root carries down a chain rather than being recomputed at each hop`() {
        val first = ExtractionRunLineage.root(runA)
        val second = ExtractionRunLineage.childOf(runB, first)
        val third = ExtractionRunLineage.childOf(runC, second)
        val fourth = ExtractionRunLineage.childOf(runD, third)

        // Every run in the lineage names the same root, so "everything in this lineage" is one
        // indexed read instead of a walk back up the parent chain.
        assertThat(listOf(first, second, third, fourth).map { it.rootRunRef })
            .containsExactly(runA, runA, runA, runA)
        assertThat(listOf(first, second, third, fourth).map { it.parentRunRef })
            .containsExactly(null, runA, runB, runC)
        assertThat(listOf(first, second, third, fourth).map { it.passIndex })
            .containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `parent and supersession are separate axes`() {
        val parent = ExtractionRunLineage.root(runA)

        val replacementOfASibling = ExtractionRunLineage.childOf(
            runRef = runB,
            parent = parent,
            supersedesRunRef = runC,
        )
        val replacementWithNoParent = ExtractionRunLineage.root(
            runRef = runD,
            supersedesRunRef = runC,
        )

        assertThat(replacementOfASibling.parentRunRef).isEqualTo(runA)
        assertThat(replacementOfASibling.supersedesRunRef).isEqualTo(runC)
        // A re-extraction replaces an earlier run without continuing it: superseded, no parent,
        // and still its own root.
        assertThat(replacementWithNoParent.parentRunRef).isNull()
        assertThat(replacementWithNoParent.supersedesRunRef).isEqualTo(runC)
        assertThat(replacementWithNoParent.rootRunRef).isEqualTo(runD)
    }

    @Test
    fun `a run cannot be its own parent or supersede itself`() {
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRunLineage.childOf(runRef = runA, parent = ExtractionRunLineage.root(runA))
        }.withMessageContaining("its own parent")

        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRunLineage.root(runRef = runA, supersedesRunRef = runA)
        }.withMessageContaining("supersede itself")
    }

    @Test
    fun `a negative pass index is rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRunLineage.root(runA, passIndex = -1)
        }.withMessageContaining("passIndex")
    }

    @Test
    fun `a root that contradicts the parent chain is unrepresentable through the public API`() {
        // PR #95 review comment: any non-self root used to pass the constructor, whether or not it
        // named the actual parent's root, and nothing verified it. root() and childOf() are now the
        // only way to mint a lineage; childOf() derives rootRunRef from the actual parent lineage
        // it is handed, and root() sets it to the run's own ref, so there is no parameter through
        // which a caller could hand in an impostor root.
        val ctor = ExtractionRunLineage::class.primaryConstructor
        assertThat(ctor).isNotNull()
        assertThat(ctor!!.visibility).isEqualTo(KVisibility.PRIVATE)

        // copy() follows the constructor's visibility (@ConsistentCopyVisibility on the class), so
        // the same closure applies to a caller who already holds a lineage and tries to overwrite
        // just the root.
        val copyFunction = ExtractionRunLineage::class.declaredFunctions.single { it.name == "copy" }
        assertThat(copyFunction.visibility).isEqualTo(KVisibility.PRIVATE)
    }

    @Test
    fun `reflection can still construct a lineage whose root contradicts its parent`() {
        // The fix closes the public API, root() and childOf(). The JVM still lets reflection call
        // a private constructor directly, the same route a permissive deserializer would take. This
        // proves only that: the call below succeeds and the value it hands back names a root that
        // runA's own lineage disagrees with.
        val ctor = ExtractionRunLineage::class.primaryConstructor!!
        ctor.isAccessible = true

        val impostor = ctor.call(runC, runD, runA, null, 1)

        assertThat(impostor.parentRunRef).isEqualTo(runA)
        assertThat(impostor.rootRunRef).isEqualTo(runD)
        // runA's own lineage says its root is runA, not runD — the impostor is representable
        // anyway, because this constructor call never saw runA's lineage, only its ref.
        assertThat(ExtractionRunLineage.root(runA).rootRunRef).isNotEqualTo(impostor.rootRunRef)
    }

    @Test
    fun `a parentless run whose root names another run is still rejected, reached the one way left`() {
        // root()/childOf() can never reach this state — root() always sets rootRunRef to its own
        // ref — so the only way left to exercise the "no parent is its own root" init guard is
        // the same reflective call a permissive deserializer would make.
        val ctor = ExtractionRunLineage::class.primaryConstructor!!
        ctor.isAccessible = true

        // Kotlin reflection routes this through java.lang.reflect.Constructor, so the init
        // block's exception arrives wrapped in an InvocationTargetException rather than bare.
        val thrown = catchThrowable { ctor.call(runA, runB, null, null, 0) }

        assertThat(thrown).isInstanceOf(InvocationTargetException::class.java)
        assertThat(thrown.cause)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no parent is its own root")
    }

    @Test
    fun `a run cannot claim to be the root of a lineage it is already a member of`() {
        // Unlike the parentless case above, this guard ("a run with a parent takes its parent's
        // root") is reachable through the public API: childOf() derives rootRunRef from the parent
        // it's given, but nothing stops a caller from reusing an ancestor's own ref as the "new" run.
        val root = ExtractionRunLineage.root(runA)
        val child = ExtractionRunLineage.childOf(runB, root)

        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRunLineage.childOf(runRef = runA, parent = child)
        }.withMessageContaining("takes its parent's root")
    }

    @Test
    fun `the run exposes its lineage without a walk`() {
        val run = ExtractionRun(
            contextId = ExtractionRunFixtures.CONTEXT,
            lineage = ExtractionRunLineage.childOf(runB, ExtractionRunLineage.root(runA)),
            status = ExtractionRunStatus.RUNNING,
            startedAt = ExtractionRunFixtures.STARTED_AT,
        )

        assertThat(run.ref).isEqualTo(runB)
        assertThat(run.rootRef).isEqualTo(runA)
        assertThat(run.parentRef).isEqualTo(runA)
        assertThat(run.isRoot).isFalse()
        assertThat(ExtractionRunFixtures.startedRun().isRoot).isTrue()
        assertThat(ExtractionRunFixtures.startedRun().rootRef).isEqualTo(ExtractionRunFixtures.RUN)
    }
}
