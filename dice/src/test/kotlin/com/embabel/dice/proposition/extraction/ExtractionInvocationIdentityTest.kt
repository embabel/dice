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

import com.embabel.dice.provenance.SourceRevisionRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Invocation identity is allocated when the call plan is laid out and never derived from the order
 * answers come back. These tests pin both halves: the identities a plan hands out survive
 * out-of-order completion and retries, and the types offer no way to mint one from a position in a
 * result list.
 */
class ExtractionInvocationIdentityTest {

    private val startedAt = ExtractionRunFixtures.STARTED_AT

    @Test
    fun `a plan allocates every identity before anything is dispatched`() {
        val plan = ExtractionInvocationRecord.plan(4)

        assertThat(plan.map { it.invocationIndex }).containsExactly(0, 1, 2, 3)
        assertThat(plan.map { it.attempt }).containsExactly(1, 1, 1, 1)
        assertThat(plan.map { it.outcome })
            .allMatch { it == ExtractionInvocationOutcome.IN_FLIGHT }
        // Nothing has been dispatched, so there is no timing and nothing observed.
        assertThat(plan.map { it.startedAt }).allMatch { it == null }
        assertThat(plan.map { it.usage }).allMatch { it == null }
        assertThat(plan.map { it.providerResponse }).allMatch { it == null }
    }

    @Test
    fun `completion order does not touch identity`() {
        val plan = ExtractionInvocationRecord.plan(4)

        // Calls come back in the order 2, 0, 3, 1. Each answer writes into the identity its call
        // already had.
        val completed = listOf(2, 0, 3, 1).mapIndexed { arrivalPosition, planIndex ->
            plan[planIndex].copy(
                outcome = ExtractionInvocationOutcome.SUCCEEDED,
                startedAt = startedAt,
                finishedAt = startedAt.plusSeconds(arrivalPosition + 1L),
            )
        }

        assertThat(completed.map { it.invocationIndex }).containsExactly(2, 0, 3, 1)
        assertThat(completed.map { it.id }).containsExactlyElementsOf(
            listOf(2, 0, 3, 1).map { ExtractionInvocationId.planned(it) },
        )

        val run = runWith(completed)

        // Handed to the run in arrival order, held in plan order. Arrival order is not a fact about
        // the run — the same four calls answered in a different sequence are the same run — and
        // equals compares this list, so normalizing at construction is what makes those two runs
        // equal and what makes the two store backends agree.
        assertThat(run.invocations.map { it.invocationIndex }).containsExactly(0, 1, 2, 3)
        assertThat(run.invocationsInPlanOrder().map { it.invocationIndex }).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `two runs whose calls came back in different orders are equal`() {
        // The whole reason invocations are normalized. Before, these two compared unequal, and a
        // durable store — which keeps identified rows and reads them back in plan order — could not
        // return a run equal to the one an in-memory store returned for the same call sequence.
        val plan = ExtractionInvocationRecord.plan(3)
        val answered = { order: List<Int> ->
            runWith(
                order.map { planIndex ->
                    plan[planIndex].copy(
                        outcome = ExtractionInvocationOutcome.SUCCEEDED,
                        startedAt = startedAt,
                        finishedAt = startedAt.plusSeconds(1),
                    )
                },
            )
        }

        val arrivedForwards = answered(listOf(0, 1, 2))
        val arrivedBackwards = answered(listOf(2, 1, 0))

        assertThat(arrivedForwards).isEqualTo(arrivedBackwards)
        assertThat(arrivedForwards.hashCode()).isEqualTo(arrivedBackwards.hashCode())
        assertThat(arrivedForwards.toString()).isEqualTo(arrivedBackwards.toString())
    }

    @Test
    fun `retries of one call sort after that call's first attempt and before the next call`() {
        val run = runWith(
            listOf(
                ExtractionInvocationRecord.planned(1).retry(),
                ExtractionInvocationRecord.planned(2),
                ExtractionInvocationRecord.planned(0),
                ExtractionInvocationRecord.planned(1),
            ),
        )

        assertThat(run.invocations.map { it.id }).containsExactly(
            ExtractionInvocationId(0, 1),
            ExtractionInvocationId(1, 1),
            ExtractionInvocationId(1, 2),
            ExtractionInvocationId(2, 1),
        )
    }

    @Test
    fun `the order sources were read in is data and is left alone`() {
        // Normalization is for invocations only. Two runs that read the same sources in different
        // orders read them in different orders, and that is a fact about the run.
        val first = SourceRevisionRef("source-a", "rev-1")
        val second = SourceRevisionRef("source-b", "rev-1")

        val forwards = runWith(emptyList(), sourceRevisions = listOf(first, second))
        val backwards = runWith(emptyList(), sourceRevisions = listOf(second, first))

        assertThat(forwards.sourceRevisions).containsExactly(first, second)
        assertThat(backwards.sourceRevisions).containsExactly(second, first)
        assertThat(forwards).isNotEqualTo(backwards)
    }

    @Test
    fun `an attempt numbers a retry of the same call`() {
        val first = ExtractionInvocationRecord.planned(2).copy(
            outcome = ExtractionInvocationOutcome.FAILED,
            configuredService = "service-alpha",
            startedAt = startedAt,
            finishedAt = startedAt.plusSeconds(3),
            usage = ExtractionModelUsage(inputTokens = 100),
        )

        val second = first.retry()
        val third = second.retry()

        assertThat(listOf(first, second, third).map { it.invocationIndex }).containsExactly(2, 2, 2)
        assertThat(listOf(first, second, third).map { it.attempt }).containsExactly(1, 2, 3)
        // A retry carries the identity forward and nothing else: the observations belonged to the
        // attempt that just failed.
        assertThat(second.outcome).isEqualTo(ExtractionInvocationOutcome.IN_FLIGHT)
        assertThat(second.configuredService).isNull()
        assertThat(second.startedAt).isNull()
        assertThat(second.finishedAt).isNull()
        assertThat(second.usage).isNull()
    }

    @Test
    fun `attempts of one call sort under it, whatever order they were recorded in`() {
        val run = runWith(
            listOf(
                ExtractionInvocationRecord(ExtractionInvocationId(1, 2)),
                ExtractionInvocationRecord(ExtractionInvocationId(0, 1)),
                ExtractionInvocationRecord(ExtractionInvocationId(1, 1)),
                ExtractionInvocationRecord(ExtractionInvocationId(1, 3)),
            ),
        )

        assertThat(run.invocationsInPlanOrder().map { "${it.invocationIndex}/${it.attempt}" })
            .containsExactly("0/1", "1/1", "1/2", "1/3")
        assertThat(run.attemptsOf(1).map { it.attempt }).containsExactly(1, 2, 3)
        assertThat(run.attemptsOf(7)).isEmpty()
    }

    @Test
    fun `a run rejects two records with the same identity`() {
        assertThatIllegalArgumentException().isThrownBy {
            runWith(
                listOf(
                    ExtractionInvocationRecord(ExtractionInvocationId(0, 1)),
                    ExtractionInvocationRecord(ExtractionInvocationId(0, 1)),
                ),
            )
        }.withMessageContaining("distinct")
    }

    @Test
    fun `identities are validated at the edges`() {
        assertThatIllegalArgumentException().isThrownBy { ExtractionInvocationId(-1, 1) }
            .withMessageContaining("invocationIndex")
        assertThatIllegalArgumentException().isThrownBy { ExtractionInvocationId(0, 0) }
            .withMessageContaining("attempt")
        assertThatIllegalArgumentException().isThrownBy { ExtractionInvocationRecord.plan(-1) }
            .withMessageContaining("count")
        assertThat(ExtractionInvocationRecord.plan(0)).isEmpty()
    }

    @Test
    fun `a plan is bounded before it is allocated`() {
        // A plan size derived from chunking a large document can be enormous. The bound is checked
        // on the count, so an over-limit plan costs nothing rather than building the list first and
        // failing at the run.
        val atLimit = ExtractionInvocationRecord.plan(ExtractionRunLimits.MAX_INVOCATIONS)

        assertThat(atLimit).hasSize(ExtractionRunLimits.MAX_INVOCATIONS)
        assertThat(atLimit.last().invocationIndex).isEqualTo(ExtractionRunLimits.MAX_INVOCATIONS - 1)

        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionInvocationRecord.plan(ExtractionRunLimits.MAX_INVOCATIONS + 1) }
            .withMessageContaining("call plan may hold at most")
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionInvocationRecord.plan(Int.MAX_VALUE) }
            .withMessageContaining("call plan may hold at most")
    }

    @Test
    fun `timing is an observation and may be absent on a terminal record`() {
        // Deliberate: a recorded outcome with no clock means the timing was not observed, not that
        // the call did not run. Requiring it would push callers to invent a duration.
        val succeededWithoutTiming = ExtractionInvocationRecord(
            id = ExtractionInvocationId.planned(0),
            outcome = ExtractionInvocationOutcome.SUCCEEDED,
            configuredService = "service-alpha",
            usage = ExtractionModelUsage(inputTokens = 100),
        )

        assertThat(succeededWithoutTiming.startedAt).isNull()
        assertThat(succeededWithoutTiming.finishedAt).isNull()
        assertThat(succeededWithoutTiming.usage?.inputTokens).isEqualTo(100)

        // A started-but-untimed finish is recordable too, which is the half a dispatcher knows.
        val startedNotTimed = succeededWithoutTiming.copy(startedAt = startedAt)
        assertThat(startedNotTimed.finishedAt).isNull()
    }

    @Test
    fun `an in-flight attempt cannot have finished and a finish cannot precede a start`() {
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionInvocationRecord(
                id = ExtractionInvocationId.planned(0),
                startedAt = startedAt,
                finishedAt = startedAt.plusSeconds(1),
            )
        }.withMessageContaining("IN_FLIGHT")

        assertThatIllegalArgumentException().isThrownBy {
            ExtractionInvocationRecord(
                id = ExtractionInvocationId.planned(0),
                outcome = ExtractionInvocationOutcome.SUCCEEDED,
                startedAt = startedAt,
                finishedAt = startedAt.minusSeconds(1),
            )
        }.withMessageContaining("finishedAt")

        assertThatIllegalArgumentException().isThrownBy {
            ExtractionInvocationRecord(
                id = ExtractionInvocationId.planned(0),
                outcome = ExtractionInvocationOutcome.SUCCEEDED,
                finishedAt = startedAt,
            )
        }.withMessageContaining("without having started")
    }

    @Test
    fun `an attempt cancelled before dispatch is recordable`() {
        val cancelled = ExtractionInvocationRecord(
            id = ExtractionInvocationId.planned(3),
            outcome = ExtractionInvocationOutcome.CANCELLED,
        )

        assertThat(cancelled.startedAt).isNull()
        assertThat(cancelled.invocationIndex).isEqualTo(3)
    }

    @Test
    fun `nothing can mint an identity out of a completion position`() {
        // Every way to build a record demands an identity, and the only way to build an identity
        // is from a plan ordinal. There is no factory taking a result-list position, and this
        // asserts that rather than describing it.
        val recordFactories = ExtractionInvocationRecord::class.java.declaredMethods
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .filter { it.returnType == ExtractionInvocationRecord::class.java }
        assertThat(recordFactories.map { it.name }).containsExactly("planned")
        assertThat(recordFactories.single().parameterTypes).containsExactly(Int::class.javaPrimitiveType)

        val recordConstructors = ExtractionInvocationRecord::class.java.constructors
        assertThat(recordConstructors).allSatisfy { constructor ->
            assertThat(constructor.parameterTypes.first()).isEqualTo(ExtractionInvocationId::class.java)
        }

        val idFactories = ExtractionInvocationId::class.java.declaredMethods
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .filter { it.returnType == ExtractionInvocationId::class.java }
        assertThat(idFactories.map { it.name }).containsExactly("planned")
    }

    @Test
    fun `an invocation record cannot hold a requested configuration`() {
        // The separation is structural. No observed type has a field of the requested type, so a
        // mapper cannot fold one into the other and a reader cannot mistake the two.
        val observedTypes = listOf(
            ExtractionInvocationRecord::class.java,
            ExtractionModelUsage::class.java,
            ExtractionProviderResponseFacts::class.java,
        )
        observedTypes.forEach { type ->
            assertThat(type.declaredFields.map { it.type })
                .doesNotContain(ExtractionRequestedModelConfig::class.java)
        }
    }

    @Test
    fun `requested and observed model facts share no property name`() {
        // Different names on purpose: `requestedModel` is what was asked for and `responseModel`
        // is what the provider said answered. A shared name is how one silently becomes the other.
        val requested = propertyNames(ExtractionRequestedModelConfig::class.java)
        val observed = propertyNames(ExtractionProviderResponseFacts::class.java) +
            propertyNames(ExtractionModelUsage::class.java)

        assertThat(requested).isNotEmpty()
        assertThat(observed).isNotEmpty()
        assertThat(requested.intersect(observed)).isEmpty()
    }

    private fun propertyNames(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        .map { it.name }
        .toSet()

    private fun runWith(
        invocations: List<ExtractionInvocationRecord>,
        sourceRevisions: List<SourceRevisionRef> = emptyList(),
    ): ExtractionRun = ExtractionRun(
        contextId = ExtractionRunFixtures.CONTEXT,
        lineage = ExtractionRunLineage.root(ExtractionRunFixtures.RUN),
        status = ExtractionRunStatus.RUNNING,
        startedAt = startedAt,
        sourceRevisions = sourceRevisions,
        invocations = invocations,
    )
}
