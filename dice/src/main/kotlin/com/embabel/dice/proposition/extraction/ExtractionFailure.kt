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
 * What kind of thing went wrong, from a fixed list.
 *
 * The code is what a query groups by and what an alert fires on, so it comes from a fixed list
 * that no caller can extend at a call site. Anything that does not fit is [INTERNAL] or
 * [UNCLASSIFIED], and a code that keeps getting used for the wrong thing is a signal to add one.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
enum class ExtractionFailureCode {

    /** The model or its service could not be reached, or refused to serve the request. */
    MODEL_UNAVAILABLE,

    /** The call was still outstanding when the configured timeout expired. */
    MODEL_TIMEOUT,

    /** The provider declined to answer — a safety refusal, a content filter, a policy block. */
    MODEL_REFUSED,

    /** The provider rejected the call for quota or rate reasons. */
    RATE_LIMITED,

    /** A response arrived and could not be parsed into the expected shape. */
    DECODE_FAILED,

    /** A response parsed and then broke the schema or the metamodel it had to satisfy. */
    SCHEMA_VIOLATION,

    /** The source material could not be read at the revision the run was asked for. */
    SOURCE_UNAVAILABLE,

    /** Extraction produced results and storing them failed. */
    PERSISTENCE_FAILED,

    /** The run, or this part of it, was stopped before it finished. */
    CANCELLED,

    /** A defect on DICE's side of the boundary. */
    INTERNAL,

    /** Nothing above fits, and the caller would rather record the failure than force a code. */
    UNCLASSIFIED,
}

/**
 * Where in a run's work the failure happened, from a fixed list.
 *
 * [ExtractionFailureCode] says what went wrong and this says where, which are different questions:
 * an [ExtractionFailureCode.INTERNAL] during [CHUNKING] and one during [PERSISTENCE] send an
 * on-call engineer to two different places.
 *
 * Nothing cross-checks a stage against a code. Most pairings that look impossible turn out to
 * happen — a decode failure while persisting is a real thing when a store reads a value back — and
 * a record that refuses to state an awkward truth is worse evidence than one that states it.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
enum class ExtractionFailureStage {

    /** Laying out the call plan, before any request went out. */
    PLANNING,

    /** Reading the source material at the revision the run was asked for. */
    SOURCE_READ,

    /** Splitting source material into the pieces each call would cover. */
    CHUNKING,

    /** Building the prompt for a call. */
    PROMPT_RENDER,

    /** The call to the model provider, from dispatch to response. */
    MODEL_CALL,

    /** Turning the provider's response into the shape the run expected. */
    RESPONSE_DECODE,

    /** Checking a decoded response against the schema and metamodel in force. */
    SCHEMA_CHECK,

    /** Running extracted propositions past the gates. */
    GATING,

    /** Resolving entity mentions to entities. */
    ENTITY_RESOLUTION,

    /** Storing what the run produced. */
    PERSISTENCE,
}

/**
 * What a number attached to a failure counts, with its unit in the name.
 *
 * A bare number on a failure record is a unit-mismatch bug waiting to happen: 30000 is half a
 * minute or thirty seconds or thirty thousand tokens depending on who wrote it. Every value here
 * names both the thing and the unit, so a reader and a dashboard agree without a convention to
 * remember.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
enum class ExtractionFailureQuantity {

    /** Tokens the failure was about: a budget that was exceeded, or what a call asked for. */
    TOKEN_COUNT,

    /** Characters of material the failure was about. */
    CHARACTER_COUNT,

    /** Bytes the failure was about. */
    BYTE_COUNT,

    /** How many things were involved — chunks, propositions, entity mentions. */
    ITEM_COUNT,

    /** Milliseconds that had passed when the work gave up. */
    ELAPSED_MILLIS,

    /** The time limit in force, in milliseconds. */
    LIMIT_MILLIS,

    /** How many seconds the provider asked the caller to wait before trying again. */
    RETRY_AFTER_SECONDS,
}

/**
 * One number a failure record carries, together with what it counts.
 *
 * Pairing them in a type is the point: there is no way to record 4096 without saying it is tokens,
 * and no way to say "tokens" without a number.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property quantity What the number counts, and in what unit
 * @property value The number
 */
@ApiStatus.Experimental
data class ExtractionFailureMeasure(
    val quantity: ExtractionFailureQuantity,
    val value: Long,
) {

    init {
        require(value >= 0) { "$quantity must not be negative, was $value" }
    }

    override fun toString(): String = "$quantity=$value"

    companion object {

        /** Java-friendly factory. */
        @JvmStatic
        fun of(quantity: ExtractionFailureQuantity, value: Long): ExtractionFailureMeasure =
            ExtractionFailureMeasure(quantity, value)
    }
}

/**
 * One thing that went wrong during a run, said entirely in a closed vocabulary.
 *
 * A failure record is evidence, and evidence about a failure is where source text leaks. A model
 * provider routinely quotes the prompt back in its exception message, a decode error carries the
 * fragment it choked on, and both used to reach a stored run header the moment someone wrote
 * `e.message` into a text field. So this record has no text field, and there is no constructor,
 * factory, or property here that will accept a `String` or a `Throwable`. Prompt content, personal
 * data, credentials and protected material have no route into durable storage through this type,
 * because there is nothing shaped to hold them.
 *
 * It holds five things a dashboard can group by and a person can read: the [code], the [stage] it
 * happened in, the provider's own [providerStatus], one [measure] answering "how much", and the
 * [invocation] it belongs to. That last one already carries the call's ordinal in the plan, so
 * "chunk 3 failed" is expressible without a sentence about chunk 3.
 *
 * One measure per record, deliberately. A failure answers one "how much"; a host that wants the
 * exceeded budget *and* the elapsed time is describing two facts, and the second one belongs with
 * the rest of its detail.
 *
 * **Where the detail goes.** A host that needs the exception message, the response body, or the
 * fragment that failed to parse keeps that material itself, under its own retention and access
 * rules. `ProtectedContentRef` is the specification for the reference such a host hands around;
 * DICE writes nothing behind it and stores none of it.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property code How the failure is classified
 * @property stage Where in the run's work it happened, when that is known
 * @property providerStatus The status the provider returned, as an HTTP status code
 * @property measure One number about the failure, with its unit
 * @property at When the failure was recorded
 * @property invocation The invocation and attempt this failure belongs to, or null for a failure
 *   that happened outside any model call
 */
@ApiStatus.Experimental
data class ExtractionFailure @JvmOverloads constructor(
    val code: ExtractionFailureCode,
    val stage: ExtractionFailureStage? = null,
    val providerStatus: Int? = null,
    val measure: ExtractionFailureMeasure? = null,
    val at: Instant = Instant.now(),
    val invocation: ExtractionInvocationId? = null,
) {

    init {
        requireProviderStatus(providerStatus)
    }

    companion object {

        /** Java-friendly factory. */
        @JvmStatic
        @JvmOverloads
        fun of(
            code: ExtractionFailureCode,
            stage: ExtractionFailureStage? = null,
            providerStatus: Int? = null,
            measure: ExtractionFailureMeasure? = null,
            at: Instant = Instant.now(),
            invocation: ExtractionInvocationId? = null,
        ): ExtractionFailure = ExtractionFailure(
            code = code,
            stage = stage,
            providerStatus = providerStatus,
            measure = measure,
            at = at,
            invocation = invocation,
        )
    }
}
