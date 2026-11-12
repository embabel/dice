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

import com.embabel.dice.proposition.PropositionPersistenceResult
import org.jetbrains.annotations.ApiStatus
import java.util.Collections

/**
 * A step in storing a [PersistUnit], in the order the steps run.
 *
 * Steps may be added.
 *
 * EXPERIMENTAL. The API may change before 1.0.
 */
@ApiStatus.Experimental
enum class PersistStep {

    /** Checks and preparation before anything is written. */
    PREPARE,

    /** Creating and updating the unit's entities. */
    ENTITIES,

    /** Saving the unit's propositions. */
    PROPOSITIONS,

    /** Linking the stored propositions to the unit's run. */
    RUN_LINKS,

    /** Writing the chunk, proposition and entity relationships. */
    STRUCTURAL_RELATIONSHIPS,
}

/**
 * How storing a [PersistUnit] ended: [Stored] when the propositions were saved, [Failed] when a step
 * threw.
 *
 * EXPERIMENTAL. The API may change before 1.0.
 */
@ApiStatus.Experimental
sealed interface PersistOutcome {

    /** True for [Stored], false for [Failed]. */
    val isStored: Boolean

    /** What the run link store returned, or null when no link call was made. */
    val runLinks: Int?

    /** Set when a run link was asked for and not recorded. Null otherwise. */
    val lineageFailure: LineageNotRecordedException?

    /**
     * The persistence result of a stored unit.
     *
     * @return [Stored.persisted].
     * @throws Exception the original [Failed.cause], rethrown as is, whatever its type.
     */
    fun persistedOrThrow(): PropositionPersistenceResult

    /**
     * The unit's propositions were saved.
     *
     * @property persisted What the store returned for the saved propositions
     */
    class Stored internal constructor(
        val persisted: PropositionPersistenceResult,
        override val runLinks: Int?,
        override val lineageFailure: LineageNotRecordedException?,
    ) : PersistOutcome {

        init {
            require(runLinks == null || runLinks >= 0) { "runLinks must not be negative: $runLinks" }
        }

        /** Always true. */
        override val isStored: Boolean
            get() = true

        /** Returns [persisted]. */
        override fun persistedOrThrow(): PropositionPersistenceResult = persisted

        override fun toString(): String =
            "Stored(persisted=$persisted, runLinks=$runLinks, " +
                "lineageFailure=${lineageFailure?.let(::describe)})"
    }

    /**
     * A step threw while storing the unit.
     *
     * @property step The step that threw
     * @property cause The exception the step threw
     */
    class Failed internal constructor(
        val step: PersistStep,
        val cause: Exception,
        leftInStore: Set<PersistStep>,
        override val runLinks: Int?,
        override val lineageFailure: LineageNotRecordedException?,
    ) : PersistOutcome {

        init {
            require(runLinks == null || runLinks >= 0) { "runLinks must not be negative: $runLinks" }
        }

        /**
         * The steps whose writes may still be in the store, in step order. Empty means nothing from
         * the unit is stored. The set cannot be modified.
         */
        val leftInStore: Set<PersistStep> =
            Collections.unmodifiableSet(LinkedHashSet(PersistStep.entries.filter { it in leftInStore }))

        /** Always false. */
        override val isStored: Boolean
            get() = false

        /** Throws [cause], the same instance the step threw. */
        override fun persistedOrThrow(): PropositionPersistenceResult = throw cause

        override fun toString(): String =
            "Failed(step=$step, cause=${describe(cause)}, leftInStore=$leftInStore, " +
                "runLinks=$runLinks, lineageFailure=${lineageFailure?.let(::describe)})"
    }

    companion object {

        /**
         * A stored outcome with no run link call.
         *
         * @param persisted What the store returned for the saved propositions.
         * @return A [Stored] with null [runLinks] and null [lineageFailure].
         */
        @JvmStatic
        fun stored(persisted: PropositionPersistenceResult): Stored = Stored(persisted, null, null)

        /**
         * A stored outcome.
         *
         * @param persisted What the store returned for the saved propositions.
         * @param runLinks What the run link store returned, or null when no link call was made.
         * @param lineageFailure Set when a run link was asked for and not recorded.
         * @return A [Stored].
         * @throws IllegalArgumentException if [runLinks] is negative.
         */
        @JvmStatic
        fun stored(
            persisted: PropositionPersistenceResult,
            runLinks: Int?,
            lineageFailure: LineageNotRecordedException?,
        ): Stored = Stored(persisted, runLinks, lineageFailure)

        /**
         * A failed outcome with no run link call.
         *
         * @param step The step that threw.
         * @param cause The exception the step threw.
         * @param leftInStore The steps whose writes may still be in the store. Empty means nothing is.
         * @return A [Failed] with null [runLinks] and null [lineageFailure].
         */
        @JvmStatic
        fun failed(step: PersistStep, cause: Exception, leftInStore: Set<PersistStep>): Failed =
            Failed(step, cause, leftInStore, null, null)

        /**
         * A failed outcome.
         *
         * @param step The step that threw.
         * @param cause The exception the step threw.
         * @param leftInStore The steps whose writes may still be in the store. Empty means nothing is.
         * @param runLinks What the run link store returned, or null when no link call was made.
         * @param lineageFailure Set when a run link was asked for and not recorded.
         * @return A [Failed].
         * @throws IllegalArgumentException if [runLinks] is negative.
         */
        @JvmStatic
        fun failed(
            step: PersistStep,
            cause: Exception,
            leftInStore: Set<PersistStep>,
            runLinks: Int?,
            lineageFailure: LineageNotRecordedException?,
        ): Failed = Failed(step, cause, leftInStore, runLinks, lineageFailure)
    }
}

/**
 * An exception as its class name and message, for a `toString`.
 *
 * @param failure The exception to describe.
 * @return The class name, a colon and the message.
 */
private fun describe(failure: Throwable): String = "${failure.javaClass.name}: ${failure.message}"
