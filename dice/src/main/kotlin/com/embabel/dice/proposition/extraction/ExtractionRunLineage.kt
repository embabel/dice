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

import org.jetbrains.annotations.ApiStatus

/**
 * Where a run sits among the runs around it: its own reference, its parent, its root, what it
 * supersedes, and which pass it is.
 *
 * Parent and supersession are two different axes and stay separate fields. A parent is the run
 * this one continues from — a later pass reading the entities its parent resolved. A superseded
 * run is one this one replaces — a re-extraction after the prompt changed. A run can have one of
 * each, both, or neither.
 *
 * **The root reference is denormalized on purpose.** It is set once when the lineage is minted:
 * a run with no parent is its own root, and a run with a parent takes its parent's root. That
 * makes "everything in this lineage" a single indexed read on one property, instead of walking
 * the parent chain a hop at a time. OpenLineage's `ParentRunFacet` does the same thing — it
 * carries an optional `root` alongside the immediate parent, so consumers do not have to walk —
 * and deep pass-and-retry chains are exactly where walking hurts.
 *
 * The root only stays true if it is never recomputed later, so it is fixed at construction. Use
 * [root] and [childOf] and the arithmetic is done for you; the constructor rejects the
 * combinations that would make the field a lie.
 *
 * **What is checked here, and what is not.** The constructor rejects self-reference on both axes
 * and rejects a root that contradicts the presence of a parent. It cannot see a cycle of length
 * two or more, because a value type holds one run and cycle detection needs the other runs. The
 * store that walks these chains is where bounded, cycle-safe traversal lives.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property runRef This run
 * @property rootRunRef The oldest run in this lineage, which is [runRef] itself when there is no
 *   parent
 * @property parentRunRef The run this one continues from, or null
 * @property supersedesRunRef The run this one replaces, or null
 * @property passIndex Which pass over the material this is, counting from zero
 */
@ApiStatus.Experimental
data class ExtractionRunLineage @JvmOverloads constructor(
    val runRef: ExtractionRunRef,
    val rootRunRef: ExtractionRunRef,
    val parentRunRef: ExtractionRunRef? = null,
    val supersedesRunRef: ExtractionRunRef? = null,
    val passIndex: Int = 0,
) {

    init {
        require(passIndex >= 0) { "passIndex must not be negative, was $passIndex" }
        require(parentRunRef != runRef) { "a run cannot be its own parent" }
        require(supersedesRunRef != runRef) { "a run cannot supersede itself" }
        if (parentRunRef == null) {
            require(rootRunRef == runRef) { "a run with no parent is its own root" }
        } else {
            require(rootRunRef != runRef) { "a run with a parent takes its parent's root, so it is not its own root" }
        }
    }

    /** True when this run starts a lineage. */
    val isRoot: Boolean
        get() = parentRunRef == null

    companion object {

        /**
         * Mints the lineage of a run that starts one: its own root, no parent.
         *
         * A first extraction of some material takes this. So does a re-extraction that replaces
         * an earlier run without continuing it — pass [supersedesRunRef] and the supersession is
         * recorded without making the replaced run a parent.
         */
        @JvmStatic
        @JvmOverloads
        fun root(
            runRef: ExtractionRunRef,
            supersedesRunRef: ExtractionRunRef? = null,
            passIndex: Int = 0,
        ): ExtractionRunLineage = ExtractionRunLineage(
            runRef = runRef,
            rootRunRef = runRef,
            parentRunRef = null,
            supersedesRunRef = supersedesRunRef,
            passIndex = passIndex,
        )

        /**
         * Mints the lineage of a run that continues [parent], carrying the parent's root forward
         * and defaulting to the next pass index.
         */
        @JvmStatic
        @JvmOverloads
        fun childOf(
            runRef: ExtractionRunRef,
            parent: ExtractionRunLineage,
            supersedesRunRef: ExtractionRunRef? = null,
            passIndex: Int = parent.passIndex + 1,
        ): ExtractionRunLineage = ExtractionRunLineage(
            runRef = runRef,
            rootRunRef = parent.rootRunRef,
            parentRunRef = parent.runRef,
            supersedesRunRef = supersedesRunRef,
            passIndex = passIndex,
        )
    }
}
