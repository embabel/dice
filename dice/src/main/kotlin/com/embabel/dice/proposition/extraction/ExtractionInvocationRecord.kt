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
import java.time.Instant

/**
 * The stable identity of one model call within a run: which call, and which try at it.
 *
 * [invocationIndex] is the call's ordinal in the run's call plan, and it is allocated when the
 * plan is laid out — before any call goes out. Two runs that chunk the same material the same way
 * give the same piece of work the same index, whichever finishes first. [attempt] counts tries at
 * that same call, starting at 1.
 *
 * Nothing derives an identity from completion order. There is no factory that takes a position in
 * a result list, and parallel execution writes into identities that already exist rather than
 * minting them as answers arrive. That is what makes (`runId`, index, attempt) a deterministic
 * child key: a retried write lands on its own row and a replayed write upserts in place.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property invocationIndex Ordinal of this call in the run's plan, counting from zero
 * @property attempt Which try at that call this is, counting from one
 */
@ApiStatus.Experimental
data class ExtractionInvocationId(
    val invocationIndex: Int,
    val attempt: Int,
) {

    init {
        require(invocationIndex >= 0) { "invocationIndex must not be negative, was $invocationIndex" }
        require(attempt >= 1) { "attempt must be at least 1, was $attempt" }
    }

    /** The identity of the next try at the same call. */
    fun nextAttempt(): ExtractionInvocationId = copy(attempt = attempt + 1)

    override fun toString(): String = "invocation $invocationIndex attempt $attempt"

    companion object {

        /**
         * The identity of the first try at the call at [invocationIndex] in the plan.
         */
        @JvmStatic
        fun planned(invocationIndex: Int): ExtractionInvocationId =
            ExtractionInvocationId(invocationIndex = invocationIndex, attempt = 1)
    }
}

/**
 * How one attempt at one model call ended.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
enum class ExtractionInvocationOutcome {

    /** The identity exists and the call has not come back. This is what a planned call starts as. */
    IN_FLIGHT,

    /** A response arrived and was usable. */
    SUCCEEDED,

    /** The attempt failed. The run's failure records say how. */
    FAILED,

    /** The attempt was stopped before it produced anything. */
    CANCELLED,
}

/**
 * Tokens a call actually consumed, as the provider reported them.
 *
 * Recorded as reported. DICE does not recompute [totalTokens] from the other two or reconcile
 * them when a provider's arithmetic looks off, because the point of an observed record is what
 * was observed. Providers count cached and reasoning tokens differently and some report neither;
 * absent means the provider said nothing, not zero.
 *
 * Native usage objects are not stored. These are the portable counts, pulled out of whatever
 * shape the SDK returned.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property inputTokens Tokens the provider counted on the way in
 * @property outputTokens Tokens the provider counted on the way out
 * @property totalTokens The provider's own total, when it gave one
 * @property cachedInputTokens Input tokens the provider served from its cache
 * @property reasoningTokens Output tokens the provider attributed to reasoning
 */
@ApiStatus.Experimental
data class ExtractionModelUsage @JvmOverloads constructor(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val reasoningTokens: Int? = null,
) {

    init {
        requireNonNegative(inputTokens, "inputTokens")
        requireNonNegative(outputTokens, "outputTokens")
        requireNonNegative(totalTokens, "totalTokens")
        requireNonNegative(cachedInputTokens, "cachedInputTokens")
        requireNonNegative(reasoningTokens, "reasoningTokens")
    }

    companion object {

        /** Java-friendly factory. */
        @JvmStatic
        @JvmOverloads
        fun of(
            inputTokens: Int? = null,
            outputTokens: Int? = null,
            totalTokens: Int? = null,
            cachedInputTokens: Int? = null,
            reasoningTokens: Int? = null,
        ): ExtractionModelUsage = ExtractionModelUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            cachedInputTokens = cachedInputTokens,
            reasoningTokens = reasoningTokens,
        )
    }
}

/**
 * What the provider said about its own response.
 *
 * Every field is present only when the provider reported it. None of it is inferred from
 * [ExtractionRequestedModelConfig]: a run that asked for `gpt-x` and got no model name back
 * records a null [responseModel] rather than echoing what it asked for, because the whole point
 * of the field is telling those two cases apart.
 *
 * Response bodies, message content, and SDK objects are not stored — these are the identifiers
 * and the classification, which is what an incident needs to correlate with the provider's own
 * logs.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property responseModel The model the provider says answered
 * @property responseId The provider's id for the response, for correlating with its support logs
 * @property finishReason Why the provider stopped generating, in its own vocabulary
 * @property systemFingerprint The provider's fingerprint for the backend configuration that served
 *   the call
 */
@ApiStatus.Experimental
data class ExtractionProviderResponseFacts @JvmOverloads constructor(
    val responseModel: String? = null,
    val responseId: String? = null,
    val finishReason: String? = null,
    val systemFingerprint: String? = null,
) {

    init {
        requireBoundedIdentifier(responseModel, "responseModel")
        requireBoundedIdentifier(responseId, "responseId")
        requireBoundedIdentifier(finishReason, "finishReason")
        requireBoundedIdentifier(systemFingerprint, "systemFingerprint")
    }

    companion object {

        /** Java-friendly factory. */
        @JvmStatic
        @JvmOverloads
        fun of(
            responseModel: String? = null,
            responseId: String? = null,
            finishReason: String? = null,
            systemFingerprint: String? = null,
        ): ExtractionProviderResponseFacts = ExtractionProviderResponseFacts(
            responseModel = responseModel,
            responseId = responseId,
            finishReason = finishReason,
            systemFingerprint = systemFingerprint,
        )
    }
}

/**
 * One attempt at one model call: its identity, and what was observed about it.
 *
 * Observed facts only. There is no requested-configuration field on this type and there will not
 * be one — that separation is the whole reason [ExtractionRequestedModelConfig] is a different
 * type sitting on the run rather than a section of this one. A reader of an invocation record can
 * never mistake "what we asked for" for "what happened", because the record cannot express the
 * first.
 *
 * [configuredService] is an observed fact too: it is the service this attempt was actually
 * dispatched against, which a router can change between attempts.
 *
 * Failures live on the run's bounded failure list, tagged with this record's [id], rather than
 * being duplicated here — one bound, one place to sanitize. A run rejects a failure naming an
 * attempt it has no record of, so the pair arrives together.
 *
 * **Timing is an observation and may be absent on a terminal record.** A `SUCCEEDED` attempt with
 * no [startedAt] is constructible, and means the clock was not recorded rather than that the call
 * did not run. Requiring timing would push callers to invent it, and a made-up duration is worse
 * evidence than none. The two checks that do apply are the ones a record can be wrong about on its
 * own terms: a finish cannot precede its start, and an [ExtractionInvocationOutcome.IN_FLIGHT]
 * attempt has not finished.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property id Which call and which attempt this record is
 * @property outcome How the attempt ended
 * @property configuredService The service this attempt went to, as configured at dispatch
 * @property startedAt When the attempt was dispatched, or null if it never was
 * @property finishedAt When the attempt came back
 * @property usage Tokens the provider reported
 * @property providerResponse What the provider said about its response
 */
@ApiStatus.Experimental
data class ExtractionInvocationRecord @JvmOverloads constructor(
    val id: ExtractionInvocationId,
    val outcome: ExtractionInvocationOutcome = ExtractionInvocationOutcome.IN_FLIGHT,
    val configuredService: String? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val usage: ExtractionModelUsage? = null,
    val providerResponse: ExtractionProviderResponseFacts? = null,
) {

    init {
        requireBoundedIdentifier(configuredService, "configuredService")
        if (finishedAt != null) {
            require(startedAt != null) { "an attempt cannot finish without having started" }
            require(!finishedAt.isBefore(startedAt)) { "finishedAt must not be before startedAt" }
        }
        require(outcome != ExtractionInvocationOutcome.IN_FLIGHT || finishedAt == null) {
            "an IN_FLIGHT attempt has no finishedAt"
        }
    }

    /** Ordinal of this call in the run's plan. */
    val invocationIndex: Int
        get() = id.invocationIndex

    /** Which try at that call this record is. */
    val attempt: Int
        get() = id.attempt

    /**
     * The record for the next try at this same call.
     *
     * The index carries over, the attempt increments, and every observed field resets, because
     * the observations belonged to the attempt that just failed.
     */
    fun retry(): ExtractionInvocationRecord = ExtractionInvocationRecord(id = id.nextAttempt())

    companion object {

        /**
         * The record of a call that the plan has allocated and nothing has dispatched yet.
         *
         * Call this while laying out the plan, before the first request goes out, so the identity
         * exists before any answer can suggest one.
         */
        @JvmStatic
        fun planned(invocationIndex: Int): ExtractionInvocationRecord =
            ExtractionInvocationRecord(id = ExtractionInvocationId.planned(invocationIndex))

        /**
         * Lays out a whole call plan: [count] invocations, indices 0 to `count - 1`, all on their
         * first attempt and none dispatched.
         *
         * The count is checked against [ExtractionRunLimits.MAX_INVOCATIONS] before anything is
         * allocated. A plan size derived from chunking a large document can be enormous, and
         * finding that out from the run's own bound would mean building the whole list first.
         */
        @JvmStatic
        fun plan(count: Int): List<ExtractionInvocationRecord> {
            require(count >= 0) { "count must not be negative, was $count" }
            require(count <= ExtractionRunLimits.MAX_INVOCATIONS) {
                "a call plan may hold at most ${ExtractionRunLimits.MAX_INVOCATIONS} invocations, was $count"
            }
            return (0 until count).map { planned(it) }
        }
    }
}
