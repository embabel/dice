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
import com.embabel.dice.provenance.SourceRevisionRef
import org.jetbrains.annotations.ApiStatus
import java.time.Instant
import java.util.Collections

/**
 * Identifies one run inside one tenant.
 *
 * A run id is host-minted and DICE never assumes it is globally unique, so the tenant travels with
 * it everywhere. Two tenants that both mint the run id `run-1` have two different runs, and this
 * type is why nothing can accidentally treat them as one.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property contextId The tenant that owns the run
 * @property runRef The run
 */
@ApiStatus.Experimental
data class ExtractionRunKey(
    val contextId: ContextId,
    val runRef: ExtractionRunRef,
) {

    /** The tenant id as a plain string, for Java callers, since `ContextId` is a value class. */
    fun getContextIdValue(): String = contextId.value

    companion object {

        /**
         * Java-friendly factory taking both halves as plain strings.
         *
         * `ContextId` is a Kotlin value class, so the constructor has a mangled JVM name. Every
         * read on `ExtractionRunStore` keys on this type, so without a factory the store would be
         * unreachable from Java.
         */
        @JvmStatic
        fun of(contextIdValue: String, runId: String): ExtractionRunKey =
            ExtractionRunKey(ContextId(contextIdValue), ExtractionRunRef(runId))
    }
}

/**
 * The durable record of one extraction execution.
 *
 * A run answers "what produced this claim, under what, and how did it go?" — the profile and
 * prompt and schema versions in force, which revisions of which sources were read, which model
 * was asked for and what the provider reported back, how far it got, and what went wrong. It is
 * the header; the propositions it produced are attributed to it by a separate relation, and source
 * grounding stays what it was.
 *
 * **Two rules shape the type.**
 *
 * The first is that requested and observed are different types. What a run asked a model for is
 * [requestedModel], one [ExtractionRequestedModelConfig] on the header. What actually happened is
 * an [ExtractionInvocationRecord] per attempt, holding usage, timing, the service it went to, and
 * whatever the provider reported. An invocation record has no field that can hold a requested
 * value, so nothing can quietly present a setting as an observation.
 *
 * The second is that the run holds no content. No prompts, no source text, no responses, no
 * user or session objects, no provider SDK payloads, no extension maps. What a host would have
 * needed those for is covered by digests it can compare ([fingerprints]) and by bounded
 * pseudonymous tokens it can group by ([subjectRefs]). Failures are said in a closed vocabulary of
 * codes, stages and numbers, so there is no text field on a run for an exception message to land
 * in.
 *
 * **What is not decided here.** Which status transitions are legal, which are compare-and-set, and
 * what a store does with a repeated terminal write belong to the run store contract in the next
 * slice. This type checks that a finish does not precede a start and stops there; it does not
 * require, for instance, that a [ExtractionRunStatus.COMPLETED] run has a [finishedAt], so the
 * state machine defines that once instead of twice.
 *
 * Collections are copied on the way in, always, including empty ones — an empty mutable list a
 * caller keeps a handle on is the same aliasing bug as a full one, and it fails later and stranger.
 * The copies are unmodifiable, so the run a caller reads back cannot be edited through the list
 * either.
 *
 * This is a plain class rather than a data class on purpose. A data class has to declare its
 * collection parameters as properties, which means the field is the caller's list and there is
 * nowhere to copy it; its generated `copy` and `componentN` methods would also pin an ABI across
 * eighteen fields while #67 is still moving. Equality and hash are written out over every
 * component instead.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property contextId The tenant that owns this run
 * @property lineage This run's reference, its root, its parent, what it supersedes, and its pass
 * @property status Where the run stands
 * @property startedAt When the run began
 * @property finishedAt When it reached a terminal state, or null while it has not
 * @property profile The content profile version in force
 * @property sourceRevisions Which revisions of which sources were read, in the order they were read
 * @property fingerprints Digests of the prompt, schema and metamodel in force
 * @property runtime What code ran it, and where
 * @property requestedModel What the run asked a model for
 * @property subjectRefs Pseudonymous references to whose work this was
 * @property experimentRef The experiment this run belongs to
 * @property cohortRef The arm within that experiment
 * @property replayFidelity How much of this run someone could set up again from what it recorded
 * @property counts How much the run got through
 * @property invocations One record per attempt at each planned model call, normalized to plan order
 * @property failures Bounded record of what went wrong, in the failure vocabulary
 * @property version The compare-and-set generation [ExtractionRunStore.save] checks this header
 *   against. A run that has never been saved, and the run its first accepted save produces, both
 *   carry 0 — the first save inserts the row, and there is no earlier generation for it to raise
 *   past. A store rejects a first save naming any other value. Every later save [save] accepts that
 *   actually changes the header raises it by one; a save whose content already matches what is
 *   stored is accepted too, as a no-op replay, and leaves the generation exactly where it stood.
 *   [ExtractionRunStore.recordInvocation] never changes it, since an invocation record writes a
 *   child row of its own — and [ExtractionRunTransition.applyTo] carries whatever value the run
 *   already has, because a terminal run takes no more saves and nothing compares its version again
 */
@ApiStatus.Experimental
class ExtractionRun @JvmOverloads constructor(
    val contextId: ContextId,
    val lineage: ExtractionRunLineage,
    val status: ExtractionRunStatus,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val profile: ExtractionContentProfileRef? = null,
    sourceRevisions: List<SourceRevisionRef> = emptyList(),
    val fingerprints: ExtractionRunFingerprints = ExtractionRunFingerprints(),
    val runtime: ExtractionRuntimeIdentity = ExtractionRuntimeIdentity(),
    val requestedModel: ExtractionRequestedModelConfig? = null,
    val subjectRefs: ExtractionRunSubjectRefs = ExtractionRunSubjectRefs(),
    val experimentRef: ExtractionExperimentRef? = null,
    val cohortRef: ExtractionCohortRef? = null,
    val replayFidelity: ExtractionReplayFidelity = ExtractionReplayFidelity.NONE,
    val counts: ExtractionRunCounts = ExtractionRunCounts(),
    invocations: List<ExtractionInvocationRecord> = emptyList(),
    failures: List<ExtractionFailure> = emptyList(),
    val version: Long = 0,
) {

    /** Which revisions of which sources this run read, in order. */
    val sourceRevisions: List<SourceRevisionRef> =
        Collections.unmodifiableList(ArrayList(sourceRevisions))

    /**
     * One record per attempt at each planned call, always in plan order: call 0 before call 1, and
     * within a call, first attempt before second.
     *
     * **Normalized here rather than left as the caller listed it.** Records arrive in the order
     * calls came back, which is not the order they were planned in and is not a fact about the run —
     * two runs that made the same calls and got the same answers in a different sequence are the
     * same run. Since [equals] compares this list element by element, leaving the caller's order
     * alone would make those two runs unequal, and it would make a durable backend disagree with the
     * in-memory one on the same call sequence: a store keeps identified rows and reads them back in
     * plan order, while an in-memory store would hand back the order it was given.
     *
     * [sourceRevisions] is deliberately not normalized the same way. The order sources were read in
     * is data about the run.
     */
    val invocations: List<ExtractionInvocationRecord> =
        Collections.unmodifiableList(
            invocations.sortedWith(compareBy({ it.invocationIndex }, { it.attempt })),
        )

    /** What went wrong, bounded and said in the failure vocabulary. */
    val failures: List<ExtractionFailure> =
        Collections.unmodifiableList(ArrayList(failures))

    init {
        require(version >= 0) { "version must not be negative, was $version" }

        require(finishedAt == null || !finishedAt.isBefore(startedAt)) {
            "finishedAt must not be before startedAt"
        }

        require(this.sourceRevisions.size <= ExtractionRunLimits.MAX_SOURCE_REVISIONS) {
            "a run may record at most ${ExtractionRunLimits.MAX_SOURCE_REVISIONS} source revisions, " +
                "was ${this.sourceRevisions.size}"
        }
        require(this.sourceRevisions.distinct().size == this.sourceRevisions.size) {
            "sourceRevisions must be distinct; a run reads each source revision once"
        }
        // sourceKey and sourceRevision carry no length check here. Their one bound lives on
        // SourceRevisionRef, the type that owns those strings (docs/design/source-revisions.md),
        // and a run accepts whatever that type accepts. Only what belongs to this type — how many
        // revisions, and that they are distinct — is checked here.

        require(this.invocations.size <= ExtractionRunLimits.MAX_INVOCATIONS) {
            "a run may record at most ${ExtractionRunLimits.MAX_INVOCATIONS} invocation records, " +
                "was ${this.invocations.size}"
        }
        val identities = this.invocations.map { it.id }
        require(identities.distinct().size == identities.size) {
            "invocation records must have distinct (invocationIndex, attempt) identities"
        }

        require(this.failures.size <= ExtractionRunLimits.MAX_FAILURES) {
            "a run may record at most ${ExtractionRunLimits.MAX_FAILURES} failures, was ${this.failures.size}"
        }
        // A failure that names an attempt the run has no record of is a dangling audit reference:
        // it reads as evidence about a call, and nothing can join it to one. Rejected here so the
        // pair arrives together or not at all.
        val identitySet = identities.toSet()
        this.failures.forEach { failure ->
            val invocation = failure.invocation ?: return@forEach
            require(invocation in identitySet) {
                "a failure names $invocation, which this run has no invocation record for"
            }
        }
    }

    /** This run's reference. */
    val ref: ExtractionRunRef
        get() = lineage.runRef

    /** The oldest run in this run's lineage, which is [ref] itself when this run has no parent. */
    val rootRef: ExtractionRunRef
        get() = lineage.rootRunRef

    /** The run this one continues from, or null. */
    val parentRef: ExtractionRunRef?
        get() = lineage.parentRunRef

    /** True when this run starts its lineage. */
    val isRoot: Boolean
        get() = lineage.isRoot

    /** The tenant-qualified identity a store keys this run on. */
    fun key(): ExtractionRunKey = ExtractionRunKey(contextId, ref)

    /** The tenant id as a plain string, for Java callers, since `ContextId` is a value class. */
    fun getContextIdValue(): String = contextId.value

    /**
     * The invocation records ordered by the plan: call 0 before call 1, and within a call, first
     * attempt before second.
     *
     * [invocations] is already in that order — it is normalized at construction — so this returns
     * it unchanged. Kept as a named call because that is what a caller asking for plan order should
     * be able to say, and because it is what the stores promise.
     */
    fun invocationsInPlanOrder(): List<ExtractionInvocationRecord> = invocations

    /** Every attempt at the call at [invocationIndex], earliest attempt first. */
    fun attemptsOf(invocationIndex: Int): List<ExtractionInvocationRecord> =
        invocations.filter { it.invocationIndex == invocationIndex }.sortedBy { it.attempt }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractionRun) return false
        return contextId == other.contextId &&
            lineage == other.lineage &&
            status == other.status &&
            startedAt == other.startedAt &&
            finishedAt == other.finishedAt &&
            profile == other.profile &&
            sourceRevisions == other.sourceRevisions &&
            fingerprints == other.fingerprints &&
            runtime == other.runtime &&
            requestedModel == other.requestedModel &&
            subjectRefs == other.subjectRefs &&
            experimentRef == other.experimentRef &&
            cohortRef == other.cohortRef &&
            replayFidelity == other.replayFidelity &&
            counts == other.counts &&
            invocations == other.invocations &&
            failures == other.failures &&
            version == other.version
    }

    override fun hashCode(): Int {
        var result = contextId.hashCode()
        result = 31 * result + lineage.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + startedAt.hashCode()
        result = 31 * result + (finishedAt?.hashCode() ?: 0)
        result = 31 * result + (profile?.hashCode() ?: 0)
        result = 31 * result + sourceRevisions.hashCode()
        result = 31 * result + fingerprints.hashCode()
        result = 31 * result + runtime.hashCode()
        result = 31 * result + (requestedModel?.hashCode() ?: 0)
        result = 31 * result + subjectRefs.hashCode()
        result = 31 * result + (experimentRef?.hashCode() ?: 0)
        result = 31 * result + (cohortRef?.hashCode() ?: 0)
        result = 31 * result + replayFidelity.hashCode()
        result = 31 * result + counts.hashCode()
        result = 31 * result + invocations.hashCode()
        result = 31 * result + failures.hashCode()
        result = 31 * result + version.hashCode()
        return result
    }

    /**
     * A summary: identity, lineage, state, and sizes.
     *
     * It leaves out the digests and the reference tokens, so a run logged at an error site does not
     * spread them. Anything that needs every field reads the properties.
     */
    override fun toString(): String =
        "ExtractionRun(contextId=${contextId.value}, runId=${ref.runId}, rootRunId=${rootRef.runId}, " +
            "parentRunId=${parentRef?.runId}, pass=${lineage.passIndex}, status=$status, " +
            "startedAt=$startedAt, finishedAt=$finishedAt, sourceRevisions=${sourceRevisions.size}, " +
            "invocations=${invocations.size}, failures=${failures.size}, replayFidelity=$replayFidelity, " +
            "version=$version)"

    companion object {

        /**
         * Java-friendly factory taking the tenant as a plain string.
         *
         * `ContextId` is a Kotlin value class, so a factory that took one directly would have a
         * mangled JVM name. Kotlin callers can use the constructor.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            contextIdValue: String,
            lineage: ExtractionRunLineage,
            status: ExtractionRunStatus,
            startedAt: Instant,
            finishedAt: Instant? = null,
            profile: ExtractionContentProfileRef? = null,
            sourceRevisions: List<SourceRevisionRef> = emptyList(),
            fingerprints: ExtractionRunFingerprints = ExtractionRunFingerprints(),
            runtime: ExtractionRuntimeIdentity = ExtractionRuntimeIdentity(),
            requestedModel: ExtractionRequestedModelConfig? = null,
            subjectRefs: ExtractionRunSubjectRefs = ExtractionRunSubjectRefs(),
            experimentRef: ExtractionExperimentRef? = null,
            cohortRef: ExtractionCohortRef? = null,
            replayFidelity: ExtractionReplayFidelity = ExtractionReplayFidelity.NONE,
            counts: ExtractionRunCounts = ExtractionRunCounts(),
            invocations: List<ExtractionInvocationRecord> = emptyList(),
            failures: List<ExtractionFailure> = emptyList(),
            version: Long = 0,
        ): ExtractionRun = ExtractionRun(
            contextId = ContextId(contextIdValue),
            lineage = lineage,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            profile = profile,
            sourceRevisions = sourceRevisions,
            fingerprints = fingerprints,
            runtime = runtime,
            requestedModel = requestedModel,
            subjectRefs = subjectRefs,
            experimentRef = experimentRef,
            cohortRef = cohortRef,
            replayFidelity = replayFidelity,
            counts = counts,
            invocations = invocations,
            failures = failures,
            version = version,
        )
    }
}
