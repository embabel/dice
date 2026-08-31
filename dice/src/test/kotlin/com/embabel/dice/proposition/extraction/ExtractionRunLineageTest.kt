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
import org.junit.jupiter.api.Test

/**
 * The root reference is denormalized, so it is only worth having if it cannot drift: a parentless
 * run is its own root, a child takes its parent's root, and the constructor rejects every
 * combination that would make the field say otherwise.
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
    fun `a parentless run whose root is some other run is rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRunLineage(runRef = runA, rootRunRef = runB)
        }.withMessageContaining("no parent is its own root")
    }

    @Test
    fun `a run with a parent cannot claim to be its own root`() {
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRunLineage(runRef = runA, rootRunRef = runA, parentRunRef = runB)
        }.withMessageContaining("takes its parent's root")
    }

    @Test
    fun `a run cannot be its own parent or supersede itself`() {
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRunLineage(runRef = runA, rootRunRef = runB, parentRunRef = runA)
        }.withMessageContaining("its own parent")

        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRunLineage(runRef = runA, rootRunRef = runA, supersedesRunRef = runA)
        }.withMessageContaining("supersede itself")
    }

    @Test
    fun `a negative pass index is rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRunLineage.root(runA, passIndex = -1)
        }.withMessageContaining("passIndex")
    }

    @Test
    fun `copying a lineage still has to satisfy the root invariant`() {
        val child = ExtractionRunLineage.childOf(runB, ExtractionRunLineage.root(runA))

        // A data class copy runs the same init block, so nothing can drop the parent and keep a
        // root that no longer follows from it.
        assertThatIllegalArgumentException().isThrownBy {
            child.copy(parentRunRef = null)
        }.withMessageContaining("no parent is its own root")
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
