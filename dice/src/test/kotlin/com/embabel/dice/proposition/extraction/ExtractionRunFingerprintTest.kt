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

import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.FINISHED_AT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The canonical encoding behind terminal-write idempotency.
 *
 * Two things have to hold. Two writes that mean the same must produce the same digest, or a correct
 * retry is rejected as an incompatible rewrite. Two writes that name different transitions must
 * produce different digests, or an incompatible rewrite is accepted as a retry — which is the
 * failure that loses audit evidence.
 *
 * What "mean the same" covers is the terminal status and the finish time, and nothing else. The
 * counts and failures a transition delivers stay outside the digest, which is what lets DICE #69
 * grow the outcome payload without moving a digest already stored beside a run.
 */
class ExtractionRunFingerprintTest {

    private fun failure(
        code: ExtractionFailureCode,
        stage: ExtractionFailureStage? = null,
        at: Instant = FINISHED_AT,
        invocation: ExtractionInvocationId? = null,
        providerStatus: Int? = null,
        measure: ExtractionFailureMeasure? = null,
    ) = ExtractionFailure(
        code = code,
        stage = stage,
        providerStatus = providerStatus,
        measure = measure,
        at = at,
        invocation = invocation,
    )

    // ---- what the digest covers ----

    @Test
    fun `changing the transition's identity changes its fingerprint`() {
        val baseline = ExtractionRunTransition.completed(
            finishedAt = FINISHED_AT,
            counts = ExtractionRunCounts(propositionsExtracted = 3, propositionsPersisted = 3),
            failures = listOf(failure(ExtractionFailureCode.RATE_LIMITED, ExtractionFailureStage.MODEL_CALL)),
        )
        val variants = mapOf(
            "status" to ExtractionRunTransition.cancelled(
                finishedAt = FINISHED_AT,
                counts = baseline.counts,
                failures = baseline.failures,
            ),
            "the other status" to ExtractionRunTransition.failed(
                finishedAt = FINISHED_AT,
                counts = baseline.counts,
                failures = baseline.failures,
            ),
            "finishedAt" to ExtractionRunTransition.completed(
                finishedAt = FINISHED_AT.plusNanos(1),
                counts = baseline.counts,
                failures = baseline.failures,
            ),
        )

        variants.forEach { (name, variant) ->
            assertThat(variant.fingerprint)
                .describedAs("a terminal write differing in %s", name)
                .isNotEqualTo(baseline.fingerprint)
        }
    }

    @Test
    fun `the outcome a transition carries never moves its fingerprint`() {
        // The property DICE #69 rests on. A run's counts and failures are data the terminal write
        // delivers; the digest names which terminal write it is. A payload that grows a field, or
        // a retry that carries better numbers, leaves every recorded digest matchable.
        val identity = ExtractionRunTransition.completed(FINISHED_AT).fingerprint

        val carrying = listOf(
            "counts" to ExtractionRunTransition.completed(
                FINISHED_AT,
                counts = ExtractionRunCounts(propositionsExtracted = 3, propositionsPersisted = 3),
            ),
            "zero counts" to ExtractionRunTransition.completed(FINISHED_AT, counts = ExtractionRunCounts()),
            "no failures" to ExtractionRunTransition.completed(FINISHED_AT, failures = emptyList()),
            "one failure" to ExtractionRunTransition.completed(
                FINISHED_AT,
                failures = listOf(failure(ExtractionFailureCode.RATE_LIMITED, ExtractionFailureStage.MODEL_CALL)),
            ),
            "several failures" to ExtractionRunTransition.completed(
                FINISHED_AT,
                counts = ExtractionRunCounts(entitiesResolved = 9),
                failures = listOf(
                    failure(ExtractionFailureCode.INTERNAL, ExtractionFailureStage.CHUNKING),
                    failure(
                        ExtractionFailureCode.MODEL_TIMEOUT,
                        ExtractionFailureStage.MODEL_CALL,
                        providerStatus = 504,
                        measure = ExtractionFailureMeasure(ExtractionFailureQuantity.ELAPSED_MILLIS, 30_000),
                    ),
                ),
            ),
        )

        carrying.forEach { (name, transition) ->
            assertThat(transition.fingerprint)
                .describedAs("a terminal write carrying %s names the same transition", name)
                .isEqualTo(identity)
        }
    }

    @Test
    fun `counts and failures still reach the run, even though they stay out of the digest`() {
        // The other half of the rule, so "outside the digest" is never read as "dropped". What a
        // transition carries is what the terminal run holds; equality on the transition sees it too.
        val running = ExtractionRunFixtures.runningRun("run-outcome-data")
        val counts = ExtractionRunCounts(propositionsExtracted = 3, propositionsPersisted = 2)
        val failures = listOf(failure(ExtractionFailureCode.RATE_LIMITED, ExtractionFailureStage.MODEL_CALL))

        val terminal = ExtractionRunTransition.completed(FINISHED_AT, counts, failures).applyTo(running)

        assertThat(terminal.counts).isEqualTo(counts)
        assertThat(terminal.failures).isEqualTo(failures)
        assertThat(ExtractionRunTransition.completed(FINISHED_AT, counts, failures))
            .isNotEqualTo(ExtractionRunTransition.completed(FINISHED_AT))
    }

    @Test
    fun `an instant is encoded at full precision whichever way it was built`() {
        // Sub-second precision survives: a run that finished 1ns after another is a different
        // terminal write, and an encoding that rendered to seconds would replay one as the other.
        val whole = Instant.ofEpochSecond(1_800_000_000L)
        val precisions = listOf(
            whole,
            whole.plusMillis(500),
            whole.plusNanos(1_000),
            whole.plusNanos(1),
        )

        val digests = precisions.map { ExtractionRunTransition.completed(it).fingerprint }
        assertThat(digests).doesNotHaveDuplicates()

        // The digest is a function of the instant's value, not of how the caller built it.
        assertThat(ExtractionRunTransition.completed(Instant.parse("2027-01-15T08:00:00Z")).fingerprint)
            .isEqualTo(ExtractionRunTransition.completed(Instant.ofEpochMilli(1_800_000_000_000L)).fingerprint)

        // Nothing here pins the rendering itself. `the digest of a fixed terminal write is pinned`
        // is what catches a change to it, which is the same discipline MetamodelVersion's content
        // hash uses: one golden literal over one fixed payload.
    }

    // ---- the persisted format ----

    @Test
    fun `the digest of a fixed terminal write is pinned`() {
        // This is a persisted format: a store keeps the digest beside the run and compares a retry
        // against it. Changing the encoding makes every recorded fingerprint unmatchable, so a
        // correct retry against an existing run would be rejected as an incompatible rewrite.
        // Moving this literal is how that decision gets made deliberately.
        val pinned = ExtractionRunTransition.completed(
            finishedAt = Instant.parse("2026-08-31T10:15:47Z"),
            counts = ExtractionRunCounts(
                sourcesRead = 2,
                chunksProcessed = 6,
                propositionsExtracted = 14,
                propositionsPersisted = 11,
                propositionsRejected = 3,
                entitiesResolved = 9,
            ),
            failures = listOf(
                ExtractionFailure(
                    code = ExtractionFailureCode.DECODE_FAILED,
                    stage = ExtractionFailureStage.RESPONSE_DECODE,
                    providerStatus = 502,
                    measure = ExtractionFailureMeasure(ExtractionFailureQuantity.CHARACTER_COUNT, 4_128),
                    at = Instant.parse("2026-08-31T10:15:47Z"),
                    invocation = ExtractionInvocationId(1, 2),
                ),
            ),
        )

        assertThat(pinned.fingerprint)
            .isEqualTo("b32bd0dd33ff0fe21397e0601f91b9f149250af9822d5fce633ad621baa44611")
    }

    @Test
    fun `a fingerprint is a lowercase sha-256 hex digest`() {
        val fingerprint = ExtractionRunTransition.completed(FINISHED_AT).fingerprint

        assertThat(fingerprint).hasSize(64)
        assertThat(fingerprint).matches("[0-9a-f]{64}")
    }

    @Test
    fun `the encoding carries a version tag`() {
        // A reader meeting a version it does not know matches nothing at all, with no guessing. The
        // tag moved to v2 when the payload narrowed to the transition's identity, so a digest
        // recorded under v1 matches nothing written now.
        assertThat(ExtractionRunFingerprint.TERMINAL_VERSION).isEqualTo("xrun-terminal:v2")
    }

    @Test
    fun `the fingerprint is a function of the transition's identity and nothing else`() {
        val transition = ExtractionRunTransition.completed(FINISHED_AT)

        assertThat(transition.fingerprint).isEqualTo(
            ExtractionRunFingerprint.ofTerminal(
                status = ExtractionRunStatus.COMPLETED,
                finishedAt = FINISHED_AT,
            ),
        )
    }

    @Test
    fun `a fingerprint never appears in full in a transition's toString`() {
        val transition = ExtractionRunTransition.completed(FINISHED_AT)

        assertThat(transition.toString()).doesNotContain(transition.fingerprint)
        assertThat(transition.toString()).contains(transition.fingerprint.take(12))
    }
}
