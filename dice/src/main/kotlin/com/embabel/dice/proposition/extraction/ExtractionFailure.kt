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
 * The code is what a query groups by and what an alert fires on, so it is an enum rather than a
 * string a caller invents per site. Anything that does not fit is [INTERNAL] or [UNCLASSIFIED],
 * and a code that keeps getting used for the wrong thing is a signal to add one.
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
 * One thing that went wrong during a run: a code, a short detail, when, and which invocation.
 *
 * A failure record is evidence, and evidence about a failure is where source text leaks. A model
 * provider routinely quotes the prompt back in its exception message, a decode error carries the
 * fragment it choked on, and both end up in a stored run header if someone writes
 * `e.message` into one. So the DICE-minted path, [fromThrowable], never reads
 * `Throwable.message` at all: it records the exception class names down the cause chain and
 * nothing else, which cannot contain source text because it never touched any.
 *
 * [of] exists for the case where a caller genuinely knows something useful ("chunk 3 of 12
 * exceeded the token budget"). DICE cannot check what a caller puts there. It bounds it, flattens
 * it to a single line so a pasted stack trace does not fit, and the contract is that the caller
 * supplies a classification rather than a payload.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property code How the failure is classified
 * @property detail Short, single-line, sanitized explanation. Empty when the code says it all.
 * @property at When the failure was recorded
 * @property invocation The invocation and attempt this failure belongs to, or null for a failure
 *   that happened outside any model call
 */
@ApiStatus.Experimental
data class ExtractionFailure @JvmOverloads constructor(
    val code: ExtractionFailureCode,
    val detail: String = "",
    val at: Instant = Instant.now(),
    val invocation: ExtractionInvocationId? = null,
) {

    init {
        require(detail.length <= ExtractionRunLimits.MAX_FAILURE_DETAIL_LENGTH) {
            "detail must be at most ${ExtractionRunLimits.MAX_FAILURE_DETAIL_LENGTH} characters, " +
                "was ${detail.length}"
        }
        require(detail.none { it == '\n' || it == '\r' }) {
            "detail must be a single line; a multi-line detail is a stack trace or a quoted payload"
        }
    }

    companion object {

        /** How many links of a cause chain [fromThrowable] walks. */
        const val MAX_CAUSE_CHAIN: Int = 5

        /**
         * Records a failure with a detail the caller wrote.
         *
         * Whitespace collapses to single spaces and the result is clipped to
         * [ExtractionRunLimits.MAX_FAILURE_DETAIL_LENGTH]. The caller is responsible for the
         * detail holding no source text, no prompt, and no personal data; nothing here can verify
         * that. [fromThrowable] is the path to use when the detail would have come from an
         * exception.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            code: ExtractionFailureCode,
            detail: String,
            at: Instant = Instant.now(),
            invocation: ExtractionInvocationId? = null,
        ): ExtractionFailure = ExtractionFailure(
            code = code,
            detail = sanitize(detail),
            at = at,
            invocation = invocation,
        )

        /**
         * Records a failure from a throwable, using its class names and nothing else.
         *
         * The detail is the exception class name, then up to [MAX_CAUSE_CHAIN] causes joined by
         * ` <- `. `Throwable.message`, suppressed exceptions, and the stack trace are all
         * untouched, so the run header cannot pick up the prompt, the response body, or the
         * fragment that failed to parse.
         *
         * A host that wants the message keeps it in its own logs, where retention and access are
         * its to set.
         */
        @JvmStatic
        @JvmOverloads
        fun fromThrowable(
            code: ExtractionFailureCode,
            throwable: Throwable,
            at: Instant = Instant.now(),
            invocation: ExtractionInvocationId? = null,
        ): ExtractionFailure = ExtractionFailure(
            code = code,
            detail = sanitize(causeChain(throwable).joinToString(" <- ")),
            at = at,
            invocation = invocation,
        )

        private fun causeChain(throwable: Throwable): List<String> {
            val names = mutableListOf<String>()
            var current: Throwable? = throwable
            val seen = mutableSetOf<Throwable>()
            while (current != null && names.size < MAX_CAUSE_CHAIN && seen.add(current)) {
                names += current.javaClass.name
                current = current.cause
            }
            return names
        }

        private fun sanitize(detail: String): String =
            detail.replace(WHITESPACE, " ").trim().take(ExtractionRunLimits.MAX_FAILURE_DETAIL_LENGTH)

        private val WHITESPACE = Regex("\\s+")
    }
}
