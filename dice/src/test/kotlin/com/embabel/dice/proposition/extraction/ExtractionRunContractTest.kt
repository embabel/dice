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
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.CONTEXT
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.OTHER_CONTEXT
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.REVISION_ONE
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.REVISION_TWO
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.RUN
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.STARTED_AT
import com.embabel.dice.provenance.SourceIdentityBounds
import com.embabel.dice.provenance.SourceRevisionRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What an [ExtractionRun] accepts, what it rejects, what it copies, and when two of them are the
 * same run.
 */
class ExtractionRunContractTest {

    @Test
    fun `a started run needs a tenant, a lineage, a status and a start`() {
        val run = ExtractionRunFixtures.startedRun()

        assertThat(run.contextId).isEqualTo(CONTEXT)
        assertThat(run.ref).isEqualTo(RUN)
        assertThat(run.status).isEqualTo(ExtractionRunStatus.RUNNING)
        assertThat(run.startedAt).isEqualTo(STARTED_AT)
        assertThat(run.finishedAt).isNull()
        // Everything else defaults to absent or empty, so an empty run is a run.
        assertThat(run.sourceRevisions).isEmpty()
        assertThat(run.invocations).isEmpty()
        assertThat(run.failures).isEmpty()
        assertThat(run.profile).isNull()
        assertThat(run.requestedModel).isNull()
        assertThat(run.replayFidelity).isEqualTo(ExtractionReplayFidelity.NONE)
        assertThat(run.counts).isEqualTo(ExtractionRunCounts())
    }

    @Test
    fun `a populated run reads back every field it was given`() {
        val run = ExtractionRunFixtures.populatedRun()

        assertThat(run.status).isEqualTo(ExtractionRunStatus.FAILED)
        assertThat(run.status.isTerminal).isTrue()
        assertThat(run.finishedAt).isEqualTo(ExtractionRunFixtures.FINISHED_AT)
        assertThat(run.profile).isEqualTo(ExtractionContentProfileRef("house-style", "v3"))
        assertThat(run.sourceRevisions).containsExactly(REVISION_ONE, REVISION_TWO)
        assertThat(run.fingerprints.schemaFingerprint).isEqualTo("sha256:a7c40e19")
        assertThat(run.runtime.extractor).isEqualTo("LlmPropositionExtractor")
        assertThat(run.requestedModel?.temperature).isEqualTo(0.2)
        assertThat(run.subjectRefs.actor).isEqualTo(ExtractionActorRef("actor:7f19aa02"))
        assertThat(run.experimentRef).isEqualTo(ExtractionExperimentRef("exp:prompt-v3"))
        assertThat(run.cohortRef).isEqualTo(ExtractionCohortRef("cohort:treatment"))
        assertThat(run.replayFidelity).isEqualTo(ExtractionReplayFidelity.APPROXIMATE)
        assertThat(run.counts.propositionsPersisted).isEqualTo(11)
        assertThat(run.invocations).hasSize(2)
        assertThat(run.failures).hasSize(1)
        assertThat(run.failures.single().code).isEqualTo(ExtractionFailureCode.DECODE_FAILED)
        assertThat(run.failures.single().invocation).isEqualTo(ExtractionInvocationId(1, 2))
    }

    @Test
    fun `a finish cannot precede a start`() {
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionRun(
                contextId = CONTEXT,
                lineage = ExtractionRunLineage.root(RUN),
                status = ExtractionRunStatus.COMPLETED,
                startedAt = STARTED_AT,
                finishedAt = STARTED_AT.minusSeconds(1),
            )
        }.withMessageContaining("finishedAt")

        // The same instant is fine: a run that did nothing can finish in the tick it started.
        assertThat(
            ExtractionRun(
                contextId = CONTEXT,
                lineage = ExtractionRunLineage.root(RUN),
                status = ExtractionRunStatus.COMPLETED,
                startedAt = STARTED_AT,
                finishedAt = STARTED_AT,
            ).finishedAt,
        ).isEqualTo(STARTED_AT)
    }

    @Test
    fun `which status may sit with which timing is left to the lifecycle`() {
        // Deliberate: the run store contract owns the transitions and the definition of COMPLETED,
        // so this type does not half-encode them. A terminal status with no finish time is
        // constructible here and is the state machine's to reject.
        val terminalWithoutFinish = ExtractionRun(
            contextId = CONTEXT,
            lineage = ExtractionRunLineage.root(RUN),
            status = ExtractionRunStatus.CANCELLED,
            startedAt = STARTED_AT,
        )

        assertThat(terminalWithoutFinish.finishedAt).isNull()
        assertThat(terminalWithoutFinish.status.isTerminal).isTrue()
        assertThat(ExtractionRunStatus.entries.map { it.name })
            .containsExactly("RUNNING", "COMPLETED", "FAILED", "CANCELLED")
        assertThat(ExtractionRunStatus.entries.filter { it.isTerminal })
            .containsExactly(
                ExtractionRunStatus.COMPLETED,
                ExtractionRunStatus.FAILED,
                ExtractionRunStatus.CANCELLED,
            )
    }

    @Test
    fun `source revisions are ordered and distinct`() {
        val run = runWith(sourceRevisions = listOf(REVISION_TWO, REVISION_ONE))

        assertThat(run.sourceRevisions).containsExactly(REVISION_TWO, REVISION_ONE)

        assertThatIllegalArgumentException().isThrownBy {
            runWith(sourceRevisions = listOf(REVISION_ONE, REVISION_ONE))
        }.withMessageContaining("distinct")
    }

    @Test
    fun `every collection the run stores is bounded`() {
        assertThatIllegalArgumentException().isThrownBy {
            runWith(
                sourceRevisions = (0..ExtractionRunLimits.MAX_SOURCE_REVISIONS)
                    .map { SourceRevisionRef("uri:doc-$it", "rev-1") },
            )
        }.withMessageContaining("source revisions")

        assertThatIllegalArgumentException().isThrownBy {
            runWith(
                failures = (0..ExtractionRunLimits.MAX_FAILURES)
                    .map { ExtractionFailure(code = ExtractionFailureCode.INTERNAL, at = STARTED_AT) },
            )
        }.withMessageContaining("failures")

        assertThatIllegalArgumentException().isThrownBy {
            runWith(
                invocations = (0..ExtractionRunLimits.MAX_INVOCATIONS)
                    .map { ExtractionInvocationRecord.planned(it) },
            )
        }.withMessageContaining("invocation records")
    }

    @Test
    fun `run recording adds no per-field length cap on a source revision`() {
        // Repro for the PR #95 review comment, round 2: a value a query happily works with must
        // not blow up on the way into run recording. The bound on a source key and a source
        // revision belongs to SourceRevisionRef, the type that owns those strings, and the run
        // holds itself to whatever that type accepts. So the test builds the biggest revision the
        // owning type will mint and records it: both halves are far past the run's own
        // MAX_IDENTIFIER_LENGTH, which proves the run's identifier cap does not reach them.
        // (The run still rejects duplicates and more than MAX_SOURCE_REVISIONS of them — see
        // `every collection the run stores is bounded` above — this test is about length only.)
        val revision = SourceRevisionRef(
            "u".repeat(SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH),
            "r".repeat(SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH),
        )
        assertThat(revision.sourceKey.length).isGreaterThan(ExtractionRunLimits.MAX_IDENTIFIER_LENGTH)
        assertThat(revision.sourceRevision.length).isGreaterThan(ExtractionRunLimits.MAX_IDENTIFIER_LENGTH)

        assertThat(runWith(sourceRevisions = listOf(revision)).sourceRevisions)
            .containsExactly(revision)

        // A long URL as a source key is exactly the kind of value SourceRevisionRef has to carry.
        val longUrlKey = "https://example.test/" + "segment/".repeat(200)
        assertThat(longUrlKey.length).isGreaterThan(ExtractionRunLimits.MAX_IDENTIFIER_LENGTH)
        assertThat(runWith(sourceRevisions = listOf(SourceRevisionRef(longUrlKey, "rev-1"))).sourceRevisions)
            .hasSize(1)

        // And there is no constant here for either field, so a cap cannot creep back in on this
        // side without someone noticing.
        assertThat(ExtractionRunLimits::class.java.declaredFields.map { it.name })
            .doesNotContain("MAX_SOURCE_KEY_LENGTH", "MAX_SOURCE_REVISION_LENGTH")
    }

    @Test
    fun `a failure cannot name an attempt the run has no record of`() {
        val plan = ExtractionInvocationRecord.plan(2)

        // A failure tied to an attempt the run does record is the normal case.
        assertThat(
            runWith(
                invocations = plan,
                failures = listOf(
                    ExtractionFailure(
                        code = ExtractionFailureCode.MODEL_TIMEOUT,
                        at = STARTED_AT,
                        invocation = ExtractionInvocationId.planned(1),
                    ),
                ),
            ).failures,
        ).hasSize(1)

        // An index outside the plan is a dangling audit reference: it reads as evidence about a
        // call and nothing can join it to one.
        assertThatIllegalArgumentException().isThrownBy {
            runWith(
                invocations = plan,
                failures = listOf(
                    ExtractionFailure(
                        code = ExtractionFailureCode.MODEL_TIMEOUT,
                        at = STARTED_AT,
                        invocation = ExtractionInvocationId.planned(7),
                    ),
                ),
            )
        }.withMessageContaining("invocation 7 attempt 1")

        // So is the right call at an attempt that was never made.
        assertThatIllegalArgumentException().isThrownBy {
            runWith(
                invocations = plan,
                failures = listOf(
                    ExtractionFailure(
                        code = ExtractionFailureCode.MODEL_TIMEOUT,
                        at = STARTED_AT,
                        invocation = ExtractionInvocationId(invocationIndex = 1, attempt = 2),
                    ),
                ),
            )
        }.withMessageContaining("no invocation record for")

        // A failure outside any model call names none, and a run with no invocations still takes it.
        assertThat(
            runWith(
                failures = listOf(
                    ExtractionFailure(
                        code = ExtractionFailureCode.SOURCE_UNAVAILABLE,
                        stage = ExtractionFailureStage.SOURCE_READ,
                        at = STARTED_AT,
                    ),
                ),
            ).failures.single().invocation,
        ).isNull()
    }

    @Test
    fun `an empty mutable collection handed in is still copied`() {
        // The bug this is here for: a defensive copy skipped when the collection is empty leaves
        // the run aliasing the caller's list, and the caller fills it afterwards.
        val revisions = mutableListOf<SourceRevisionRef>()
        val invocations = mutableListOf<ExtractionInvocationRecord>()
        val failures = mutableListOf<ExtractionFailure>()

        val run = ExtractionRun(
            contextId = CONTEXT,
            lineage = ExtractionRunLineage.root(RUN),
            status = ExtractionRunStatus.RUNNING,
            startedAt = STARTED_AT,
            sourceRevisions = revisions,
            invocations = invocations,
            failures = failures,
        )

        revisions += REVISION_ONE
        invocations += ExtractionInvocationRecord.planned(0)
        failures += ExtractionFailure(code = ExtractionFailureCode.INTERNAL, at = STARTED_AT)

        assertThat(run.sourceRevisions).isEmpty()
        assertThat(run.invocations).isEmpty()
        assertThat(run.failures).isEmpty()
    }

    @Test
    fun `a populated mutable collection handed in is copied too`() {
        val revisions = mutableListOf(REVISION_ONE)

        val run = runWith(sourceRevisions = revisions)
        revisions += REVISION_TWO

        assertThat(run.sourceRevisions).containsExactly(REVISION_ONE)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the collections a run hands back cannot be edited`() {
        val run = ExtractionRunFixtures.populatedRun()

        assertThrows<UnsupportedOperationException> {
            (run.sourceRevisions as MutableList<SourceRevisionRef>).add(REVISION_ONE)
        }
        assertThrows<UnsupportedOperationException> {
            (run.invocations as MutableList<ExtractionInvocationRecord>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (run.failures as MutableList<ExtractionFailure>).clear()
        }
    }

    @Test
    fun `two runs built from the same values are equal and hash alike`() {
        val one = ExtractionRunFixtures.populatedRun()
        val two = ExtractionRunFixtures.populatedRun()

        assertThat(one).isEqualTo(two)
        assertThat(one.hashCode()).isEqualTo(two.hashCode())
        assertThat(one).isEqualTo(one)
        assertThat(one).isNotEqualTo(null)
        assertThat(one).isNotEqualTo("not a run")
        assertThat(setOf(one, two)).hasSize(1)
    }

    @Test
    fun `changing any single component makes it a different run`() {
        val base = ExtractionRunFixtures.populatedRun()

        // Hand-written equality has to cover every component, so every component gets varied. A
        // field left out of equals shows up here as an equal pair that should not be.
        val variants: Map<String, ExtractionRun> = mapOf(
            "contextId" to base.copyWith(contextId = OTHER_CONTEXT),
            "lineage" to base.copyWith(lineage = ExtractionRunLineage.root(ExtractionRunRef("run-other"))),
            "status" to base.copyWith(status = ExtractionRunStatus.CANCELLED),
            "startedAt" to base.copyWith(startedAt = STARTED_AT.minusSeconds(60)),
            "finishedAt" to base.copyWith(finishedAt = null),
            "profile" to base.copyWith(profile = ExtractionContentProfileRef("house-style", "v4")),
            "sourceRevisions" to base.copyWith(sourceRevisions = listOf(REVISION_ONE)),
            "fingerprints" to base.copyWith(fingerprints = ExtractionRunFingerprints()),
            "runtime" to base.copyWith(runtime = ExtractionRuntimeIdentity()),
            "requestedModel" to base.copyWith(requestedModel = ExtractionRequestedModelConfig()),
            "subjectRefs" to base.copyWith(subjectRefs = ExtractionRunSubjectRefs()),
            "experimentRef" to base.copyWith(experimentRef = null),
            "cohortRef" to base.copyWith(cohortRef = null),
            "replayFidelity" to base.copyWith(replayFidelity = ExtractionReplayFidelity.METADATA),
            "counts" to base.copyWith(counts = ExtractionRunCounts()),
            // Drops the first record and keeps the one the failure names, since a run rejects a
            // failure whose invocation it has no record of.
            "invocations" to base.copyWith(invocations = base.invocations.drop(1)),
            "failures" to base.copyWith(failures = emptyList()),
            "version" to base.copyWith(version = base.version + 1),
        )

        assertThat(variants).hasSize(18)
        variants.forEach { (component, variant) ->
            assertThat(variant)
                .describedAs("a run differing only in %s", component)
                .isNotEqualTo(base)
        }
    }

    @Test
    fun `the same run id in two tenants is two runs`() {
        val here = ExtractionRunFixtures.startedRun(contextId = CONTEXT)
        val there = ExtractionRunFixtures.startedRun(contextId = OTHER_CONTEXT)

        assertThat(here.key()).isNotEqualTo(there.key())
        assertThat(here).isNotEqualTo(there)
        assertThat(here.key()).isEqualTo(ExtractionRunKey(CONTEXT, RUN))
        assertThat(here.key().getContextIdValue()).isEqualTo("tenant-4f2c9a")
        assertThat(here.getContextIdValue()).isEqualTo("tenant-4f2c9a")
        assertThat(setOf(here.key(), there.key())).hasSize(2)
    }

    @Test
    fun `the Java-facing factory takes the tenant as a string and matches the constructor`() {
        val fromFactory = ExtractionRun.of(
            contextIdValue = "tenant-4f2c9a",
            lineage = ExtractionRunLineage.root(RUN),
            status = ExtractionRunStatus.RUNNING,
            startedAt = STARTED_AT,
        )

        assertThat(fromFactory).isEqualTo(ExtractionRunFixtures.startedRun())
        assertThat(fromFactory.contextId).isEqualTo(ContextId("tenant-4f2c9a"))
    }

    private fun runWith(
        sourceRevisions: List<SourceRevisionRef> = emptyList(),
        invocations: List<ExtractionInvocationRecord> = emptyList(),
        failures: List<ExtractionFailure> = emptyList(),
    ): ExtractionRun = ExtractionRun(
        contextId = CONTEXT,
        lineage = ExtractionRunLineage.root(RUN),
        status = ExtractionRunStatus.RUNNING,
        startedAt = STARTED_AT,
        sourceRevisions = sourceRevisions,
        invocations = invocations,
        failures = failures,
    )
}

/**
 * Rebuilds a run with one component replaced.
 *
 * [ExtractionRun] is a plain class, so it has no generated `copy`; the equality test needs one and
 * this keeps it in the test rather than widening the API for it.
 */
@Suppress("LongParameterList")
private fun ExtractionRun.copyWith(
    contextId: ContextId = this.contextId,
    lineage: ExtractionRunLineage = this.lineage,
    status: ExtractionRunStatus = this.status,
    startedAt: java.time.Instant = this.startedAt,
    finishedAt: java.time.Instant? = this.finishedAt,
    profile: ExtractionContentProfileRef? = this.profile,
    sourceRevisions: List<SourceRevisionRef> = this.sourceRevisions,
    fingerprints: ExtractionRunFingerprints = this.fingerprints,
    runtime: ExtractionRuntimeIdentity = this.runtime,
    requestedModel: ExtractionRequestedModelConfig? = this.requestedModel,
    subjectRefs: ExtractionRunSubjectRefs = this.subjectRefs,
    experimentRef: ExtractionExperimentRef? = this.experimentRef,
    cohortRef: ExtractionCohortRef? = this.cohortRef,
    replayFidelity: ExtractionReplayFidelity = this.replayFidelity,
    counts: ExtractionRunCounts = this.counts,
    invocations: List<ExtractionInvocationRecord> = this.invocations,
    failures: List<ExtractionFailure> = this.failures,
    version: Long = this.version,
): ExtractionRun = ExtractionRun(
    contextId = contextId,
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
