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
 * Every bound an extraction run obeys, in one place.
 *
 * One rule covers the whole run model: each bound is a named constant here, the check runs in the
 * `init` block of the type that owns the value, and anything over the bound is rejected at
 * construction. Truncating an identifier would be worse than rejecting it — a shortened id is a
 * different id, and a store would then key rows on a value the caller never minted.
 *
 * A failure detail is the one exception, and only on the way in. It is the model's only free-text
 * field, so [ExtractionFailure.of] and [ExtractionFailure.fromThrowable] shorten it to
 * [MAX_FAILURE_DETAIL_LENGTH] before construction; keeping a clipped failure record beats losing
 * the failure. The constructor still rejects a longer one.
 *
 * Lengths count UTF-16 chars (`String.length`), so a 256-char identifier can be around 1 KB of
 * UTF-8. The bound is there to keep a run header finite.
 *
 * `SourceRevisionRef` predates this rule and validates non-blank only, so [ExtractionRun] applies
 * [MAX_SOURCE_KEY_LENGTH] and [MAX_IDENTIFIER_LENGTH] to the revisions it stores. Moving those
 * checks into `SourceRevisionRef` is a follow-up in the module that owns it.
 *
 * One string a run stores is outside the rule and stays outside it: `ContextId.value`, which
 * `ExtractionRun` holds as its tenant and validates non-blank only. `ContextId` is a DICE-wide
 * type owned by the agent framework, so bounding it is not this model's call. It matters because
 * the tenant is half of the store key `ExtractionRunKey`, so the run store's key length is bounded
 * on one side only; whoever sizes that key's index decides what to do about the other side.
 */
@ApiStatus.Experimental
object ExtractionRunLimits {

    /**
     * Longest host-minted identifier a run stores: opaque reference tokens, fingerprints, model
     * and role names, service names, provider response ids, runtime identifiers. A uuid, a ULID,
     * a sha-256 hex digest, or a host correlation id all fit with room to spare.
     */
    const val MAX_IDENTIFIER_LENGTH: Int = 256

    /**
     * Longest source key a run stores. Source keys come out of `SourceLocator.key()` and can
     * legitimately hold a long URL, so they get more room than an identifier a host mints itself.
     */
    const val MAX_SOURCE_KEY_LENGTH: Int = 1024

    /**
     * Longest failure detail a run stores. Long enough for a classified one-line explanation,
     * short enough that a run with the full [MAX_FAILURES] of them stays small.
     */
    const val MAX_FAILURE_DETAIL_LENGTH: Int = 512

    /** Most source revisions one run may record. */
    const val MAX_SOURCE_REVISIONS: Int = 256

    /** Most invocation records one run may record, across every invocation and every attempt. */
    const val MAX_INVOCATIONS: Int = 1024

    /**
     * Most failure records one run may record. A run that fails this many times has a systemic
     * problem, and the hundredth message says nothing the first ten did not.
     */
    const val MAX_FAILURES: Int = 64
}

/**
 * Checks a host-minted identifier that may be absent.
 *
 * Error messages name the field and the length and never quote the value, because an
 * `IllegalArgumentException` propagates into logs and a rejected value is exactly the one nobody
 * vouched for.
 */
internal fun requireBoundedIdentifier(value: String?, field: String): String? {
    if (value == null) return null
    require(value.isNotBlank()) { "$field must not be blank when present" }
    require(value.length <= ExtractionRunLimits.MAX_IDENTIFIER_LENGTH) {
        "$field must be at most ${ExtractionRunLimits.MAX_IDENTIFIER_LENGTH} characters, was ${value.length}"
    }
    return value
}

/** Checks a count that may be absent and can never be negative. */
internal fun requireNonNegative(value: Int?, field: String): Int? {
    if (value == null) return null
    require(value >= 0) { "$field must not be negative, was $value" }
    return value
}

/** Checks a double that may be absent and must be a real number. */
internal fun requireFinite(value: Double?, field: String): Double? {
    if (value == null) return null
    require(value.isFinite()) { "$field must be a finite number, was $value" }
    return value
}

/** Checks a double that may be absent, must be a real number, and must not fall below [min]. */
internal fun requireAtLeast(value: Double?, field: String, min: Double): Double? {
    requireFinite(value, field)
    if (value == null) return null
    require(value >= min) { "$field must be at least $min, was $value" }
    return value
}

/** Checks a double that may be absent, must be a real number, and must sit within [min]..[max]. */
internal fun requireInRange(value: Double?, field: String, min: Double, max: Double): Double? {
    requireAtLeast(value, field, min)
    if (value == null) return null
    require(value <= max) { "$field must be at most $max, was $value" }
    return value
}
